package com.mansereok.server.domain.order.service;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주문에 적용된 할인(쿠폰 또는 할인코드)을 되돌리고 다시 쓰는 규칙의 단일 소유자.
 *
 * <p>규칙은 "쿠폰이 있으면 쿠폰, 아니면 할인코드, 아니면 없음" 이고, 무료 이벤트 표기 같은 시스템 표기
 * ({@link Order#hasSystemDiscountCode()})는 대상이 아니다. 환불(PaymentRefundService)·만료(OrderExpirationService)·웹훅 실패 기록(PaymentWebhookService)이
 * 되돌리기를, 만료된 주문의 늦은 결제 확정(PaymentConfirmService·PaymentWebhookService)이 다시 쓰기를 같은 규칙으로 부르도록
 * 여기로 모았다.
 *
 * <p>지키려는 규칙은 "결제 대기·결제 완료 주문의 할인은 사용된 상태" 다. 트랜잭션은 호출자의 것에 참여한다. 여기서 건 잠금은
 * 호출자의 트랜잭션이 끝날 때 풀린다.
 *
 * <p>할인 행을 잠그는 순서는 경로마다 다르다.
 * <ul>
 *   <li>주문 생성(PaymentOrderService.createOrder): 쿠폰·할인 코드 행 → orders INSERT</li>
 *   <li>늦은 결제 확정({@link #reclaim}): 주문 행(호출자) → 쿠폰·할인 코드 행</li>
 *   <li>할인 복구({@link #restore}, 만료·환불·웹훅 실패 기록): 주문 행(호출자) → 쿠폰·할인 코드 행. 같은 쿠폰을 쓴 다른 주문은
 *   잠그지 않고 읽는다.</li>
 * </ul>
 *
 * <p>늦은 결제 확정은 주문 생성과 반대 순서라 orders.merchant_uid 인덱스를 전제로 한다. 인덱스가 있으면 결제 확정의 주문 잠금
 * 조회는 자기 주문 행만 잠근다. 인덱스가 없으면 그 조회가 orders 의 모든 행과 끝 틈까지 잠가, 할인 행을 쥔 주문 생성은 INSERT 에서
 * 확정을 기다리고 늦은 결제 확정은 할인 행에서 주문 생성을 기다려 교착이 난다. InnoDB 가 한쪽 트랜잭션을 되돌리므로 요청 하나가
 * 실패한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderDiscountRestorer {

	/**
	 * 할인을 쥐고 있는 주문 상태. 이 상태의 다른 주문이 같은 쿠폰을 쓰고 있으면 그 쿠폰은 되돌리지 않는다.
	 * VIRTUAL_ACCOUNT_ISSUED 는 지금 이 상태로 바꾸는 코드가 없지만, PENDING 처럼 결제를 기다리며 할인을 쥔 상태라 함께 둔다.
	 */
	private static final Set<OrderStatus> STATUSES_HOLDING_DISCOUNT = EnumSet.of(
		OrderStatus.PENDING, OrderStatus.VIRTUAL_ACCOUNT_ISSUED, OrderStatus.PAID);

	private final CouponService couponService;
	private final DiscountCodeService discountCodeService;
	private final OrderRepository orderRepository;

	/**
	 * 주문이 쓴 쿠폰 또는 할인코드를 복구한다.
	 *
	 * <p>쿠폰은 이 주문 말고 결제 대기·결제 완료인 다른 주문이 같은 쿠폰을 쓰고 있으면 되돌리지 않는다. 만료 뒤 늦게 결제된 주문이
	 * 그사이 다른 주문에 넘어간 쿠폰을 기록상 들고 있을 수 있는데, 이 주문을 환불하면서 쿠폰을 풀면 다른 주문이 쓰는 쿠폰이 다시
	 * 쓸 수 있게 되기 때문이다.
	 *
	 * <p>다른 주문이 쥐고 있는지는 잠그지 않고 읽는다. 잠금 읽기로 판단하면 orders.coupon_id 인덱스가 없는 DB 에서는 orders 를 모두
	 * 잠그며 훑어, 늦은 결제와 상관없이 쿠폰이 서로 다른 주문의 환불·만료끼리도 교착이 난다. 쿠폰 행은 잠가 읽는다
	 * (CouponService#restoreCoupon).
	 *
	 * <p>만료된 주문에 늦게 들어온 결제는 {@link #reclaim} 으로 할인을 다시 잡았을 때만 확정되므로, 결제 완료 주문이 쥔 쿠폰을 다른
	 * 주문이 함께 쥐는 일은 생기지 않는다. 이 확인은 그 규칙이 생기기 전에 확정된 주문을 위해 남겨 둔다.
	 */
	public void restore(Order order) {
		Long couponId = order.getCouponId();
		if (couponId != null) {
			if (orderRepository.existsByCouponIdAndStatusInAndIdNot(couponId, STATUSES_HOLDING_DISCOUNT,
				order.getId())) {
				log.warn("다른 주문이 쓰고 있는 쿠폰이라 복구하지 않습니다: orderId={}, couponId={}", order.getId(),
					couponId);
				return;
			}
			couponService.restoreCoupon(couponId);
			log.info("주문 쿠폰 복구 완료: orderId={}, couponId={}", order.getId(), couponId);
			return;
		}

		String code = discountCodeOf(order);
		if (code == null) {
			return;
		}

		discountCodeService.restoreDiscountUsage(code);
		log.info("주문 할인코드 복구 완료: orderId={}, code={}", order.getId(), code);
	}

	/**
	 * 만료 때 되돌린 쿠폰 또는 할인코드를 다시 사용 처리한다. 만료된 주문에 결제가 늦게 들어왔을 때 확정하기 전에 부른다.
	 *
	 * <p>그사이 다른 주문이 쿠폰을 썼거나 할인 코드가 최대 횟수에 닿았으면 아무것도 바꾸지 않고 false 를 돌려준다. 호출자는 그 결제를
	 * 확정하지 않고 포트원에서 취소한다. 확정하면 쿠폰 한 장·선착순 한 자리의 할인이 두 결제에 들어간다.
	 *
	 * <p>호출자가 주문 행을 잠근 뒤 쿠폰·할인 코드 행을 잠근다. 주문 생성과는 반대 순서라 orders.merchant_uid 인덱스를 전제로 한다
	 * (클래스 설명).
	 *
	 * @return 다시 사용 처리했거나 되돌릴 할인이 없으면 true, 다시 쓸 수 없으면 false
	 */
	public boolean reclaim(Order order) {
		Long couponId = order.getCouponId();
		if (couponId != null) {
			return couponService.claimForPaidOrder(couponId);
		}

		String code = discountCodeOf(order);
		if (code == null) {
			return true;
		}
		return discountCodeService.claimForPaidOrder(code);
	}

	/** 되돌리거나 다시 쓸 할인 코드. 없거나 공백이거나 시스템 표기(무료 이벤트)면 null. */
	private static String discountCodeOf(Order order) {
		String code = order.getAppliedDiscountCode();
		if (code == null || code.isBlank() || order.hasSystemDiscountCode()) {
			return null;
		}
		return code;
	}
}
