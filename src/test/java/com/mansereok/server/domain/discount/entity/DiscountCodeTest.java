package com.mansereok.server.domain.discount.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DiscountCodeTest {

	@Test
	@DisplayName("정액 할인 테스트 코드")
	void applyDiscount_fixed_amount() {
		// given
		DiscountCode discountCode = new DiscountCode();
		ReflectionTestUtils.setField(discountCode, "amount", 100);
	}
}
