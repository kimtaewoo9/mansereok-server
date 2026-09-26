package com.mansereok.server.domain.discount.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 쿠폰과 할인 코드가 같은 할인가 계산 규칙(DiscountPolicy)을 쓰는지 확인한다.
 *
 * <p>같은 할인 표를 쿠폰과 할인 코드 양쪽에 돌려, 한쪽 규칙만 바뀌면 실패하게 한다. 두 규칙이 일부러 다른 곳은 100% 정률 할인
 * 하나뿐이고, 따로 표로 둔다. 원래 금액이 최소 결제 금액(1,000원)보다 싼 상품은 할인 종류마다(@EnumSource) 원래 금액을 넘지 않는지
 * 본다. 할인 종류를 새로 더해도 이 검사가 자동으로 따라온다.
 */
class DiscountPolicyTest {

	@ParameterizedTest(name = "[{index}] {0} {1} 을 {2}원에 적용하면 {3}원")
	@CsvSource(textBlock = """
		# 할인 종류,   할인 값, 원래 금액, 결제 금액
		FIXED_AMOUNT,  3000,  10000,  7000
		PERCENTAGE,      15,  10000,  8500
		# 할인액 999.9원은 999원으로 버린 뒤, 9000원에서 10원 단위 버림
		PERCENTAGE,      10,   9999,  9000
		# 29% 할인액은 정확히 841원이다. 실수로 계산하면 840원이 되어 2060원이 나온다
		PERCENTAGE,      29,   2900,  2050
		# 9005원은 10원 단위로 버려 9000원
		FIXED_AMOUNT,  1000,  10005,  9000
		# 500원이 되지만 최소 결제 금액 1000원
		FIXED_AMOUNT,  9500,  10000,  1000
		# 원래 금액이 1000원보다 싸면 최소 결제 금액이 아니라 원래 금액이 상한이다
		FIXED_AMOUNT,  3000,    500,   500
		PERCENTAGE,      50,    800,   800
		FIXED_AMOUNT,  3000,      0,     0
		""")
	@DisplayName("쿠폰과 할인 코드는 같은 할인 표에서 같은 결제 금액을 낸다")
	void couponAndDiscountCodeFollowSameTable(DiscountType discountType, int discountValue, int originalAmount,
		int expectedAmount) {
		// given
		Coupon coupon = CouponFixture.usableCoupon().discountType(discountType).discountValue(discountValue)
			.build();
		DiscountCode discountCode = DiscountCodeFixture.usableCode().discountType(discountType)
			.discountValue(discountValue).build();

		// when
		int couponAmount = coupon.applyDiscount(originalAmount);
		int discountCodeAmount = discountCode.applyDiscount(originalAmount);

		// then
		assertThat(couponAmount).as("쿠폰").isEqualTo(expectedAmount);
		assertThat(discountCodeAmount).as("할인 코드").isEqualTo(expectedAmount);
	}

	@ParameterizedTest(name = "[{index}] 100% 정률을 {0}원에 적용하면 쿠폰 {1}원, 할인 코드 {2}원")
	@CsvSource(textBlock = """
		# 원래 금액, 쿠폰 결제 금액, 할인 코드 결제 금액
		10000, 1000, 0
		  500,  500, 0
		""")
	@DisplayName("100% 정률 할인만 두 규칙이 다르다. 할인 코드는 0원(무료)이 되고 쿠폰은 최소 결제 금액을 받는다")
	void fullPercentageDiffersBetweenCouponAndDiscountCode(int originalAmount, int expectedCouponAmount,
		int expectedDiscountCodeAmount) {
		// given
		Coupon coupon = CouponFixture.usableCoupon().discountType(DiscountType.PERCENTAGE).discountValue(100)
			.build();
		DiscountCode discountCode = DiscountCodeFixture.percentage(100).build();

		// when
		int couponAmount = coupon.applyDiscount(originalAmount);
		int discountCodeAmount = discountCode.applyDiscount(originalAmount);

		// then
		assertThat(couponAmount).as("쿠폰").isEqualTo(expectedCouponAmount);
		assertThat(discountCodeAmount).as("할인 코드").isEqualTo(expectedDiscountCodeAmount);
	}

	@Nested
	@DisplayName("원래 금액이 최소 결제 금액(1,000원)보다 싼 상품이면")
	class WhenOriginalAmountIsBelowMinimumPayable {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(DiscountType.class)
		@DisplayName("어떤 할인 종류든 500원 상품의 결제 금액은 원래 금액 500원 그대로다")
		void neverChargesMoreThanOriginalAmount(DiscountType discountType) {
			// given
			Coupon coupon = CouponFixture.usableCoupon().discountType(discountType).discountValue(10).build();
			DiscountCode discountCode = DiscountCodeFixture.usableCode().discountType(discountType).discountValue(10)
				.build();

			// when
			int couponAmount = coupon.applyDiscount(500);
			int discountCodeAmount = discountCode.applyDiscount(500);

			// then
			assertThat(couponAmount).as("쿠폰").isEqualTo(500);
			assertThat(discountCodeAmount).as("할인 코드").isEqualTo(500);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(DiscountType.class)
		@DisplayName("어떤 할인 종류든 0원 상품의 결제 금액은 0원이다")
		void freeProductStaysFree(DiscountType discountType) {
			// given
			Coupon coupon = CouponFixture.usableCoupon().discountType(discountType).discountValue(10).build();
			DiscountCode discountCode = DiscountCodeFixture.usableCode().discountType(discountType).discountValue(10)
				.build();

			// when
			int couponAmount = coupon.applyDiscount(0);
			int discountCodeAmount = discountCode.applyDiscount(0);

			// then
			assertThat(couponAmount).as("쿠폰").isZero();
			assertThat(discountCodeAmount).as("할인 코드").isZero();
		}
	}

	@Test
	@DisplayName("주문 금액이 최소 주문 금액보다 1원이라도 적으면 쿠폰과 할인 코드 모두 같은 문구로 거절한다")
	void bothRejectAmountBelowMinimumPurchase() {
		// given
		Coupon coupon = CouponFixture.fixedAmount(1000).minPurchaseAmount(20000).build();
		DiscountCode discountCode = DiscountCodeFixture.fixedAmount(1000).minPurchaseAmount(20000).build();

		// when & then
		assertThatThrownBy(() -> coupon.applyDiscount(19999))
			.isInstanceOf(PaymentException.class)
			.hasMessage("최소 주문 금액(20000원)을 충족하지 못했습니다.");
		assertThatThrownBy(() -> discountCode.applyDiscount(19999))
			.isInstanceOf(PaymentException.class)
			.hasMessage("최소 주문 금액(20000원)을 충족하지 못했습니다.");
	}

	@ParameterizedTest(name = "[{index}] {0} {1} 을 {2}원에 적용하면 할인액 {3}원")
	@CsvSource(textBlock = """
		# 할인 종류,   할인 값, 원래 금액, 할인액
		FIXED_AMOUNT,  3000,  10000,  3000
		# 정액 할인액은 원래 금액보다 클 수 있다. 결제 금액 하한은 DiscountPolicy 가 정한다
		FIXED_AMOUNT,  3000,    500,  3000
		PERCENTAGE,      15,  10000,  1500
		PERCENTAGE,      10,   9999,   999
		PERCENTAGE,      29,   2900,   841
		""")
	@DisplayName("할인 종류마다 할인액을 스스로 정한다. 정액은 할인 값 그대로, 정률은 원래 금액의 할인 값 % 를 원 단위로 버린다")
	void discountTypeDecidesDiscountAmount(DiscountType discountType, int discountValue, int originalAmount,
		int expectedDiscount) {
		// when
		int discount = discountType.discountAmount(originalAmount, discountValue);

		// then
		assertThat(discount).isEqualTo(expectedDiscount);
	}
}
