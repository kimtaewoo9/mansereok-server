package com.mansereok.server.domain.payment.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRequestValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	private static Set<String> violatedFields(Set<? extends ConstraintViolation<?>> violations) {
		return violations.stream()
			.map(v -> v.getPropertyPath().toString())
			.collect(java.util.stream.Collectors.toSet());
	}

	// ===== OrderCreateRequest =====

	@Test
	@DisplayName("OrderCreateRequest: subCategoryId 가 null 이면 위반이 난다")
	void orderCreateRequest_nullSubCategoryId_violates() {
		// given
		OrderCreateRequest request = new OrderCreateRequest();
		request.setDiscountCode("ABC");

		// when
		Set<ConstraintViolation<OrderCreateRequest>> violations = validator.validate(request);

		// then
		assertThat(violatedFields(violations)).containsExactly("subCategoryId");
	}

	@Test
	@DisplayName("OrderCreateRequest: subCategoryId 만 있어도 위반이 없다 (discountCode, couponId 는 선택)")
	void orderCreateRequest_onlySubCategoryId_valid() {
		// given
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(1L);

		// when
		Set<ConstraintViolation<OrderCreateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).isEmpty();
	}

	// ===== PaymentCompleteRequest =====

	@Test
	@DisplayName("PaymentCompleteRequest: paymentId 와 merchantUid 가 null 이면 둘 다 위반이 난다")
	void paymentCompleteRequest_nullFields_violate() {
		// given
		PaymentCompleteRequest request = new PaymentCompleteRequest();

		// when
		Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

		// then
		assertThat(violatedFields(violations)).containsExactlyInAnyOrder("paymentId", "merchantUid");
	}

	@Test
	@DisplayName("PaymentCompleteRequest: 공백 문자열도 위반이 난다")
	void paymentCompleteRequest_blankFields_violate() {
		// given
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId("   ");
		request.setMerchantUid("");

		// when
		Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

		// then
		assertThat(violatedFields(violations)).containsExactlyInAnyOrder("paymentId", "merchantUid");
	}

	@Test
	@DisplayName("PaymentCompleteRequest: 두 값이 모두 있으면 위반이 없다")
	void paymentCompleteRequest_valid() {
		// given
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId("pay_test_001");
		request.setMerchantUid("order_test_001");

		// when
		Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).isEmpty();
	}

	// ===== PaymentCancelRequest =====

	@Test
	@DisplayName("PaymentCancelRequest: paymentId 와 reason 이 null 이면 둘 다 위반이 난다")
	void paymentCancelRequest_nullFields_violate() {
		// given
		PaymentCancelRequest request = new PaymentCancelRequest();

		// when
		Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

		// then
		assertThat(violatedFields(violations)).containsExactlyInAnyOrder("paymentId", "reason");
	}

	@Test
	@DisplayName("PaymentCancelRequest: reason 이 공백이면 위반이 난다")
	void paymentCancelRequest_blankReason_violates() {
		// given
		PaymentCancelRequest request = new PaymentCancelRequest();
		ReflectionTestUtils.setField(request, "paymentId", "pay_test_001");
		ReflectionTestUtils.setField(request, "reason", " ");

		// when
		Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

		// then
		assertThat(violatedFields(violations)).containsExactly("reason");
	}

	@Test
	@DisplayName("PaymentCancelRequest: 두 값이 모두 있으면 위반이 없다")
	void paymentCancelRequest_valid() {
		// given
		PaymentCancelRequest request = new PaymentCancelRequest();
		ReflectionTestUtils.setField(request, "paymentId", "pay_test_001");
		ReflectionTestUtils.setField(request, "reason", "단순 변심");

		// when
		Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

		// then
		assertThat(violations).isEmpty();
	}
}
