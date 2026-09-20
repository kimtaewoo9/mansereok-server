package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·주문 조회 전용 서비스.
 *
 * <p>주문 단건 조회는 반드시 요청자 소유권을 검사한다. 소유권 규칙은 이 클래스의
 * {@link #assertOwnedBy(Order, User)} 한 곳에만 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

	private final OrderRepository orderRepository;
	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;
	private final ResultRepository resultRepository;

	/**
	 * 요청자 본인의 주문을 PK 로 조회한다.
	 *
	 * @throws EntityNotFoundException 주문 또는 사용자가 없을 때 (404)
	 * @throws AccessDeniedException   요청자가 주문 소유자가 아닐 때 (403)
	 */
	public Order getOwnedOrder(Long orderId, String username) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
		assertOwnedBy(order, findUser(username));
		return order;
	}

	/**
	 * 요청자 본인의 주문을 Payment PK 로 조회한다.
	 *
	 * @throws EntityNotFoundException 주문 또는 사용자가 없을 때 (404)
	 * @throws AccessDeniedException   요청자가 주문 소유자가 아닐 때 (403)
	 */
	public Order getOwnedOrderByPaymentPkId(Long paymentPkId, String username) {
		Order order = orderRepository.findByPaymentPkId(paymentPkId)
			.orElseThrow(() -> new EntityNotFoundException("결제 ID에 해당하는 주문을 찾을 수 없습니다."));
		assertOwnedBy(order, findUser(username));
		return order;
	}

	public Payment getPayment(Long paymentId) {
		return paymentRepository.findById(paymentId).orElseThrow(EntityNotFoundException::new);
	}

	public List<PaymentResponseDto> getPayments(String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

		List<Payment> payments = paymentRepository.findAllByUserIdOrderByCreatedAtDesc(
			user.getId());

		// 1. 조회된 결제들의 ID 목록 추출
		List<Long> paymentIds = payments.stream().map(Payment::getId).toList();

		// 2. Result 를 한 번에 조회
		List<Result> results = resultRepository.findByPaymentIdIn(paymentIds);

		// 3. 매핑 편의를 위해 Map 으로 변환 (paymentId -> ResultStatus)
		Map<Long, ResultStatus> statusMap = results.stream()
			.collect(Collectors.toMap(Result::getPaymentId, Result::getStatus));

		// 4. 조립
		return payments.stream().map(payment -> {
			ResultStatus status = statusMap.get(payment.getId());
			return PaymentResponseDto.create(payment, status);
		}).collect(Collectors.toList());
	}

	private User findUser(String username) {
		return userRepository.findByUsername(username)
			.orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다: " + username));
	}

	/**
	 * 주문 소유자와 요청자를 대조한다. 탈퇴 처리로 userId 가 null 인 주문은 누구의 것도 아니므로 거부한다.
	 */
	private void assertOwnedBy(Order order, User user) {
		if (order.getUserId() == null || !Objects.equals(order.getUserId(), user.getId())) {
			log.warn("권한 없는 주문 조회 시도: 요청자={}, 주문 소유자={}, orderId={}",
				user.getId(), order.getUserId(), order.getId());
			throw new AccessDeniedException("본인의 주문만 조회할 수 있습니다.");
		}
	}
}
