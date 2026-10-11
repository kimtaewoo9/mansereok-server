package com.mansereok.server.domain.order.scheduler;

import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

	/** 주문을 만든 뒤 이 시간이 지나도 PENDING 이면 만료 대상이다. */
	static final long ORDER_EXPIRATION_MINUTES = 30;

	/**
	 * 만료 대상을 찾는 주기. 만료 기준({@link #ORDER_EXPIRATION_MINUTES})과는 다른 값이다. 주문은 만료 기준이 지난 뒤 다음 스캔까지
	 * PENDING 으로 남으므로 최대 (만료 기준 + 이 주기) 동안 쿠폰·할인 코드를 쥔다. 만료 기준을 줄일 때는 이 값도 함께 본다.
	 */
	static final long EXPIRATION_SCAN_INTERVAL_MINUTES = 30;

	private final OrderRepository orderRepository;
	private final OrderExpirationService orderExpirationService;
	private final Clock clock;

	@Scheduled(fixedRate = EXPIRATION_SCAN_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
	public void expireStaleOrders() {
		LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(ORDER_EXPIRATION_MINUTES);

		List<Long> staleOrderIds = orderRepository.findIdsByStatusAndCreatedAtBefore(OrderStatus.PENDING,
			cutoff);

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
