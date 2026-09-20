package com.mansereok.server.domain.order.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderResponseTest {

	@Test
	@DisplayName("from 은 Order 의 모든 필드를 같은 이름으로 옮긴다")
	void from_copiesAllFields() {
		// given
		LocalDateTime createdAt = LocalDateTime.of(2026, 9, 21, 10, 0);
		LocalDateTime paidAt = LocalDateTime.of(2026, 9, 21, 10, 5);
		Order order = Order.create("order_test_001", 7L, 2L, 19900, 9950, "VIP50", 3L,
			OrderStatus.PENDING, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", 12L);
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		order.linkPayment(9L);
		order.markPaid("pay_test_001", paidAt);

		// when
		OrderResponse response = OrderResponse.from(order);

		// then
		assertThat(response.id()).isEqualTo(12L);
		assertThat(response.merchantUid()).isEqualTo("order_test_001");
		assertThat(response.paymentId()).isEqualTo("pay_test_001");
		assertThat(response.paymentPkId()).isEqualTo(9L);
		assertThat(response.userId()).isEqualTo(7L);
		assertThat(response.subCategoryId()).isEqualTo(2L);
		assertThat(response.buyerName()).isEqualTo("김태우");
		assertThat(response.buyerEmail()).isEqualTo("taewoo@example.com");
		assertThat(response.amount()).isEqualTo(9950);
		assertThat(response.status()).isEqualTo(OrderStatus.PAID);
		assertThat(response.createdAt()).isEqualTo(createdAt);
		assertThat(response.paidAt()).isEqualTo(paidAt);
		assertThat(response.originalAmount()).isEqualTo(19900);
		assertThat(response.appliedDiscountCode()).isEqualTo("VIP50");
		assertThat(response.couponId()).isEqualTo(3L);
	}

	@Test
	@DisplayName("record 컴포넌트 이름은 기존 Order JSON 키 집합과 같다 (프론트 계약 유지)")
	void recordComponents_matchOrderJsonKeys() {
		// when
		String[] names = Arrays.stream(OrderResponse.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toArray(String[]::new);

		// then
		assertThat(names).containsExactlyInAnyOrder(
			"id", "merchantUid", "paymentId", "paymentPkId", "userId", "subCategoryId",
			"buyerName", "buyerEmail", "amount", "status", "createdAt", "paidAt",
			"originalAmount", "appliedDiscountCode", "couponId");
	}

	@Test
	@DisplayName("from 은 비어 있는 선택 필드를 null 로 옮긴다")
	void from_keepsNullOptionalFields() {
		// given
		Order order = Order.create("order_test_002", null, 2L, 10000, 10000, null, null,
			OrderStatus.PENDING, "김태우", "taewoo@example.com");

		// when
		OrderResponse response = OrderResponse.from(order);

		// then
		assertThat(response.id()).isNull();
		assertThat(response.userId()).isNull();
		assertThat(response.paymentId()).isNull();
		assertThat(response.paymentPkId()).isNull();
		assertThat(response.paidAt()).isNull();
		assertThat(response.appliedDiscountCode()).isNull();
		assertThat(response.couponId()).isNull();
		assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
	}
}
