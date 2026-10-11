package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·주문 조회 전용 서비스.
 *
 * <p>주문 단건 조회는 반드시 요청자 소유권을 검사한다. 판정은 {@link Order#isOwnedBy(Long)} 한 곳에 있고 결제 완료 경로
 * (PaymentConfirmService)도 같은 판정을 쓴다. 여기서는 조회에 맞는 예외(403)만 고른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

	private final OrderRepository orderRepository;
	private final PaymentUserLookup paymentUserLookup;
	private final PaymentRepository paymentRepository;
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	/**
	 * 요청자 본인의 주문을 PK 로 조회한다.
	 *
	 * @throws EntityNotFoundException 주문이 없을 때 (404)
	 * @throws PaymentException        사용자가 없을 때 (400)
	 * @throws AccessDeniedException   요청자가 주문 소유자가 아닐 때 (403)
	 */
	public Order getOwnedOrder(Long orderId, String username) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
		assertOwnedBy(order, paymentUserLookup.getByUsername(username));
		return order;
	}

	/**
	 * 요청자 본인의 주문을 Payment PK 로 조회한다.
	 *
	 * @throws EntityNotFoundException 주문이 없을 때 (404)
	 * @throws PaymentException        사용자가 없을 때 (400)
	 * @throws AccessDeniedException   요청자가 주문 소유자가 아닐 때 (403)
	 */
	public Order getOwnedOrderByPaymentPkId(Long paymentPkId, String username) {
		Order order = orderRepository.findByPaymentPkId(paymentPkId)
			.orElseThrow(() -> new EntityNotFoundException("결제 ID에 해당하는 주문을 찾을 수 없습니다."));
		assertOwnedBy(order, paymentUserLookup.getByUsername(username));
		return order;
	}

	/**
	 * 요청자의 결제 목록을 최근 것부터 돌려준다. 결제마다 결과 상태와 환불 가능 여부를 붙인다.
	 *
	 * @throws PaymentException 사용자가 없을 때 (400)
	 */
	public List<PaymentResponseDto> getPayments(String username) {
		User user = paymentUserLookup.getByUsername(username);

		List<Payment> payments = paymentRepository.findAllByUserIdOrderByCreatedAtDesc(
			user.getId());

		// 1. 조회된 결제들의 ID 목록 추출
		List<Long> paymentIds = payments.stream().map(Payment::getId).toList();

		// 2. 일반 사주(Result)와 궁합(CompatibilityResult)의 상태를 한 번씩 조회해 합친다 (paymentId -> ResultStatus).
		//    같은 결제에 둘 다 있을 일은 없지만, 있다면 ResultService.findStatusByPaymentId 와 같이 Result 가 우선한다.
		Map<Long, ResultStatus> statusMap = new HashMap<>();
		for (Result result : resultRepository.findByPaymentIdIn(paymentIds)) {
			statusMap.put(result.getPaymentId(), result.getStatus());
		}
		for (CompatibilityResult result : compatibilityResultRepository.findByPaymentIdIn(paymentIds)) {
			statusMap.putIfAbsent(result.getPaymentId(), result.getStatus());
		}

		// 3. 조립
		return payments.stream().map(payment -> {
			ResultStatus status = statusMap.get(payment.getId());
			return PaymentResponseDto.create(payment, status);
		}).collect(Collectors.toList());
	}

	/**
	 * 주문 소유자와 요청자를 대조한다. 탈퇴 처리로 userId 가 null 인 주문은 누구의 것도 아니므로 거부한다.
	 */
	private void assertOwnedBy(Order order, User user) {
		if (!order.isOwnedBy(user.getId())) {
			log.warn("권한 없는 주문 조회 시도: 요청자={}, 주문 소유자={}, orderId={}",
				user.getId(), order.getUserId(), order.getId());
			throw new AccessDeniedException("본인의 주문만 조회할 수 있습니다.");
		}
	}
}
