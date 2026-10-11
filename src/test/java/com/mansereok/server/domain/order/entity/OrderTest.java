package com.mansereok.server.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

	private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 9, 21, 12, 30);

	private Order orderWith(OrderStatus status) {
		return Order.create("order_test_001", 1L, 2L, 10000, 9000, "SALE10", null,
			status, "김태우", "taewoo@example.com");
	}

	private Order pendingOrder() {
		return orderWith(OrderStatus.PENDING);
	}

	// ===== markPaid =====

	@Test
	@DisplayName("PENDING 주문에 markPaid 를 부르면 PAID 가 되고 paymentId 와 paidAt 이 채워진다")
	void markPaid_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markPaid("pay_test_001", PAID_AT);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);

		// 다른 필드는 건드리지 않는다
		assertThat(order.getPaymentPkId()).isNull();
		assertThat(order.getAmount()).isEqualTo(9000);
		assertThat(order.getAppliedDiscountCode()).isEqualTo("SALE10");
	}

	@Test
	@DisplayName("이미 PAID 인 주문에 markPaid 를 부르면 OrderStateException 이 나고 값이 바뀌지 않는다")
	void markPaid_onPaidOrder_throws() {
		// given
		Order order = pendingOrder();
		LocalDateTime first = LocalDateTime.of(2026, 9, 21, 12, 0);
		order.markPaid("pay_first", first);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_second", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining("PAID 에서 PAID 로");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_first");
		assertThat(order.getPaidAt()).isEqualTo(first);
	}

	@Test
	@DisplayName("EXPIRED 주문에 markPaid 를 부르면 만료 직후 결제 경합으로 보고 PAID 를 허용한다")
	void markPaid_fromExpired_allowed() {
		// given
		Order order = orderWith(OrderStatus.EXPIRED);

		// when
		order.markPaid("pay_late", PAID_AT);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_late");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	@DisplayName("CANCELLED 주문에 markPaid 를 부르면 OrderStateException 이 난다")
	void markPaid_fromCancelled_throws() {
		// given
		Order order = orderWith(OrderStatus.CANCELLED);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_again", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED")
			.hasMessageContaining("PAID");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isNull();
		assertThat(order.getPaidAt()).isNull();
	}

	@Test
	@DisplayName("FAILED 주문에 markPaid 를 부르면 OrderStateException 이 난다")
	void markPaid_fromFailed_throws() {
		// given
		Order order = orderWith(OrderStatus.FAILED);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_again", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("FAILED")
			.hasMessageContaining("PAID");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
	}

	// ===== markCancelled =====

	@Test
	@DisplayName("PAID 주문에 markCancelled 를 부르면 CANCELLED 가 되고 결제 정보는 남는다")
	void markCancelled_fromPaid() {
		// given
		Order order = pendingOrder();
		order.markPaid("pay_test_001", PAID_AT);

		// when
		order.markCancelled();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	@DisplayName("PENDING 주문에 markCancelled 를 부르면 OrderStateException 이 난다")
	void markCancelled_fromPending_throws() {
		// given
		Order order = pendingOrder();

		// when & then
		assertThatThrownBy(order::markCancelled)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("PENDING")
			.hasMessageContaining("CANCELLED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
	}

	// ===== markExpired =====

	@Test
	@DisplayName("PENDING 주문에 markExpired 를 부르면 EXPIRED 가 된다")
	void markExpired_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markExpired();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(order.getPaymentId()).isNull();
		assertThat(order.getPaidAt()).isNull();
	}

	@Test
	@DisplayName("PAID 주문에 markExpired 를 부르면 OrderStateException 이 나고 PAID 가 유지된다")
	void markExpired_fromPaid_throws() {
		// given
		Order order = pendingOrder();
		order.markPaid("pay_test_001", PAID_AT);

		// when & then
		assertThatThrownBy(order::markExpired)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("PAID")
			.hasMessageContaining("EXPIRED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	// ===== markFailed =====

	@Test
	@DisplayName("PENDING 주문에 markFailed 를 부르면 FAILED 가 된다")
	void markFailed_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markFailed();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
	}

	@Test
	@DisplayName("CANCELLED 주문에 markFailed 를 부르면 OrderStateException 이 난다")
	void markFailed_fromCancelled_throws() {
		// given
		Order order = orderWith(OrderStatus.CANCELLED);

		// when & then
		assertThatThrownBy(order::markFailed)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED")
			.hasMessageContaining("FAILED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	// ===== linkPayment =====

	@Test
	@DisplayName("linkPayment 는 paymentPkId 만 채우고 상태와 paymentId 는 바꾸지 않는다")
	void linkPayment_setsOnlyPaymentPkId() {
		// given
		Order order = pendingOrder();

		// when
		order.linkPayment(100L);

		// then
		assertThat(order.getPaymentPkId()).isEqualTo(100L);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentId()).isNull();
		assertThat(order.getPaidAt()).isNull();
	}
}
