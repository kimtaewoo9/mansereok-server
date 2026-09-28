package com.mansereok.server.domain.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 쿠폰 한 장의 상태 규칙을 확인한다. 템플릿으로 쿠폰을 만들 때의 만료 시각, 만료 판정(만료 시각 그 순간과 기간 없는 쿠폰), 사용
 * 처리와 두 번째 사용 거절, 되돌리기, 100% 쿠폰의 최소 결제 금액이다.
 *
 * <p>"지금" 은 서울 시간대로 고정한 Clock 에서 읽어 넘긴다. 테스트가 시스템 시계를 읽지 않으므로 언제 돌려도 같은 결과가 나온다.
 */
class CouponTest {

	// 2026-09-26 12:00 (서울)
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);
	private static final LocalDateTime EARLIER_USE = LocalDateTime.of(2026, 9, 1, 10, 0);

	@Nested
	@DisplayName("템플릿으로 쿠폰을 만들 때(createFromTemplate)")
	class CreateFromTemplate {

		@Test
		@DisplayName("발급 후 30일 유효 템플릿이면 받은 시각부터 30일 뒤에 만료된다")
		void expiresThirtyDaysAfterIssue() {
			// given: CouponTemplateFixture 의 기본값이 "발급 후 30일 유효" 다
			CouponTemplate template = CouponTemplateFixture.issuableNow().build();

			// when
			Coupon coupon = Coupon.createFromTemplate(template, 1L, NOW);

			// then
			assertThat(coupon.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0));
		}

		@Test
		@DisplayName("유효 기간이 없는 템플릿이면 만료 시각 없이(기간 없는 쿠폰으로) 만든다")
		void noExpiryWhenTemplateHasNoValidPeriod() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().noValidPeriod().build();

			// when
			Coupon coupon = Coupon.createFromTemplate(template, 1L, NOW);

			// then
			assertThat(coupon.getExpiresAt()).isNull();
		}

		@Test
		@DisplayName("받은 사용자와 원본 템플릿을 기록하고 미사용 상태로 만든다")
		void recordsOwnerAndTemplateAsUnused() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(11L).build();

			// when
			Coupon coupon = Coupon.createFromTemplate(template, 3L, NOW);

			// then
			assertThat(coupon.getUserId()).isEqualTo(3L);
			assertThat(coupon.getTemplateId()).isEqualTo(11L);
			assertThat(coupon.isUsed()).isFalse();
			assertThat(coupon.getUsedAt()).isNull();
		}
	}

	@Nested
	@DisplayName("만료됐는지 볼 때(isExpired)")
	class IsExpired {

		@ParameterizedTest(name = "[{index}] 만료 시각이 지금보다 {0}초 뒤 → 만료 {1}")
		@CsvSource(textBlock = """
			# 만료 시각(지금 기준 초. 음수는 과거), 만료 여부
			-1, true
			 0, false
			 1, false
			""")
		@DisplayName("만료 시각이 지나야 만료이고, 만료 시각 그 순간까지는 쓸 수 있다")
		void expiredOnlyAfterExpiryTime(long expiresInSeconds, boolean expected) {
			// given
			Coupon coupon = CouponFixture.usableCoupon().expiresAt(NOW.plusSeconds(expiresInSeconds)).build();

			// when
			boolean expired = coupon.isExpired(NOW);

			// then
			assertThat(expired).isEqualTo(expected);
		}

		@Test
		@DisplayName("만료 시각이 없는(기간 없는) 쿠폰은 만료되지 않는다")
		void couponWithoutExpiryNeverExpires() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().expiresAt(null).build();

			// when
			boolean expired = coupon.isExpired(NOW);

			// then
			assertThat(expired).isFalse();
		}
	}

	@Nested
	@DisplayName("쿠폰을 사용 처리할 때(use)")
	class Use {

		@Test
		@DisplayName("미사용이고 기간 안이면 사용으로 바꾸고 지금을 사용 시각으로 남긴다")
		void marksUsedWithUseTime() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().expiresAt(NOW.plusDays(1)).build();

			// when
			coupon.use(NOW);

			// then
			assertThat(coupon.isUsed()).isTrue();
			assertThat(coupon.getUsedAt()).isEqualTo(NOW);
		}

		@Test
		@DisplayName("만료 시각이 없는(기간 없는) 쿠폰도 사용 처리한다")
		void usesCouponWithoutExpiry() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().expiresAt(null).build();

			// when
			coupon.use(NOW);

			// then
			assertThat(coupon.isUsed()).isTrue();
			assertThat(coupon.getUsedAt()).isEqualTo(NOW);
		}

		@Test
		@DisplayName("만료 시각이 1초라도 지났으면 '기간이 만료된 쿠폰입니다.' 로 거절하고 미사용으로 둔다")
		void rejectsExpiredCoupon() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().expiresAt(NOW.minusSeconds(1)).build();

			// when & then
			assertThatThrownBy(() -> coupon.use(NOW))
				.isInstanceOf(PaymentException.class)
				.hasMessage("기간이 만료된 쿠폰입니다.");
			assertThat(coupon.isUsed()).isFalse();
		}

		@Test
		@DisplayName("두 번째로 사용하면 '이미 사용된 쿠폰입니다.' 로 거절하고 처음 사용 시각을 그대로 둔다")
		void rejectsSecondUse() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().usedAt(EARLIER_USE).build();

			// when & then
			assertThatThrownBy(() -> coupon.use(NOW))
				.isInstanceOf(PaymentException.class)
				.hasMessage("이미 사용된 쿠폰입니다.");
			assertThat(coupon.getUsedAt()).isEqualTo(EARLIER_USE);
		}
	}

	@Test
	@DisplayName("사용한 쿠폰을 되돌리면 미사용이 되고 사용 시각이 지워진다")
	void restoreMarksUnusedAndClearsUseTime() {
		// given
		Coupon coupon = CouponFixture.usableCoupon().usedAt(EARLIER_USE).build();

		// when
		coupon.restore();

		// then
		assertThat(coupon.isUsed()).isFalse();
		assertThat(coupon.getUsedAt()).isNull();
	}

	// 할인 코드와 규칙이 다르다. 100% 할인 코드는 0원이 되고, 그 규칙은 DiscountCodeTest 가 확인한다.
	@Test
	@DisplayName("100% 정률 쿠폰도 할인가가 1,000원 아래로 내려가지 않는다")
	void fullPercentageCouponKeepsMinimumPayableAmount() {
		// given
		Coupon coupon = CouponFixture.usableCoupon().discountType(DiscountType.PERCENTAGE).discountValue(100)
			.build();

		// when
		int finalAmount = coupon.applyDiscount(10000);

		// then
		assertThat(finalAmount).isEqualTo(1000);
	}
}
