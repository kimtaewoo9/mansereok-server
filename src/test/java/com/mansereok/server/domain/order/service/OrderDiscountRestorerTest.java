package com.mansereok.server.domain.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderDiscountRestorerTest {

	@Mock
	private CouponService couponService;

	@Mock
	private DiscountCodeService discountCodeService;

	@InjectMocks
	private OrderDiscountRestorer restorer;

	private Order createOrder(Long id, String discountCode, Long couponId) {
		Order order = Order.create("merchant_" + id, 1L, 1L, 10000, 5000, discountCode, couponId,
			OrderStatus.PENDING, "테스트", "test@test.com");
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	@Test
	@DisplayName("쿠폰을 쓴 주문은 쿠폰만 복구하고 할인코드는 건드리지 않는다")
	void restore_couponOrder_restoresCouponOnly() {
		Order order = createOrder(1L, null, 100L);

		restorer.restore(order);

		verify(couponService).restoreCoupon(100L);
		verify(discountCodeService, never()).restoreDiscountUsage(any());
	}

	@Test
	@DisplayName("쿠폰과 할인코드가 둘 다 있으면 쿠폰만 복구한다")
	void restore_couponAndCode_prefersCoupon() {
		Order order = createOrder(2L, "SALE10", 100L);

		restorer.restore(order);

		verify(couponService).restoreCoupon(100L);
		verify(discountCodeService, never()).restoreDiscountUsage(any());
	}

	@Test
	@DisplayName("할인코드를 쓴 주문은 할인코드 사용 횟수만 복구한다")
	void restore_discountCodeOrder_restoresCodeOnly() {
		Order order = createOrder(3L, "SALE10", null);

		restorer.restore(order);

		verify(discountCodeService).restoreDiscountUsage("SALE10");
		verify(couponService, never()).restoreCoupon(any());
	}

	@Test
	@DisplayName("EVENT_FREE 코드는 복구하지 않는다")
	void restore_eventFreeCode_doesNothing() {
		Order order = createOrder(4L, "EVENT_FREE", null);

		restorer.restore(order);

		verifyNoInteractions(couponService, discountCodeService);
	}

	@Test
	@DisplayName("쿠폰도 할인코드도 없는 주문은 아무것도 복구하지 않는다")
	void restore_noDiscount_doesNothing() {
		Order order = createOrder(5L, null, null);

		restorer.restore(order);

		verifyNoInteractions(couponService, discountCodeService);
	}

	@Test
	@DisplayName("할인코드가 공백이면 복구하지 않는다")
	void restore_blankCode_doesNothing() {
		Order order = createOrder(6L, "   ", null);

		restorer.restore(order);

		verifyNoInteractions(couponService, discountCodeService);
	}
}
