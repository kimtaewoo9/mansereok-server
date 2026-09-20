package com.mansereok.server.domain.order.scheduler;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 30분 넘게 PENDING 인 주문을 만료시킨다.
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다. 대상 id 만 뽑은 뒤 OrderExpirationService 가 건별
 * 독립 트랜잭션으로 처리하므로 한 건의 실패가 나머지 건을 롤백시키지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationScheduler {

	static final long ORDER_EXPIRATION_MINUTES = 30;

	private final OrderRepository orderRepository;
	private final OrderExpirationService orderExpirationService;
	private final Clock clock;

	// 30분마다 실행
	@Scheduled(fixedRate = 1800000)
	public void expireStaleOrders() {
		LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(ORDER_EXPIRATION_MINUTES);

		List<Long> staleOrderIds = orderRepository.findAllByStatusAndCreatedAtBefore(
				OrderStatus.PENDING, cutoff).stream()
			.map(Order::getId)
			.toList();

		if (staleOrderIds.isEmpty()) {
			return;
		}

		log.info("만료 대상 주문 {}건 발견", staleOrderIds.size());

		int expired = 0;
		int skipped = 0;
		int failed = 0;
		for (Long orderId : staleOrderIds) {
			try {
				if (orderExpirationService.expireIfStillPending(orderId)) {
					expired++;
				} else {
					skipped++;
				}
			} catch (Exception e) {
				failed++;
				log.error("주문 만료 처리 실패: orderId={}, error={}", orderId, e.getMessage(), e);
			}
		}

		log.info("만료 주문 처리 완료: 대상 {}건, 만료 {}건, 건너뜀 {}건, 실패 {}건",
			staleOrderIds.size(), expired, skipped, failed);
	}
}
