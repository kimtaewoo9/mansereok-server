package com.mansereok.server.domain.order.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.service.PaymentUserLookup;
import com.mansereok.server.global.exception.OrderStateException;
import jakarta.persistence.EntityNotFoundException;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 결제창에서 결제하지 않고 나온 주문이 쥔 쿠폰·할인 코드를 만료 스케줄러를 기다리지 않고 바로 돌려준다.
 *
 * <p>만료와 같은 경로(OrderExpirationService)로 EXPIRED 로 바꾼다. 아직 PENDING 일 때만 바꾸므로 결제 확정과 겹쳐도 PAID 를
 * 덮어쓰지 않는다. 이탈을 알린 뒤 결제가 늦게 들어오면 만료 뒤 결제와 같이 할인을 다시 잡아야 확정하고, 못 잡으면 결제를
 * 취소한다(OrderDiscountRestorer.reclaim).
 * 브라우저가 꺼져 이탈 알림이 오지 않은 주문은 지금처럼 만료 스케줄러가 정리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderAbandonService {

	/** 할인을 돌려준 상태. 같은 이탈 알림이 다시 와도 성공으로 답한다. */
	private static final Set<OrderStatus> DISCOUNT_RELEASED = EnumSet.of(OrderStatus.EXPIRED, OrderStatus.FAILED);

	private final OrderRepository orderRepository;
	private final PaymentUserLookup paymentUserLookup;
	private final OrderExpirationService orderExpirationService;

	/**
	 * @throws EntityNotFoundException 주문이 없을 때 (404)
	 * @throws AccessDeniedException   요청자가 주문 소유자가 아닐 때 (403)
	 * @throws OrderStateException     이미 결제됐거나 환불된 주문일 때 (400)
	 */
	public Order abandon(Long orderId, String username) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
		Long requesterId = paymentUserLookup.getByUsername(username).getId();
		if (!order.isOwnedBy(requesterId)) {
			log.warn("권한 없는 결제창 이탈 요청: 요청자={}, 주문 소유자={}, orderId={}", requesterId, order.getUserId(), orderId);
			throw new AccessDeniedException("본인의 주문만 취소할 수 있습니다.");
		}

		orderExpirationService.expireIfStillPending(orderId);

		Order current = orderRepository.findById(orderId).orElseThrow();
		if (!DISCOUNT_RELEASED.contains(current.getStatus())) {
			throw new OrderStateException("이미 결제된 주문은 취소할 수 없습니다. 환불을 요청해 주세요.");
		}
		return current;
	}
}
