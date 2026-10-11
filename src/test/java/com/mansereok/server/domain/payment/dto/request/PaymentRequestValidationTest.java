package com.mansereok.server.domain.payment.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

	// ===== 결제 ID 형식 (PaymentCompleteRequest·PaymentCancelRequest 공통) =====

	@Nested
	@DisplayName("결제 ID 형식")
	class PaymentIdFormatRule {

		private static final String PAYMENT_ID_FORMAT_MESSAGE = "결제 ID 형식이 올바르지 않습니다.";

		/** '#'·'?'·'/' 처럼 조회 주소를 바꾸는 문자, 공백, 영문 밖 문자, 끝 줄바꿈, 101자. */
		static Stream<Arguments> malformedPaymentIds() {
			return Stream.of(
				Arguments.of("# 뒤가 잘리는 변형 ID", "pay_A#1"),
				Arguments.of("? 뒤가 쿼리가 되는 변형 ID", "pay_A?x=1"),
				Arguments.of("경로를 바꾸는 / 가 든 ID", "a/../b"),
				Arguments.of("공백이 든 ID", "pay A"),
				Arguments.of("영문 밖 문자가 든 ID", "결제_1"),
				Arguments.of("끝에 줄바꿈이 붙은 ID", "pay_A\n"),
				Arguments.of("101자", "p".repeat(101))
			);
		}

		/** 필수 검사와 형식 검사가 함께 걸릴 수 있는 입력과, 그때 나가야 하는 메시지 하나. */
		static Stream<Arguments> missingPaymentIds() {
			return Stream.of(
				Arguments.of("null", null, "결제 ID는 필수입니다."),
				Arguments.of("빈 문자열", "", PAYMENT_ID_FORMAT_MESSAGE),
				Arguments.of("공백", "   ", PAYMENT_ID_FORMAT_MESSAGE)
			);
		}

		/** 포트원 결제 ID(프론트 생성), UUID 를 붙인 ID, 무료 주문 결제 ID(free_ + 주문 번호), 1자·100자. */
		static Stream<Arguments> wellFormedPaymentIds() {
			return Stream.of(
				Arguments.of("영문·숫자·밑줄", "pay_test_001"),
				Arguments.of("하이픈과 UUID", "payment-3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"),
				Arguments.of("무료 주문 결제 ID", "free_free_1726000000000_ab12cd34"),
				Arguments.of("1자", "p"),
				Arguments.of("100자", "p".repeat(100))
			);
		}

		@ParameterizedTest(name = "[{index}] {0}: {1}")
		@MethodSource("malformedPaymentIds")
		@DisplayName("결제 완료 요청의 결제 ID 가 형식 밖이면 '결제 ID 형식이 올바르지 않습니다.' 위반이 난다")
		void completeRequest_malformedPaymentId_violates(String caseName, String paymentId) {
			// given
			PaymentCompleteRequest request = new PaymentCompleteRequest();
			request.setPaymentId(paymentId);
			request.setMerchantUid("order_test_001");

			// when
			Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

			// then
			assertThat(violations)
				.extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
				.containsExactly(tuple("paymentId", PAYMENT_ID_FORMAT_MESSAGE));
		}

		@ParameterizedTest(name = "[{index}] {0}: {1}")
		@MethodSource("wellFormedPaymentIds")
		@DisplayName("결제 완료 요청의 결제 ID 가 형식 안이면 위반이 없다")
		void completeRequest_wellFormedPaymentId_valid(String caseName, String paymentId) {
			// given
			PaymentCompleteRequest request = new PaymentCompleteRequest();
			request.setPaymentId(paymentId);
			request.setMerchantUid("order_test_001");

			// when
			Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

			// then
			assertThat(violations).isEmpty();
		}

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("missingPaymentIds")
		@DisplayName("결제 완료 요청의 결제 ID 가 null·빈 문자열·공백이면 위반이 한 개만 나서 응답 메시지가 하나로 정해진다")
		void completeRequest_missingPaymentId_violatesOnce(String caseName, String paymentId,
			String expectedMessage) {
			// given
			PaymentCompleteRequest request = new PaymentCompleteRequest();
			request.setPaymentId(paymentId);
			request.setMerchantUid("order_test_001");

			// when
			Set<ConstraintViolation<PaymentCompleteRequest>> violations = validator.validate(request);

			// then
			assertThat(violations)
				.extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
				.containsExactly(tuple("paymentId", expectedMessage));
		}

		@ParameterizedTest(name = "[{index}] {0}: {1}")
		@MethodSource("malformedPaymentIds")
		@DisplayName("환불 요청의 결제 ID 가 형식 밖이면 '결제 ID 형식이 올바르지 않습니다.' 위반이 난다")
		void cancelRequest_malformedPaymentId_violates(String caseName, String paymentId) {
			// given
			PaymentCancelRequest request = new PaymentCancelRequest();
			ReflectionTestUtils.setField(request, "paymentId", paymentId);
			ReflectionTestUtils.setField(request, "reason", "단순 변심");

			// when
			Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

			// then
			assertThat(violations)
				.extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
				.containsExactly(tuple("paymentId", PAYMENT_ID_FORMAT_MESSAGE));
		}

		@ParameterizedTest(name = "[{index}] {0}: {1}")
		@MethodSource("wellFormedPaymentIds")
		@DisplayName("환불 요청의 결제 ID 가 형식 안이면 위반이 없다")
		void cancelRequest_wellFormedPaymentId_valid(String caseName, String paymentId) {
			// given
			PaymentCancelRequest request = new PaymentCancelRequest();
			ReflectionTestUtils.setField(request, "paymentId", paymentId);
			ReflectionTestUtils.setField(request, "reason", "단순 변심");

			// when
			Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

			// then
			assertThat(violations).isEmpty();
		}

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("missingPaymentIds")
		@DisplayName("환불 요청의 결제 ID 가 null·빈 문자열·공백이면 위반이 한 개만 나서 응답 메시지가 하나로 정해진다")
		void cancelRequest_missingPaymentId_violatesOnce(String caseName, String paymentId,
			String expectedMessage) {
			// given
			PaymentCancelRequest request = new PaymentCancelRequest();
			ReflectionTestUtils.setField(request, "paymentId", paymentId);
			ReflectionTestUtils.setField(request, "reason", "단순 변심");

			// when
			Set<ConstraintViolation<PaymentCancelRequest>> violations = validator.validate(request);

			// then
			assertThat(violations)
				.extracting(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage)
				.containsExactly(tuple("paymentId", expectedMessage));
		}
	}
}
