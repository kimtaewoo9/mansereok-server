package com.mansereok.server.domain.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.event.PaymentAnomalyEvent;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderDiscountRestorerTest {

	/** 할인을 쥐고 있는 주문 상태. 이 상태의 다른 주문이 같은 쿠폰을 쓰면 그 쿠폰은 되돌리지 않는다. */
	private static final Set<OrderStatus> HOLDING_STATUSES = EnumSet.of(OrderStatus.PENDING,
		OrderStatus.VIRTUAL_ACCOUNT_ISSUED, OrderStatus.PAID);

	@Mock
	private CouponService couponService;

	@Mock
	private DiscountCodeService discountCodeService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private OrderDiscountRestorer restorer;

	private Order createOrder(Long id, String discountCode, Long couponId) {
		Order order = Order.create("merchant_" + id, 1L, 1L, 10000, 5000, discountCode, couponId,
			OrderStatus.PENDING, "테스트", "test@test.com");
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	/** 만료됐다가 결제 ID pay_{id} 로 확정된 주문. reapply 는 PaidOrderFinalizer 가 markPaid 뒤에 부른다. */
	private Order paidAfterExpiry(Long id, String discountCode, Long couponId) {
		Order order = createOrder(id, discountCode, couponId);
		ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
		ReflectionTestUtils.setField(order, "paymentId", "pay_" + id);
		return order;
	}

	/** 이 주문 말고 쿠폰을 쥔 다른 주문의 id 를 공유 잠금 읽기로 찾으면 otherHolderIds 가 나온다. */
	private void givenOtherOrdersHoldingCoupon(Long couponId, Long orderId, Long... otherHolderIds) {
		given(orderRepository.findIdsByCouponIdAndStatusInAndIdNotForShare(couponId, HOLDING_STATUSES, orderId))
			.willReturn(List.of(otherHolderIds));
	}

	@Nested
	@DisplayName("할인을 되돌릴 때(restore)")
	class Restore {

		@Test
		@DisplayName("쿠폰을 쓴 주문은 다른 주문이 그 쿠폰을 쥐고 있지 않으면 쿠폰만 복구하고 할인코드는 건드리지 않는다")
		void restore_couponOrder_restoresCouponOnly() {
			Order order = createOrder(1L, null, 100L);
			givenOtherOrdersHoldingCoupon(100L, 1L);

			restorer.restore(order);

			verify(couponService).restoreCoupon(100L);
			verify(discountCodeService, never()).restoreDiscountUsage(any());
		}

		@Test
		@DisplayName("쿠폰과 할인코드가 둘 다 있으면 쿠폰만 복구한다")
		void restore_couponAndCode_prefersCoupon() {
			Order order = createOrder(2L, "SALE10", 100L);
			givenOtherOrdersHoldingCoupon(100L, 2L);

			restorer.restore(order);

			verify(couponService).restoreCoupon(100L);
			verify(discountCodeService, never()).restoreDiscountUsage(any());
		}

		@Test
		@DisplayName("결제 대기·가상계좌 발급·결제 완료인 다른 주문이 같은 쿠폰을 쓰고 있으면 쿠폰을 되돌리지 않는다")
		void restore_couponHeldByAnotherActiveOrder_isLeftUsed() {
			// given: 만료 뒤 늦게 결제된 주문 1 을 환불하는데, 쿠폰 100 은 그사이 주문 9 가 쓰고 있다
			Order order = createOrder(1L, null, 100L);
			givenOtherOrdersHoldingCoupon(100L, 1L, 9L);

			// when
			restorer.restore(order);

			// then
			verifyNoInteractions(couponService, discountCodeService);
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

	@Nested
	@DisplayName("만료 뒤 결제된 주문의 할인을 다시 쓸 때(reapply)")
	class Reapply {

		@Test
		@DisplayName("쿠폰을 다시 사용 처리했으면 알림 이벤트를 발행하지 않는다")
		void claimedCoupon_publishesNothing() {
			// given
			Order order = paidAfterExpiry(1L, null, 100L);
			given(couponService.claimForPaidOrder(100L)).willReturn(true);

			// when
			restorer.reapply(order);

			// then
			verifyNoInteractions(eventPublisher, discountCodeService);
		}

		@Test
		@DisplayName("다른 주문이 이미 쓰고 있는 쿠폰이면 결제 이상 이벤트에 주문 번호·주문 ID·결제 ID·쿠폰 ID 를 실어 발행한다")
		void couponUsedByAnotherOrder_publishesAnomaly() {
			// given
			Order order = paidAfterExpiry(1L, null, 100L);
			given(couponService.claimForPaidOrder(100L)).willReturn(false);

			// when
			restorer.reapply(order);

			// then
			Map<String, String> details = new LinkedHashMap<>();
			details.put("주문 번호", "merchant_1");
			details.put("주문 ID", "1");
			details.put("결제 ID", "pay_1");
			details.put("쿠폰 ID", "100");
			verify(eventPublisher).publishEvent(new PaymentAnomalyEvent(
				"만료 뒤 결제된 주문의 쿠폰을 다른 주문이 이미 쓰고 있습니다. 결제는 확정했습니다.", details));
		}

		@Test
		@DisplayName("쿠폰과 할인코드가 둘 다 있으면 쿠폰만 다시 쓴다")
		void couponAndCode_reappliesCouponOnly() {
			// given
			Order order = paidAfterExpiry(2L, "SALE10", 100L);
			given(couponService.claimForPaidOrder(100L)).willReturn(true);

			// when
			restorer.reapply(order);

			// then
			verifyNoInteractions(discountCodeService);
		}

		@Test
		@DisplayName("할인코드 사용 횟수를 다시 올려도 최대 횟수 안이면 알림 이벤트를 발행하지 않는다")
		void codeWithinLimit_publishesNothing() {
			// given
			Order order = paidAfterExpiry(3L, "SALE10", null);
			given(discountCodeService.reapplyUsage("SALE10")).willReturn(false);

			// when
			restorer.reapply(order);

			// then
			verifyNoInteractions(eventPublisher, couponService);
		}

		@Test
		@DisplayName("할인코드 사용 횟수가 최대 횟수를 넘으면 결제 이상 이벤트에 주문 번호·주문 ID·결제 ID·할인 코드를 실어 발행한다")
		void codeOverLimit_publishesAnomaly() {
			// given
			Order order = paidAfterExpiry(3L, "SALE10", null);
			given(discountCodeService.reapplyUsage("SALE10")).willReturn(true);

			// when
			restorer.reapply(order);

			// then
			Map<String, String> details = new LinkedHashMap<>();
			details.put("주문 번호", "merchant_3");
			details.put("주문 ID", "3");
			details.put("결제 ID", "pay_3");
			details.put("할인 코드", "SALE10");
			verify(eventPublisher).publishEvent(new PaymentAnomalyEvent(
				"만료 뒤 결제된 주문 때문에 할인 코드 사용 횟수가 최대 횟수를 넘었습니다. 결제는 확정했습니다.", details));
		}

		@ParameterizedTest(name = "[{index}] 할인 코드 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   ", "EVENT_FREE"})
		@DisplayName("쿠폰이 없고 할인코드가 없거나 공백이거나 EVENT_FREE 면 아무것도 하지 않는다")
		void noDiscountToReapply_doesNothing(String code) {
			// given
			Order order = paidAfterExpiry(4L, code, null);

			// when
			restorer.reapply(order);

			// then
			verifyNoInteractions(couponService, discountCodeService, eventPublisher);
		}
	}
}
