package com.mansereok.server.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.AppliedDiscount;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.support.fixture.TestOrders;
import java.time.LocalDateTime;
import java.util.EnumSet;
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


	@InjectMocks
	private OrderDiscountRestorer restorer;

	/** 할인 표기와 쿠폰 id 를 저장된 값 그대로 담은 주문. 공백 코드처럼 팩터리로는 만들지 않는 값도 넣는다. */
	private TestOrders orderWith(Long id, String discountCode, Long couponId) {
		return TestOrders.order().id(id).merchantUid("merchant_" + id).amounts(10000, 5000)
			.discount(new AppliedDiscount(discountCode, couponId));
	}

	private Order createOrder(Long id, String discountCode, Long couponId) {
		return orderWith(id, discountCode, couponId).pending();
	}

	/** 만료된 주문. 늦은 결제를 확정하기 전에 reclaim 으로 할인을 다시 잡는다. */
	private Order expiredOrder(Long id, String discountCode, Long couponId) {
		return orderWith(id, discountCode, couponId).inStatus(OrderStatus.EXPIRED);
	}

	private void givenOtherOrderHoldingCoupon(Long couponId, Long orderId, boolean holding) {
		given(orderRepository.existsByCouponIdAndStatusInAndIdNot(couponId, HOLDING_STATUSES, orderId))
			.willReturn(holding);
	}

	@Nested
	@DisplayName("할인을 되돌릴 때(restore)")
	class Restore {

		@Test
		@DisplayName("쿠폰을 쓴 주문은 다른 주문이 그 쿠폰을 쥐고 있지 않으면 쿠폰만 복구하고 할인코드는 건드리지 않는다")
		void restore_couponOrder_restoresCouponOnly() {
			Order order = createOrder(1L, null, 100L);
			givenOtherOrderHoldingCoupon(100L, 1L, false);

			restorer.restore(order);

			verify(couponService).restoreCoupon(100L);
			verify(discountCodeService, never()).restoreDiscountUsage(any());
		}

		@Test
		@DisplayName("쿠폰과 할인코드가 둘 다 있으면 쿠폰만 복구한다")
		void restore_couponAndCode_prefersCoupon() {
			Order order = createOrder(2L, "SALE10", 100L);
			givenOtherOrderHoldingCoupon(100L, 2L, false);

			restorer.restore(order);

			verify(couponService).restoreCoupon(100L);
			verify(discountCodeService, never()).restoreDiscountUsage(any());
		}

		@Test
		@DisplayName("결제 대기·가상계좌 발급·결제 완료인 다른 주문이 같은 쿠폰을 쓰고 있으면 쿠폰을 되돌리지 않는다")
		void restore_couponHeldByAnotherActiveOrder_isLeftUsed() {
			// given: 만료 뒤 늦게 결제된 주문 1 을 환불하는데, 쿠폰 100 은 그사이 주문 9 가 쓰고 있다
			Order order = createOrder(1L, null, 100L);
			givenOtherOrderHoldingCoupon(100L, 1L, true);

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
		@DisplayName("무료 이벤트 표기(EVENT_FREE)는 복구하지 않는다")
		void restore_eventFreeCode_doesNothing() {
			Order order = orderWith(4L, null, null).discount(AppliedDiscount.eventFree()).pending();

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
	@DisplayName("만료된 주문의 늦은 결제를 확정하기 전에 할인을 다시 잡을 때(reclaim)")
	class Reclaim {

		@ParameterizedTest(name = "[{index}] 쿠폰을 다시 잡았는가 {0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("쿠폰이 있으면 쿠폰을 다시 잡은 결과를 그대로 돌려준다")
		void coupon_returnsClaimResult(boolean claimed) {
			// given
			Order order = expiredOrder(1L, null, 100L);
			given(couponService.claimForPaidOrder(100L)).willReturn(claimed);

			// when & then
			assertThat(restorer.reclaim(order)).isEqualTo(claimed);
			verifyNoInteractions(discountCodeService);
		}

		@Test
		@DisplayName("쿠폰과 할인코드가 둘 다 있으면 쿠폰만 다시 잡는다")
		void couponAndCode_claimsCouponOnly() {
			// given
			Order order = expiredOrder(2L, "SALE10", 100L);
			given(couponService.claimForPaidOrder(100L)).willReturn(true);

			// when & then
			assertThat(restorer.reclaim(order)).isTrue();
			verifyNoInteractions(discountCodeService);
		}

		@ParameterizedTest(name = "[{index}] 할인 코드를 다시 잡았는가 {0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("할인코드만 있으면 할인코드를 다시 잡은 결과를 그대로 돌려준다")
		void code_returnsClaimResult(boolean claimed) {
			// given
			Order order = expiredOrder(3L, "SALE10", null);
			given(discountCodeService.claimForPaidOrder("SALE10")).willReturn(claimed);

			// when & then
			assertThat(restorer.reclaim(order)).isEqualTo(claimed);
			verifyNoInteractions(couponService);
		}

		@ParameterizedTest(name = "[{index}] 할인 코드 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   ", AppliedDiscount.EVENT_FREE_CODE})
		@DisplayName("쿠폰이 없고 할인코드가 없거나 공백이거나 EVENT_FREE 면 잡을 할인이 없으므로 true 를 돌려준다")
		void noDiscount_returnsTrue(String code) {
			// given
			Order order = expiredOrder(4L, code, null);

			// when & then
			assertThat(restorer.reclaim(order)).isTrue();
			verifyNoInteractions(couponService, discountCodeService);
		}
	}
}
