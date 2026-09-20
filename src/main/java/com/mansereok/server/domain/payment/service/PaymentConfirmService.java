package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 완료 API 의 확정 절차. 포트원 조회를 트랜잭션(주문 행 락) 밖으로 빼고, 잠금 구간에는 DB 작업만 남긴다.
 *
 * <ol>
 *   <li>트랜잭션 밖: 포트원 결제 조회. 타임아웃 없는 외부 호출이 행 락과 DB 커넥션 점유 시간이 되지 않게 한다.</li>
 *   <li>트랜잭션 안: 잠금 조회 → 소유자 검사 → PAID 멱등 반환 → 결제 중복 검사 → customData 대조 → 금액 검증
 *       → 상태 매핑 → 확정({@link PaidOrderFinalizer#finalizePaid}).</li>
 * </ol>
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. Discord 알림은
 * finalizePaid 가 발행한 이벤트를 커밋 뒤 비동기 리스너가 받아 보낸다.
 *
 * <p>포트원 응답은 트랜잭션 밖에서 받은 스냅샷이다. 그 사이 웹훅이 같은 주문을 먼저 확정했으면 잠금 조회 뒤의
 * PAID 멱등 반환·결제 중복 검사가 걸러낸다.
 */
@Service
@Slf4j
public class PaymentConfirmService {

	private final UserRepository userRepository;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final PaidOrderFinalizer paidOrderFinalizer;
	private final TransactionTemplate transactionTemplate;

	public PaymentConfirmService(
		UserRepository userRepository,
		OrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PortOneClient portOneClient,
		PaymentVerifier paymentVerifier,
		PaidOrderFinalizer paidOrderFinalizer,
		PlatformTransactionManager transactionManager
	) {
		this.userRepository = userRepository;
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.portOneClient = portOneClient;
		this.paymentVerifier = paymentVerifier;
		this.paidOrderFinalizer = paidOrderFinalizer;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 결제 완료 검증·확정. 클라이언트가 보낸 paymentId 와 merchantUid 를 그대로 믿지 않는다.
	 *
	 * @throws AccessDeniedException 요청자가 주문 소유자가 아닐 때 (403)
	 * @throws PaymentException      사용자 없음 · 주문 없음 · 결제 중복 · 주문 번호 불일치 · 금액 불일치 (400)
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 포트원 일시 장애 (503, 트랜잭션 시작 전)
	 */
	public Order complete(String username, PaymentCompleteRequest request) {
		log.info("결제 완료 요청 및 검증: username={}, paymentId={}, merchantUid={}",
			username, request.getPaymentId(), request.getMerchantUid());

		// 1. 트랜잭션 밖: 포트원 API 조회를 통한 2차 검증 자료
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(request.getPaymentId());

		// 2. 트랜잭션 안: 잠금·검증·확정
		return transactionTemplate.execute(status -> confirm(username, request, paymentResponse));
	}

	private Order confirm(String username, PaymentCompleteRequest request,
		PortOnePaymentResponse paymentResponse) {
		String paymentId = request.getPaymentId();

		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		// 비관적 락으로 주문 조회
		Order order = orderRepository.findByMerchantUidWithLock(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		// 소유자 대조. 멱등 반환보다 먼저 해서 타인의 PAID 주문 정보도 새지 않게 한다.
		assertOrderOwnedBy(order, user);

		// 멱등성 보장: 이미 처리된 주문이면 바로 반환
		if (order.getStatus() == OrderStatus.PAID) {
			log.info("이미 처리된 주문입니다. orderId={}", order.getId());
			return order;
		}

		if (paymentRepository.findByImpUid(paymentId).isPresent()) {
			log.warn("이미 존재하는 결제입니다: paymentId={}", paymentId);
			throw new PaymentException("이미 처리된 결제입니다.");
		}

		// 결제와 주문의 결합 검증: 포트원에 기록된 주문 번호가 잠근 주문과 같아야 한다
		paymentVerifier.assertCustomDataMatchesOrder(order, paymentId, paymentResponse);

		// 금액 검증
		if (!paymentVerifier.amountMatches(order, paymentResponse)) {
			throw new PaymentException("결제 금액이 일치하지 않습니다.");
		}

		// 포트원 상태가 PAID 라면 즉시 DB 업데이트. 모르는 상태는 "아직 완료되지 않음" 으로 보고 주문을 그대로 돌려준다.
		Optional<PaymentStatus> paymentStatus = paymentVerifier.resolveStatus(order, paymentId,
			paymentResponse);
		if (paymentStatus.isEmpty()) {
			return order;
		}

		if (paymentStatus.get() == PaymentStatus.PAID) {
			// 주문 PAID 확정, Payment 저장, 연관관계 연결, 초기 Result 생성, 완료 이벤트 발행
			paidOrderFinalizer.finalizePaid(
				order,
				paymentId,
				paymentResponse.getAmount().getTotal(),
				LocalDateTime.now()
			);

			log.info("결제 완료 API 로 결제 확정: orderId={}, paymentId={}, status={}",
				order.getId(), paymentId, order.getStatus());

			return order;  // 이제 PAID 상태로 반환
		}

		// PAID 가 아닌 경우
		log.warn("결제가 아직 완료되지 않았습니다: status={}", paymentStatus.get());
		return order;
	}

	/**
	 * 주문 소유자와 요청자를 대조한다. 탈퇴 처리로 userId 가 null 인 주문은 누구의 것도 아니므로 거부한다.
	 */
	private void assertOrderOwnedBy(Order order, User user) {
		if (order.getUserId() == null || !Objects.equals(order.getUserId(), user.getId())) {
			log.warn("권한 없는 결제 완료 시도: 요청자={}, 주문 소유자={}, orderId={}",
				user.getId(), order.getUserId(), order.getId());
			throw new AccessDeniedException("본인의 주문만 결제 완료 처리할 수 있습니다.");
		}
	}
}
