package com.mansereok.server.domain.order.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 한 건의 만료 처리. 건마다 독립 트랜잭션(REQUIRES_NEW)이라 한 건의 실패가 다른 건을 롤백시키지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationService {

	private final OrderRepository orderRepository;
	private final OrderDiscountRestorer orderDiscountRestorer;

	/**
	 * 주문이 아직 PENDING 일 때만 EXPIRED 로 바꾸고 할인을 복구한다.
	 *
	 * <p>조건부 UPDATE(status = PENDING 인 행만 갱신)의 영향 행 수로 분기하므로, 조회와 갱신 사이에
	 * 결제가 완료되어 PAID 가 된 주문은 덮어쓰지 않고 건너뛴다.
	 *
	 * @return 만료 처리했으면 true, 이미 다른 상태라 건너뛰었으면 false
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean expireIfStillPending(Long orderId) {
		int updated = orderRepository.updateStatusIf(orderId, OrderStatus.PENDING,
			OrderStatus.EXPIRED);
		if (updated == 0) {
			log.info("주문이 더 이상 PENDING 이 아니라 만료를 건너뜁니다. orderId={}", orderId);
			return false;
		}

		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new IllegalStateException(
				"만료 처리한 주문을 다시 읽을 수 없습니다. orderId=" + orderId));
		orderDiscountRestorer.restore(order);

		log.info("주문 만료 처리 완료: orderId={}, merchantUid={}", order.getId(), order.getMerchantUid());
		return true;
	}
}
