package com.mansereok.server.domain.order.service;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주문에 적용된 할인(쿠폰 또는 할인코드)을 되돌리는 규칙의 단일 소유자.
 *
 * <p>규칙은 "쿠폰이 있으면 쿠폰, 아니면 할인코드, 아니면 없음" 이고, EVENT_FREE 같은 시스템 코드는
 * 복구 대상이 아니다. 환불(PaymentService.cancelPayment)과 만료(OrderExpirationService)가
 * 같은 규칙을 쓰도록 여기로 모았다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderDiscountRestorer {

	private static final String EVENT_FREE_CODE = "EVENT_FREE";

	private final CouponService couponService;
	private final DiscountCodeService discountCodeService;

	/**
	 * 주문이 쓴 쿠폰 또는 할인코드를 복구한다. 트랜잭션은 호출자의 것에 참여한다.
	 */
	public void restore(Order order) {
		if (order.getCouponId() != null) {
			couponService.restoreCoupon(order.getCouponId());
			log.info("주문 쿠폰 복구 완료: orderId={}, couponId={}", order.getId(), order.getCouponId());
			return;
		}

		String code = order.getAppliedDiscountCode();
		if (code == null || code.isBlank() || EVENT_FREE_CODE.equals(code)) {
			return;
		}

		discountCodeService.restoreDiscountUsage(code);
		log.info("주문 할인코드 복구 완료: orderId={}, code={}", order.getId(), code);
	}
}
