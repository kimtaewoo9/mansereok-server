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

	// ===== markCancelled =====

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

	@Test
	@DisplayName("CANCEL_REQUESTED 결제에 markCancelled 를 부르면 CANCELLED 가 된다 (환불 확정 단계)")
	void markCancelled_fromCancelRequested() {
		// given
		Payment payment = paymentWith(PaymentStatus.CANCEL_REQUESTED);

		// when
		payment.markCancelled();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
	}

	@ParameterizedTest(name = "{0} 결제에 markCancelled 를 부르면 OrderStateException 이 난다")
	@DisplayName("PAID 도 CANCEL_REQUESTED 도 아닌 결제는 취소할 수 없다")
	@EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "READY",
		"VIRTUAL_ACCOUNT_ISSUED"})
	void markCancelled_fromOtherStatus_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::markCancelled)
			.isInstanceOf(OrderStateException.class)
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}

	// ===== markCancelRequested =====

	@Test
	@DisplayName("PAID 결제에 markCancelRequested 를 부르면 CANCEL_REQUESTED 가 된다")
	void markCancelRequested_fromPaid() {
		// given
		Payment payment = paymentWith(PaymentStatus.PAID);

		// when
		payment.markCancelRequested();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
	}

	@ParameterizedTest(name = "{0} 결제에 markCancelRequested 를 부르면 OrderStateException 이 난다")
	@DisplayName("PAID 가 아닌 결제는 취소 요청 상태로 갈 수 없다 (CANCEL_REQUESTED 재진입 포함)")
	@EnumSource(value = PaymentStatus.class, names = {"CANCEL_REQUESTED", "CANCELLED", "FAILED",
		"READY", "VIRTUAL_ACCOUNT_ISSUED"})
	void markCancelRequested_fromNonPaid_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::markCancelRequested)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}

	// ===== revertCancelRequest =====

	@Test
	@DisplayName("CANCEL_REQUESTED 결제에 revertCancelRequest 를 부르면 PAID 로 돌아간다")
	void revertCancelRequest_fromCancelRequested() {
		// given
		Payment payment = paymentWith(PaymentStatus.CANCEL_REQUESTED);

		// when
		payment.revertCancelRequest();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
	}

	@ParameterizedTest(name = "{0} 결제에 revertCancelRequest 를 부르면 OrderStateException 이 난다")
	@DisplayName("CANCEL_REQUESTED 가 아닌 결제는 취소 요청을 되돌릴 수 없다")
	@EnumSource(value = PaymentStatus.class, names = {"PAID", "CANCELLED", "FAILED", "READY",
		"VIRTUAL_ACCOUNT_ISSUED"})
	void revertCancelRequest_fromNonCancelRequested_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::revertCancelRequest)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}

	@Test
	@DisplayName("취소 요청 → 되돌림 → 다시 취소 요청 → 확정 순서의 전이가 모두 허용된다")
	void cancelRequest_revert_request_cancel_roundTrip() {
		// given
		Payment payment = paymentWith(PaymentStatus.PAID);

		// when
		payment.markCancelRequested();
		payment.revertCancelRequest();
		payment.markCancelRequested();
		payment.markCancelled();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
	}
}
