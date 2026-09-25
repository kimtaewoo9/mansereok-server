package com.mansereok.server.domain.order.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 주문에 적용된 할인(쿠폰 또는 할인코드)을 되돌리고 다시 쓰는 규칙의 단일 소유자.
 *
 * <p>규칙은 "쿠폰이 있으면 쿠폰, 아니면 할인코드, 아니면 없음" 이고, EVENT_FREE 같은 시스템 코드는
 * 대상이 아니다. 환불(PaymentRefundService)·만료(OrderExpirationService)·웹훅 실패 기록(PaymentWebhookService)이
 * 되돌리기를, 만료 뒤 결제 확정(PaidOrderFinalizer)이 다시 쓰기를 같은 규칙으로 부르도록 여기로 모았다.
 *
 * <p>지키려는 규칙은 "결제 대기·결제 완료 주문의 할인은 사용된 상태" 다. 트랜잭션은 호출자의 것에 참여한다. 여기서 건 잠금은
 * 호출자의 트랜잭션이 끝날 때 풀린다.
 *
 * <p>할인 행을 잠그는 순서는 경로마다 다르다.
 * <ul>
 *   <li>주문 생성(PaymentOrderService.createOrder): 쿠폰·할인 코드 행 → orders INSERT</li>
 *   <li>늦은 결제 확정({@link #reapply}): 주문 행(호출자) → 쿠폰·할인 코드 행</li>
 *   <li>쿠폰 복구({@link #restore}, 만료·환불·웹훅 실패 기록): 주문 행(호출자) → 같은 쿠폰을 쓴 다른 주문 행(공유 잠금) → 쿠폰 행</li>
 *   <li>할인 코드 복구({@link #restore}): 주문 행(호출자) → 할인 코드 행</li>
 * </ul>
 *
 * <p>이 순서는 orders.merchant_uid 인덱스를 전제로 한다. 인덱스가 있으면 결제 확정의 주문 잠금 조회는 자기 주문 행만 잠근다. 인덱스가
 * 없으면 그 조회가 orders 의 모든 행과 끝 틈까지 잠가 아래 두 교착이 난다. InnoDB 가 한쪽 트랜잭션을 되돌리므로 요청 하나가 실패한다.
 * <ul>
 *   <li>할인 행을 쥔 주문 생성은 INSERT 에서 확정을 기다리고, 늦은 결제 확정은 할인 행에서 주문 생성을 기다린다(서로 반대 순서).</li>
 *   <li>쿠폰 복구는 자기 주문 행을 쥔 채 확정이 잠근 행을 공유 잠금으로 기다리고, 확정은 쿠폰 복구가 쥔 주문 행을 기다린다.</li>
 * </ul>
 *
 * <p>인덱스가 있어도 쿠폰 복구 두 건이 겹치면(같은 쿠폰을 쥔 두 주문의 환불과 만료 등) 서로 자기 주문 행을 쥔 채 상대 행을 공유
 * 잠금으로 기다려 교착이 난다. orders.coupon_id 인덱스가 없으면 쿠폰이 달라도 orders 를 모두 훑으므로 같은 일이 난다. 만료는 다음
 * 주기에 다시 돌지만, 환불은 포트원 취소 뒤의 DB 확정이 실패해 CANCEL_REQUESTED 로 남는다(PaymentRefundService 설명).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderDiscountRestorer {

	private static final String EVENT_FREE_CODE = "EVENT_FREE";

	/**
	 * 할인을 쥐고 있는 주문 상태. 이 상태의 다른 주문이 같은 쿠폰을 쓰고 있으면 그 쿠폰은 되돌리지 않는다.
	 * VIRTUAL_ACCOUNT_ISSUED 는 지금 이 상태로 바꾸는 코드가 없지만, PENDING 처럼 결제를 기다리며 할인을 쥔 상태라 함께 둔다.
	 */
	private static final Set<OrderStatus> STATUSES_HOLDING_DISCOUNT = EnumSet.of(
		OrderStatus.PENDING, OrderStatus.VIRTUAL_ACCOUNT_ISSUED, OrderStatus.PAID);

	private final CouponService couponService;
	private final DiscountCodeService discountCodeService;
	private final OrderRepository orderRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 주문이 쓴 쿠폰 또는 할인코드를 복구한다.
	 *
	 * <p>쿠폰은 이 주문 말고 결제 대기·결제 완료인 다른 주문이 같은 쿠폰을 쓰고 있으면 되돌리지 않는다. 만료 뒤 늦게 결제된 주문이
	 * 그사이 다른 주문에 넘어간 쿠폰을 기록상 들고 있을 수 있는데, 이 주문을 환불하면서 쿠폰을 풀면 다른 주문이 쓰는 쿠폰이 다시
	 * 쓸 수 있게 되기 때문이다.
	 *
	 * <p>다른 주문이 쥐고 있는지는 공유 잠금 읽기로 판단한다. 잠그지 않고 읽으면 트랜잭션 스냅샷을 읽는다. 그래서 늦은 결제 확정이
	 * 주문 행과 쿠폰 행을 잠근 채 아직 커밋하지 않은 동안에는 그 주문을 여전히 EXPIRED 로 본다. 그러면 확정이 커밋된 뒤 쿠폰을 풀어,
	 * 결제 완료 주문이 쥔 쿠폰이 미사용으로 남는다. 공유 잠금 읽기는 그 확정이 커밋될 때까지 기다린 뒤 PAID 로 읽는다.
	 *
	 * <p>쿠폰 행보다 다른 주문 행을 먼저 잠근다. 늦은 결제 확정은 주문 행 → 쿠폰 행 순서로 잠그므로, 여기서 쿠폰 행을 먼저 잠그면
	 * 확정이 쥔 주문 행을 기다리는 동안 확정은 이 트랜잭션이 쥔 쿠폰 행을 기다려 교착이 난다.
	 */
	public void restore(Order order) {
		Long couponId = order.getCouponId();
		if (couponId != null) {
			List<Long> otherHolderIds = orderRepository.findIdsByCouponIdAndStatusInAndIdNotForShare(couponId,
				STATUSES_HOLDING_DISCOUNT, order.getId());
			if (!otherHolderIds.isEmpty()) {
				log.warn("다른 주문이 쓰고 있는 쿠폰이라 복구하지 않습니다: orderId={}, couponId={}, 쓰고 있는 주문 ID={}",
					order.getId(), couponId, otherHolderIds);
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
	 * 만료 때 되돌린 쿠폰 또는 할인코드를 다시 사용 처리한다. 만료된 주문이 늦게 결제되어 PAID 로 확정될 때 같은 트랜잭션에서 부른다.
	 *
	 * <p>다시 쓸 수 없어도 예외를 던지지 않는다. 결제는 이미 끝났으므로 확정은 그대로 두고, 커밋 뒤 운영 채널로 알리도록
	 * {@link PaymentAnomalyEvent} 를 발행한다. 알리는 경우는 둘이다.
	 * <ul>
	 *   <li>쿠폰을 그사이 다른 주문이 이미 쓰고 있다. 쿠폰 한 장의 할인이 두 결제에 들어갔다.</li>
	 *   <li>할인 코드 사용 횟수가 최대 횟수를 넘었다. 선착순 인원보다 많이 할인됐다.</li>
	 * </ul>
	 *
	 * <p>호출자가 주문 행을 잠근 뒤 쿠폰·할인 코드 행을 잠근다. 주문 생성과는 반대 순서라 orders.merchant_uid 인덱스를 전제로 한다
	 * (클래스 설명).
	 */
	public void reapply(Order order) {
		Long couponId = order.getCouponId();
		if (couponId != null) {
			if (!couponService.claimForPaidOrder(couponId)) {
				log.error("만료 뒤 결제된 주문의 쿠폰을 다른 주문이 이미 쓰고 있습니다: orderId={}, couponId={}",
					order.getId(), couponId);
				publishAnomaly("만료 뒤 결제된 주문의 쿠폰을 다른 주문이 이미 쓰고 있습니다. 결제는 확정했습니다.",
					order, "쿠폰 ID", String.valueOf(couponId));
				return;
			}
			log.info("만료 뒤 결제된 주문의 쿠폰을 다시 사용 처리: orderId={}, couponId={}", order.getId(), couponId);
			return;
		}

		String code = discountCodeOf(order);
		if (code == null) {
			return;
		}

		if (discountCodeService.reapplyUsage(code)) {
			log.error("만료 뒤 결제된 주문 때문에 할인 코드 사용 횟수가 최대 횟수를 넘었습니다: orderId={}, code={}",
				order.getId(), code);
			publishAnomaly("만료 뒤 결제된 주문 때문에 할인 코드 사용 횟수가 최대 횟수를 넘었습니다. 결제는 확정했습니다.",
				order, "할인 코드", code);
			return;
		}
		log.info("만료 뒤 결제된 주문의 할인코드 사용 횟수를 다시 올림: orderId={}, code={}", order.getId(), code);
	}

	/** 되돌리거나 다시 쓸 할인 코드. 없거나 공백이거나 시스템 코드(EVENT_FREE)면 null. */
	private static String discountCodeOf(Order order) {
		String code = order.getAppliedDiscountCode();
		if (code == null || code.isBlank() || EVENT_FREE_CODE.equals(code)) {
			return null;
		}
		return code;
	}

	private void publishAnomaly(String summary, Order order, String discountName, String discountValue) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", order.getMerchantUid());
		details.put("주문 ID", String.valueOf(order.getId()));
		details.put("결제 ID", order.getPaymentId());
		details.put(discountName, discountValue);
		eventPublisher.publishEvent(new PaymentAnomalyEvent(summary, details));
	}
}
