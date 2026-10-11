package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.support.fixture.TestOrders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 결제 금액 대조의 경계값을 한 표로 검증한다. 같은 값, 1원 모자람, 1원 넘침, 0원 주문을 모두 넣는다.
 */
class PaymentVerifierAmountTest {

	// 협력 객체가 ObjectMapper 하나뿐이라 mock 대신 진짜를 쓴다.
	private final PaymentVerifier paymentVerifier = new PaymentVerifier(
		Jackson2ObjectMapperBuilder.json().build());

	@ParameterizedTest(name = "[{index}] 주문 {0}원, 포트원 {1}원 → 일치 {2}")
	@DisplayName("포트원 결제 금액이 주문 금액과 1원이라도 다르면 불일치로 본다")
	@CsvSource(textBlock = """
		# 주문 금액, 포트원 결제 금액, 일치 여부
		10000, 10000, true
		10000,  9999, false
		10000, 10001, false
		    0,     0, true
		    0,     1, false
		""")
	void amountMatches(int orderAmount, long paidAmount, boolean expected) {
		// given
		Order order = orderOf(orderAmount);
		PortOnePaymentResponse response = portOneResponseOf(paidAmount);

		// when
		boolean matches = paymentVerifier.amountMatches(order, response);

		// then
		assertThat(matches).isEqualTo(expected);
	}

	@Test
	@DisplayName("포트원 응답에 결제 금액이 없으면 불일치로 본다")
	void missingPaidAmount_doesNotMatch() {
		// given
		PortOnePaymentResponse response = portOneResponseOf(10000L);
		response.getAmount().setTotal(null);

		// when
		boolean matches = paymentVerifier.amountMatches(orderOf(10000), response);

		// then
		assertThat(matches).isFalse();
	}

	private static Order orderOf(int amount) {
		return TestOrders.order().merchantUid("order_amount_test").price(amount).pending();
	}

	private static PortOnePaymentResponse portOneResponseOf(long total) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setStatus("PAID");
		response.setAmount(amount);
		return response;
	}
}
