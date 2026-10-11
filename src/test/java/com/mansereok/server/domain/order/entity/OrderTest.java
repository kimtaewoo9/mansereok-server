package com.mansereok.server.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

	private Order pendingOrder() {
		return Order.create("order_test_001", 1L, 2L, 10000, 9000, "SALE10", null,
			OrderStatus.PENDING, "김태우", "taewoo@example.com");
	}

	@Test
	@DisplayName("markPaid 는 상태를 PAID 로 바꾸고 paymentId 와 paidAt 을 채운다")
	void markPaid_setsStatusPaymentIdAndPaidAt() {
		// given
		Order order = pendingOrder();
		LocalDateTime paidAt = LocalDateTime.of(2026, 9, 21, 12, 30);

		// when
		order.markPaid("pay_test_001", paidAt);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(paidAt);

		// 다른 필드는 건드리지 않는다
		assertThat(order.getPaymentPkId()).isNull();
		assertThat(order.getAmount()).isEqualTo(9000);
		assertThat(order.getAppliedDiscountCode()).isEqualTo("SALE10");
	}

	@Test
	@DisplayName("[가드 도입 전 임시 특성화] markPaid 는 이미 PAID 인 주문에도 값을 덮어쓴다 (전이 가드 PR 에서 예외로 뒤집는다)")
	void markPaid_overwritesWithoutGuard() {
		// given
		Order order = pendingOrder();
		order.markPaid("pay_first", LocalDateTime.of(2026, 9, 21, 12, 0));
		LocalDateTime later = LocalDateTime.of(2026, 9, 21, 13, 0);

		// when
		order.markPaid("pay_second", later);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_second");
		assertThat(order.getPaidAt()).isEqualTo(later);
	}

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
