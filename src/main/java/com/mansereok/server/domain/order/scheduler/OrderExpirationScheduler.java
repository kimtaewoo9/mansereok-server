package com.mansereok.server.domain.order.scheduler;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationScheduler {

	private final OrderRepository orderRepository;
	private final DiscountCodeService discountCodeService;
	private final CouponService couponService;

	// 30분마다 실행
	@Scheduled(fixedRate = 1800000)
	@Transactional
	public void expireStaleOrders() {
		LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);

		List<Order> staleOrders = orderRepository.findAllByStatusAndCreatedAtBefore(
			OrderStatus.PENDING, cutoff);

		if (staleOrders.isEmpty()) {
			return;
		}

		log.info("만료 대상 주문 {}건 발견", staleOrders.size());

		for (Order order : staleOrders) {
			try {
				// 1. 주문 상태 만료로 변경
				order.markExpired();

				// 2. 쿠폰 복구
				if (order.getCouponId() != null) {
					couponService.restoreCoupon(order.getCouponId());
					log.info("만료 주문 쿠폰 복구: orderId={}, couponId={}", order.getId(), order.getCouponId());
				}
				// 3. 할인코드 복구
				else if (order.getAppliedDiscountCode() != null && !order.getAppliedDiscountCode().isBlank()) {
					if (!"EVENT_FREE".equals(order.getAppliedDiscountCode())) {
						discountCodeService.restoreDiscountUsage(order.getAppliedDiscountCode());
						log.info("만료 주문 할인코드 복구: orderId={}, code={}", order.getId(), order.getAppliedDiscountCode());
					}
				}

				log.info("주문 만료 처리 완료: orderId={}, merchantUid={}", order.getId(), order.getMerchantUid());
			} catch (Exception e) {
				log.error("주문 만료 처리 실패: orderId={}, error={}", order.getId(), e.getMessage());
			}
		}

		orderRepository.saveAll(staleOrders);
		log.info("만료 주문 {}건 처리 완료", staleOrders.size());
	}
}
