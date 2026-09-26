package com.mansereok.server.domain.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 쿠폰 한 장의 상태 규칙을 확인한다. 템플릿으로 쿠폰을 만들 때의 만료 시각, 사용 처리와 두 번째 사용 거절, 되돌리기, 100% 쿠폰의
 * 최소 결제 금액이다.
 *
 * <p>쿠폰은 "지금" 을 직접 읽으므로 기대 시각은 호출 앞뒤로 읽은 시각 사이에 드는지로 확인한다.
 */
class CouponTest {

	private static final LocalDateTime EARLIER_USE = LocalDateTime.of(2026, 9, 1, 10, 0);

	@Nested
	@DisplayName("템플릿으로 쿠폰을 만들 때(createFromTemplate)")
	class CreateFromTemplate {

		@Test
		@DisplayName("발급 후 30일 유효 템플릿이면 받은 시각부터 30일 뒤에 만료된다")
		void expiresThirtyDaysAfterIssue() {
			// given: CouponTemplateFixture 의 기본값이 "발급 후 30일 유효" 다
			CouponTemplate template = CouponTemplateFixture.issuableNow().build();
			LocalDateTime before = LocalDateTime.now();

			// when
			Coupon coupon = Coupon.createFromTemplate(template, 1L);

			// then
			LocalDateTime after = LocalDateTime.now();
			assertThat(coupon.getExpiresAt()).isBetween(before.plusDays(30), after.plusDays(30));
		}

		@Test
		@DisplayName("받은 사용자와 원본 템플릿을 기록하고 미사용 상태로 만든다")
		void recordsOwnerAndTemplateAsUnused() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(11L).build();

			// when
			Coupon coupon = Coupon.createFromTemplate(template, 3L);

			// then
			assertThat(coupon.getUserId()).isEqualTo(3L);
			assertThat(coupon.getTemplateId()).isEqualTo(11L);
			assertThat(coupon.isUsed()).isFalse();
			assertThat(coupon.getUsedAt()).isNull();
		}
	}

	@Nested
	@DisplayName("쿠폰을 사용 처리할 때(use)")
	class Use {

		@Test
		@DisplayName("미사용이고 기간 안이면 사용으로 바꾸고 사용 시각을 남긴다")
		void marksUsedWithUseTime() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().build();
			LocalDateTime before = LocalDateTime.now();

			// when
			coupon.use();

			// then
			LocalDateTime after = LocalDateTime.now();
			assertThat(coupon.isUsed()).isTrue();
			assertThat(coupon.getUsedAt()).isBetween(before, after);
		}

		@Test
		@DisplayName("두 번째로 사용하면 '이미 사용된 쿠폰입니다.' 로 거절하고 처음 사용 시각을 그대로 둔다")
		void rejectsSecondUse() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().usedAt(EARLIER_USE).build();

			// when & then
			assertThatThrownBy(coupon::use)
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

	@Test
	@DisplayName("100% 정률 쿠폰도 할인가가 1,000원 아래로 내려가지 않는다(100% 할인 코드는 0원이 된다)")
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
