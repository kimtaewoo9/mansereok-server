package com.mansereok.server.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

	private Payment paymentWith(PaymentStatus status) {
		return Payment.create("pay_test_001", "order_test_001", 10000L, status, 10L, 1L, 2L);
	}

	@Test
	@DisplayName("PAID 결제에 markCancelled 를 부르면 CANCELLED 가 되고 나머지 필드는 유지된다")
	void markCancelled_fromPaid() {
		// given
		Payment payment = paymentWith(PaymentStatus.PAID);

		// when
		payment.markCancelled();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(payment.getImpUid()).isEqualTo("pay_test_001");
		assertThat(payment.getMerchantUid()).isEqualTo("order_test_001");
		assertThat(payment.getAmount()).isEqualTo(10000L);
		assertThat(payment.getOrderId()).isEqualTo(10L);
	}

	@ParameterizedTest(name = "{0} 결제에 markCancelled 를 부르면 OrderStateException 이 난다")
	@DisplayName("PAID 가 아닌 결제는 취소할 수 없다")
	@EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "READY",
		"VIRTUAL_ACCOUNT_ISSUED"})
	void markCancelled_fromNonPaid_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::markCancelled)
			.isInstanceOf(OrderStateException.class)
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}
}
