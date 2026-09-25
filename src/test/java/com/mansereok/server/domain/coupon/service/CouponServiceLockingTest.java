package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 쿠폰을 사용 처리하는 두 메서드가 쿠폰 행을 스스로 잠가(findByIdWithLock) 읽는지와, 만료 뒤 결제된 주문 몫의 사용 처리 규칙을 확인한다.
 *
 * <p>잠금 자체는 DB 가 지키므로 여기서는 "잠금 조회로 읽는다" 까지만 본다. 리포지토리 목은 findByIdWithLock 만 스텁한다.
 * 코드가 잠그지 않는 findById 로 읽으면 스텁되지 않은 조회가 빈 값을 돌려줘 "쿠폰 없음" 으로 실패하고, MockitoExtension 의
 * strict stubs 가 쓰이지 않은 스텁을 알린다.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceLockingTest {

	private static final Long COUPON_ID = 7L;
	private static final LocalDateTime EARLIER_USE = LocalDateTime.of(2026, 9, 1, 10, 0);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private CouponTemplateRepository couponTemplateRepository;

	private CouponService couponService;

	@BeforeEach
	void setUp() {
		couponService = new CouponService(couponRepository, couponTemplateRepository);
	}

	@Nested
	@DisplayName("주문을 만들며 쿠폰을 사용 처리할 때(useCoupon)")
	class UseCoupon {

		@Test
		@DisplayName("쿠폰 행을 잠가 읽은 뒤 사용 처리한다")
		void readsCouponWithLockThenMarksUsed() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when
			couponService.useCoupon(COUPON_ID);

			// then
			assertThat(coupon.isUsed()).isTrue();
		}
	}

	@Nested
	@DisplayName("만료 뒤 결제된 주문 몫으로 쿠폰을 다시 사용 처리할 때(claimForPaidOrder)")
	class ClaimForPaidOrder {

		@Test
		@DisplayName("미사용 쿠폰이면 잠가 읽은 뒤 사용 처리하고 true 를 돌려준다")
		void claimsUnusedCoupon() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when
			boolean claimed = couponService.claimForPaidOrder(COUPON_ID);

			// then
			assertThat(claimed).isTrue();
			assertThat(coupon.isUsed()).isTrue();
			assertThat(coupon.getUsedAt()).isNotNull();
		}

		@Test
		@DisplayName("쿠폰 기간이 지났어도 결제는 이미 끝났으므로 사용 처리한다")
		void claimsExpiredCouponBecausePaymentIsDone() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID)
				.expiresAt(LocalDateTime.now().minusDays(1)).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when
			boolean claimed = couponService.claimForPaidOrder(COUPON_ID);

			// then
			assertThat(claimed).isTrue();
			assertThat(coupon.isUsed()).isTrue();
		}

		@Test
		@DisplayName("다른 주문이 이미 쓰고 있으면 아무것도 바꾸지 않고 false 를 돌려준다")
		void leavesCouponUsedByAnotherOrderUntouched() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID).usedAt(EARLIER_USE).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when
			boolean claimed = couponService.claimForPaidOrder(COUPON_ID);

			// then
			assertThat(claimed).isFalse();
			assertThat(coupon.isUsed()).isTrue();
			assertThat(coupon.getUsedAt()).as("다른 주문이 쓴 시각이 그대로 남는다").isEqualTo(EARLIER_USE);
		}

		@Test
		@DisplayName("쿠폰이 없으면 '쿠폰 정보를 찾을 수 없습니다.' 로 거절한다")
		void rejectsMissingCoupon() {
			// given
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> couponService.claimForPaidOrder(COUPON_ID))
				.isInstanceOf(PaymentException.class)
				.hasMessage("쿠폰 정보를 찾을 수 없습니다.");
		}
	}
}
