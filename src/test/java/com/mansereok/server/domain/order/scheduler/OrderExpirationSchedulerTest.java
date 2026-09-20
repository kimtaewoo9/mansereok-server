package com.mansereok.server.domain.order.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderExpirationSchedulerTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"),
		ZoneOffset.UTC);
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 21, 0, 0);

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderExpirationService orderExpirationService;

	private OrderExpirationScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new OrderExpirationScheduler(orderRepository, orderExpirationService,
			FIXED_CLOCK);
	}

	private Order createStaleOrder(Long id) {
		Order order = Order.create("merchant_" + id, 1L, 1L, 10000, 5000, null, null,
			OrderStatus.PENDING, "테스트", "test@test.com");
		ReflectionTestUtils.setField(order, "id", id);
		ReflectionTestUtils.setField(order, "createdAt", FIXED_NOW.minusMinutes(40));
		return order;
	}

	@Test
	@DisplayName("만료 기준 시각은 Clock 기준 현재 시각의 정확히 30분 전이다")
	void cutoffIsExactlyThirtyMinutesBeforeNow() {
		given(orderRepository.findAllByStatusAndCreatedAtBefore(any(), any()))
			.willReturn(Collections.emptyList());

		scheduler.expireStaleOrders();

		verify(orderRepository).findAllByStatusAndCreatedAtBefore(OrderStatus.PENDING,
			LocalDateTime.of(2026, 9, 20, 23, 30));
	}

	@Test
	@DisplayName("만료 대상 주문이 없으면 아무것도 하지 않는다")
	void noStaleOrders() {
		given(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.willReturn(Collections.emptyList());

		scheduler.expireStaleOrders();

		verifyNoInteractions(orderExpirationService);
		verify(orderRepository, never()).saveAll(any());
	}

	@Test
	@DisplayName("만료 대상 주문마다 건별 만료 서비스를 호출하고 saveAll 은 쓰지 않는다")
	void expireMultipleOrders() {
		Order order1 = createStaleOrder(10L);
		Order order2 = createStaleOrder(11L);
		Order order3 = createStaleOrder(12L);
		given(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.willReturn(List.of(order1, order2, order3));
		given(orderExpirationService.expireIfStillPending(any())).willReturn(true);

		scheduler.expireStaleOrders();

		verify(orderExpirationService).expireIfStillPending(10L);
		verify(orderExpirationService).expireIfStillPending(11L);
		verify(orderExpirationService).expireIfStillPending(12L);
		verify(orderRepository, never()).saveAll(any());
	}

	@Test
	@DisplayName("조회와 갱신 사이에 상태가 바뀐 주문(false)은 건너뛰고 나머지는 계속 처리한다")
	void skippedOrderDoesNotStopOthers() {
		Order order1 = createStaleOrder(10L);
		Order order2 = createStaleOrder(11L);
		given(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.willReturn(List.of(order1, order2));
		given(orderExpirationService.expireIfStillPending(10L)).willReturn(false);
		given(orderExpirationService.expireIfStillPending(11L)).willReturn(true);

		scheduler.expireStaleOrders();

		verify(orderExpirationService).expireIfStillPending(10L);
		verify(orderExpirationService).expireIfStillPending(11L);
	}

	@Test
	@DisplayName("한 건 실패해도 예외가 밖으로 나가지 않고 나머지는 정상 처리된다")
	void oneFailureDoesNotBlockOthers() {
		Order order1 = createStaleOrder(20L);
		Order order2 = createStaleOrder(21L);
		given(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.willReturn(List.of(order1, order2));
		willThrow(new RuntimeException("DB 오류"))
			.given(orderExpirationService).expireIfStillPending(20L);
		given(orderExpirationService.expireIfStillPending(21L)).willReturn(true);

		assertThatCode(() -> scheduler.expireStaleOrders()).doesNotThrowAnyException();

		verify(orderExpirationService).expireIfStillPending(20L);
		verify(orderExpirationService).expireIfStillPending(21L);
	}
}
