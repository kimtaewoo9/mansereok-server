package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.global.exception.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentVerifierTest {

	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final int PRICE = 10000;

	private final PaymentVerifier paymentVerifier = new PaymentVerifier(
		Jackson2ObjectMapperBuilder.json().build());

	private static Order order() {
		Order order = Order.create(MERCHANT_UID, 1L, 1L, PRICE, PRICE, null, null,
			OrderStatus.PENDING, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", 10L);
		return order;
	}

	private static PortOnePaymentResponse response(String status, long total, String customData) {
		return response(PAYMENT_ID, status, total, customData);
	}

	private static PortOnePaymentResponse response(String paymentId, String status, long total,
		String customData) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus(status);
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		response.setAmount(amount);
		response.setCustomData(customData);
		return response;
	}

	@Test
	@DisplayName("customData 의 merchantUid 를 꺼낸다")
	void merchantUidFromCustomData_extractsMerchantUid() {
		String merchantUid = paymentVerifier.merchantUidFromCustomData(
			response("PAID", PRICE, "{\"merchantUid\":\"" + MERCHANT_UID + "\"}"));

		assertThat(merchantUid).isEqualTo(MERCHANT_UID);
	}

	@Test
	@DisplayName("customData 의 merchantUid 가 주문과 다르면 '결제 정보의 주문 번호가 일치하지 않습니다.' PaymentException 이 난다")
	void assertCustomDataMatchesOrder_mismatch_throws() {
		assertThatThrownBy(() -> paymentVerifier.assertCustomDataMatchesOrder(order(), PAYMENT_ID,
			response("PAID", PRICE, "{\"merchantUid\":\"order_other\"}")))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보의 주문 번호가 일치하지 않습니다.");
	}

	@ParameterizedTest(name = "customData={0}")
	@NullSource
	@ValueSource(strings = {"", "  "})
	@DisplayName("customData 가 비어 있으면 대조를 건너뛰지 않고 '결제 정보에 주문 번호가 없습니다.' PaymentException 이 난다")
	void assertCustomDataMatchesOrder_blank_throws(String customData) {
		assertThatThrownBy(() -> paymentVerifier.assertCustomDataMatchesOrder(order(), PAYMENT_ID,
			response("PAID", PRICE, customData)))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보에 주문 번호가 없습니다.");
	}

	@Test
	@DisplayName("customData 의 merchantUid 가 주문과 같으면 통과한다")
	void assertCustomDataMatchesOrder_same_passes() {
		assertThatCode(() -> paymentVerifier.assertCustomDataMatchesOrder(order(), PAYMENT_ID,
			response("PAID", PRICE, "{\"merchantUid\":\"" + MERCHANT_UID + "\"}")))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "[{index}] 요청 {0}, 포트원 응답 {1}")
	@DisplayName("포트원 응답의 결제 ID 가 요청한 결제 ID 와 한 글자라도 다르거나 없으면 '결제 정보의 결제 ID가 일치하지 않습니다.' PaymentException 이 난다")
	@CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
		# 요청한 결제 ID | 포트원 응답의 결제 ID
		pay_A#1        | pay_A
		pay_A?x=1      | pay_A
		pay_a          | pay_A
		pay_A          | NULL
		""")
	void assertPaymentIdMatches_different_throws(String requestedPaymentId, String responsePaymentId) {
		// given
		PortOnePaymentResponse response = response(responsePaymentId, "PAID", PRICE, null);

		// when & then
		assertThatThrownBy(() -> paymentVerifier.assertPaymentIdMatches(requestedPaymentId, response))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보의 결제 ID가 일치하지 않습니다.");
	}

	@Test
	@DisplayName("포트원 응답의 결제 ID 가 요청한 결제 ID 와 같으면 통과한다")
	void assertPaymentIdMatches_same_passes() {
		assertThatCode(() -> paymentVerifier.assertPaymentIdMatches(PAYMENT_ID,
			response(PAYMENT_ID, "PAID", PRICE, null))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("금액이 같으면 true, 다르면 false 를 돌려준다")
	void amountMatches_comparesTotalWithOrderAmount() {
		assertThat(paymentVerifier.amountMatches(order(), response("PAID", PRICE, null))).isTrue();
		assertThat(paymentVerifier.amountMatches(order(), response("PAID", PRICE - 1, null)))
			.isFalse();
	}

	@Test
	@DisplayName("포트원 상태를 PaymentStatus 로 매핑하고 모르는 상태는 빈 Optional 을 돌려준다")
	void resolveStatus_mapsKnownAndUnknownStatuses() {
		assertThat(paymentVerifier.resolveStatus(order(), PAYMENT_ID, response("PAID", PRICE, null)))
			.contains(PaymentStatus.PAID);
		assertThat(paymentVerifier.resolveStatus(order(), PAYMENT_ID,
			response("PAY_PENDING", PRICE, null))).contains(PaymentStatus.READY);
		assertThat(paymentVerifier.resolveStatus(order(), PAYMENT_ID,
			response("SOMETHING_NEW", PRICE, null))).isEmpty();
	}
}
