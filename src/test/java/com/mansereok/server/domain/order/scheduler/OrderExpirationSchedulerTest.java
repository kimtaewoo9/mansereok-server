package com.mansereok.server.domain.order.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderExpirationSchedulerTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private DiscountCodeService discountCodeService;

	@Mock
	private CouponService couponService;

	@InjectMocks
	private OrderExpirationScheduler scheduler;

	private Order createTestOrder(Long id, OrderStatus status, String discountCode, Long couponId) {
		Order order = Order.create(
			"merchant_" + id,
			1L,
			1L,
			10000,
			5000,
			discountCode,
			couponId,
			status,
			"테스트",
			"test@test.com"
		);
		ReflectionTestUtils.setField(order, "id", id);
		ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now().minusMinutes(40));
		return order;
	}

	@Test
	@DisplayName("만료 대상 주문이 없으면 아무것도 하지 않는다")
	void noStaleOrders() {
		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(Collections.emptyList());

		scheduler.expireStaleOrders();

		verify(orderRepository, never()).saveAll(any());
		verify(discountCodeService, never()).restoreDiscountUsage(any());
		verify(couponService, never()).restoreCoupon(any());
	}

	@Test
	@DisplayName("할인코드 사용 주문이 만료되면 할인코드가 복구된다")
	void expireOrderWithDiscountCode() {
		Order order = createTestOrder(1L, OrderStatus.PENDING, "쑨디", null);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order));

		scheduler.expireStaleOrders();

		// 주문 상태가 EXPIRED로 변경됐는지
		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);

		// 할인코드 복구 호출됐는지
		verify(discountCodeService).restoreDiscountUsage("쑨디");

		// 쿠폰은 복구 안 됐는지
		verify(couponService, never()).restoreCoupon(any());

		// saveAll 호출됐는지
		verify(orderRepository).saveAll(List.of(order));
	}

	@Test
	@DisplayName("쿠폰 사용 주문이 만료되면 쿠폰이 복구된다")
	void expireOrderWithCoupon() {
		Order order = createTestOrder(2L, OrderStatus.PENDING, null, 100L);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order));

		scheduler.expireStaleOrders();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);

		// 쿠폰 복구 호출됐는지
		verify(couponService).restoreCoupon(100L);

		// 할인코드는 복구 안 됐는지
		verify(discountCodeService, never()).restoreDiscountUsage(any());
	}

	@Test
	@DisplayName("할인코드도 쿠폰도 없는 주문은 상태만 만료된다")
	void expireOrderWithoutDiscount() {
		Order order = createTestOrder(3L, OrderStatus.PENDING, null, null);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order));

		scheduler.expireStaleOrders();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		verify(discountCodeService, never()).restoreDiscountUsage(any());
		verify(couponService, never()).restoreCoupon(any());
	}

	@Test
	@DisplayName("EVENT_FREE 코드는 복구하지 않는다")
	void expireOrderWithEventFreeCode() {
		Order order = createTestOrder(4L, OrderStatus.PENDING, "EVENT_FREE", null);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order));

		scheduler.expireStaleOrders();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		verify(discountCodeService, never()).restoreDiscountUsage(any());
	}

	@Test
	@DisplayName("여러 건의 만료 주문이 한번에 처리된다")
	void expireMultipleOrders() {
		Order order1 = createTestOrder(10L, OrderStatus.PENDING, "쑨디", null);
		Order order2 = createTestOrder(11L, OrderStatus.PENDING, null, 200L);
		Order order3 = createTestOrder(12L, OrderStatus.PENDING, "뿍이", null);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order1, order2, order3));

		scheduler.expireStaleOrders();

		assertThat(order1.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(order2.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(order3.getStatus()).isEqualTo(OrderStatus.EXPIRED);

		verify(discountCodeService).restoreDiscountUsage("쑨디");
		verify(discountCodeService).restoreDiscountUsage("뿍이");
		verify(couponService).restoreCoupon(200L);
	}

	@Test
	@DisplayName("한 건 실패해도 나머지는 정상 처리된다")
	void oneFailureDoesNotBlockOthers() {
		Order order1 = createTestOrder(20L, OrderStatus.PENDING, "에러코드", null);
		Order order2 = createTestOrder(21L, OrderStatus.PENDING, "정상코드", null);

		when(orderRepository.findAllByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any()))
			.thenReturn(List.of(order1, order2));

		// 첫 번째 할인코드 복구 시 예외 발생
		org.mockito.Mockito.doThrow(new RuntimeException("DB 오류"))
			.when(discountCodeService).restoreDiscountUsage("에러코드");

		scheduler.expireStaleOrders();

		// 두 번째 주문은 정상 처리
		assertThat(order2.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		verify(discountCodeService).restoreDiscountUsage("정상코드");
	}
}
