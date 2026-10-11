package com.mansereok.server.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceTest {

	private static final Long ORDER_ID = 10L;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderDiscountRestorer orderDiscountRestorer;

	@InjectMocks
	private OrderExpirationService orderExpirationService;

	private Order createOrder(OrderStatus status) {
		Order order = Order.create("merchant_" + ORDER_ID, 1L, 1L, 10000, 5000, null, 100L,
			status, "테스트", "test@test.com");
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
	}

	@Test
	@DisplayName("조건부 UPDATE 가 1을 돌려주면 주문을 다시 읽어 할인 복구를 호출하고 true 를 돌려준다")
	void expireIfStillPending_updated_restoresAndReturnsTrue() {
		given(orderRepository.updateStatusIf(ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED))
			.willReturn(1);
		Order order = createOrder(OrderStatus.EXPIRED);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

		boolean expired = orderExpirationService.expireIfStillPending(ORDER_ID);

		assertThat(expired).isTrue();
		verify(orderDiscountRestorer).restore(order);
	}

	@Test
	@DisplayName("조건부 UPDATE 가 0을 돌려주면(이미 PAID 등) 다시 읽지도 복구하지도 않고 false 를 돌려준다")
	void expireIfStillPending_notUpdated_skipsRestoreAndReturnsFalse() {
		given(orderRepository.updateStatusIf(ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED))
			.willReturn(0);

		boolean expired = orderExpirationService.expireIfStillPending(ORDER_ID);

		assertThat(expired).isFalse();
		verify(orderRepository, never()).findById(any());
		verify(orderDiscountRestorer, never()).restore(any());
	}

	@Test
	@DisplayName("복구가 예외를 던지면 그대로 전파해 해당 주문의 트랜잭션만 롤백되게 한다")
	void expireIfStillPending_restoreFails_propagates() {
		given(orderRepository.updateStatusIf(ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED))
			.willReturn(1);
		Order order = createOrder(OrderStatus.EXPIRED);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
		willThrow(new RuntimeException("DB 오류")).given(orderDiscountRestorer).restore(order);

		assertThatThrownBy(() -> orderExpirationService.expireIfStillPending(ORDER_ID))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("DB 오류");
	}

	@Test
	@DisplayName("UPDATE 는 됐는데 주문을 다시 읽지 못하면 IllegalStateException 이 난다")
	void expireIfStillPending_orderMissingAfterUpdate_throws() {
		given(orderRepository.updateStatusIf(ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED))
			.willReturn(1);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> orderExpirationService.expireIfStillPending(ORDER_ID))
			.isInstanceOf(IllegalStateException.class);
		verify(orderDiscountRestorer, never()).restore(any());
	}
}
