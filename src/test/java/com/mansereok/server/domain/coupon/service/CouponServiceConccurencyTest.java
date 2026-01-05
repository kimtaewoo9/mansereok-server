package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.global.exception.PaymentException;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CouponServiceTest {

	@Test
	@DisplayName("정률 할인(10%) 계산이 정확해야 한다 (10원 단위 절삭 포함)")
	void applyPercentageDiscount() throws Exception {
		// given
		// 1. 템플릿 생성 (Reflection 사용)
		CouponTemplate template = createDummyTemplate(DiscountType.PERCENTAGE, 10, 10000);

		// 2. 정적 팩토리 메서드로 쿠폰 생성 (Coupon.issue 테스트)
		Coupon coupon = Coupon.createFromTemplate(template, 1L);

		// when
		int finalAmount = coupon.applyDiscount(20000); // 20,000원 -> 10% 할인 -> 18,000원

		// then
		assertThat(finalAmount).isEqualTo(18000);
	}

	@Test
	@DisplayName("최소 주문 금액 미달 시 예외가 발생해야 한다")
	void validateMinPurchaseAmount() throws Exception {
		// given
		// 1. 템플릿 생성 (최소주문 30000원 설정)
		CouponTemplate template = createDummyTemplate(DiscountType.FIXED_AMOUNT, 1000, 30000);

		// 2. 쿠폰 생성
		Coupon coupon = Coupon.createFromTemplate(template, 1L);

		// when & then
		assertThatThrownBy(() -> coupon.applyDiscount(20000))
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining("최소 주문 금액");
	}

	// 엔티티 코드 수정 없이, 테스트에서만 강제로 객체 생성하는 헬퍼 메서드
	private CouponTemplate createDummyTemplate(DiscountType type, int value, int minAmount)
		throws Exception {
		// 1. Protected 생성자 강제 접근
		Constructor<CouponTemplate> constructor = CouponTemplate.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		CouponTemplate template = constructor.newInstance();

		// 2. 필드 값 주입
		ReflectionTestUtils.setField(template, "name", "테스트 쿠폰");
		ReflectionTestUtils.setField(template, "discountType", type);
		ReflectionTestUtils.setField(template, "discountValue", value);
		ReflectionTestUtils.setField(template, "minPurchaseAmount", minAmount);
		ReflectionTestUtils.setField(template, "validDaysAfterIssue", 30); // 30일 유효

		return template;
	}
}
