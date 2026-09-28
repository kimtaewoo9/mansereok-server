package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.DuplicatePayment;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.Finished;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
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
 *   <li>트랜잭션 밖: 포트원 결제 조회 → 결제 ID 대조. 포트원 조회(연결 3초·읽기 10초 제한)를 기다리는 시간이 행 락과 DB 커넥션
 *       점유 시간이 되지 않게 하고, 요청과 다른 결제를 받아 왔으면 주문을 잠그기 전에 거부한다.</li>
 *   <li>트랜잭션 안: 잠금 조회 → 소유자 검사 → PAID 주문이면 같은 결제인지 가리기 → 결제 중복 검사 → customData 대조
 *       → 금액 검증 → 상태 매핑 → 확정({@link PaidOrderFinalizer#finalizePaid}).</li>
 *   <li>트랜잭션 밖: 이미 다른 결제로 확정된 주문에 결제가 한 번 더 승인됐으면 그 결제를 포트원에서 취소하고 알린 뒤
 *       PaymentException(400)을 던진다({@link DuplicatePaymentCanceller}).</li>
 * </ol>
 *
 * <p>결제 ID 대조를 통과한 뒤에는 요청값이 아니라 포트원 응답의 결제 ID 로 중복을 검사하고 Payment.impUid 에 저장한다.
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. Discord 알림은
 * finalizePaid 가 발행한 이벤트를 커밋 뒤 비동기 리스너가 받아 보낸다.
 *
 * <p>포트원 응답은 트랜잭션 밖에서 받은 스냅샷이다. 그 사이 웹훅이 같은 주문을 먼저 확정했으면 잠금 조회 뒤의
 * PAID 주문 검사·결제 중복 검사가 걸러낸다.
 *
 * <p>트랜잭션 밖 구간이 DB 커넥션을 쥐지 않는 것은 spring.jpa.open-in-view 가 꺼져 있을 때다. 켜져 있으면 웹 요청은 처음 잡은
 * 커넥션을 요청이 끝날 때까지 놓지 않아, 확정 트랜잭션 뒤의 중복 결제 취소(3단계)가 커넥션을 쥔 채 포트원을 부른다. 1단계 포트원
 * 조회는 그 요청의 첫 DB 접근보다 앞서 있어 설정과 상관없이 커넥션을 쥐지 않는다.
 */
@Service
@Slf4j
public class PaymentConfirmService {

	/** 이미 결제가 끝난 주문에 결제가 한 번 더 승인됐을 때 돌려주는 안내. 자동 취소에 실패해도 같은 문구를 쓴다(운영자가 손으로 취소한다). */
	static final String DUPLICATE_PAYMENT_MESSAGE = "이미 결제가 끝난 주문입니다. 중복 결제는 자동으로 취소됩니다.";

	private final PaymentUserLookup paymentUserLookup;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final PaidOrderFinalizer paidOrderFinalizer;
	private final DuplicatePaymentCanceller duplicatePaymentCanceller;
	private final TransactionTemplate transactionTemplate;

	public PaymentConfirmService(
		PaymentUserLookup paymentUserLookup,
		OrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PortOneClient portOneClient,
		PaymentVerifier paymentVerifier,
		PaidOrderFinalizer paidOrderFinalizer,
		DuplicatePaymentCanceller duplicatePaymentCanceller,
		PlatformTransactionManager transactionManager
	) {
		this.paymentUserLookup = paymentUserLookup;
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.portOneClient = portOneClient;
		this.paymentVerifier = paymentVerifier;
		this.paidOrderFinalizer = paidOrderFinalizer;
		this.duplicatePaymentCanceller = duplicatePaymentCanceller;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 결제 완료 검증·확정. 클라이언트가 보낸 paymentId 와 merchantUid 를 그대로 믿지 않는다.
	 *
	 * @throws AccessDeniedException 요청자가 주문 소유자가 아닐 때 (403)
	 * @throws PaymentException      결제 ID 불일치 · 사용자 없음 · 주문 없음 · 결제 중복 · customData 없음 · 주문 번호 불일치
	 *                               · 금액 불일치 · 이미 다른 결제로 확정된 주문에 한 번 더 승인된 결제(자동 취소 뒤) (400)
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 포트원 일시 장애 (503, 트랜잭션 시작 전)
	 */
	public Order complete(String username, PaymentCompleteRequest request) {
		log.info("결제 완료 요청 및 검증: paymentId={}, merchantUid={}", request.getPaymentId(), request.getMerchantUid());

		// 1. 트랜잭션 밖: 포트원 API 조회를 통한 2차 검증 자료. 요청과 다른 결제를 받아 왔으면 잠그기 전에 거부한다.
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(request.getPaymentId());
		paymentVerifier.assertPaymentIdMatches(request.getPaymentId(), paymentResponse);

		// 2. 트랜잭션 안: 잠금·검증·확정
		ConfirmOutcome outcome = transactionTemplate.execute(
			status -> confirm(username, request, paymentResponse));

		// 3. 트랜잭션 밖: 중복 결제 취소. 잠금과 트랜잭션을 놓은 뒤 포트원을 부른다.
		//    open-in-view 가 켜져 있으면 DB 커넥션은 요청이 끝날 때까지 잡혀 있다(포트원 읽기 시간 제한 안에서, 클래스 주석 참고).
		return switch (outcome) {
			case Finished finished -> finished.order();
			case DuplicatePayment duplicate -> {
				duplicatePaymentCanceller.cancel(duplicate);
				throw new PaymentException(DUPLICATE_PAYMENT_MESSAGE);
			}
		};
	}

	private ConfirmOutcome confirm(String username, PaymentCompleteRequest request,
		PortOnePaymentResponse paymentResponse) {
		// complete 에서 요청값과 대조를 마친 포트원 결제 ID. 중복 검사와 Payment.impUid 저장에 쓴다.
		String paymentId = paymentResponse.getId();

		User user = paymentUserLookup.getByUsername(username);

		// 비관적 락으로 주문 조회
		Order order = orderRepository.findByMerchantUidWithLock(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		// 소유자 대조. 멱등 반환보다 먼저 해서 타인의 PAID 주문 정보도 새지 않게 한다.
		assertOrderOwnedBy(order, user);

		// 이미 결제가 끝난 주문: 같은 결제의 재요청이면 그대로 돌려주고, 한 번 더 승인된 다른 결제면 트랜잭션 밖에서 취소한다
		if (order.getStatus() == OrderStatus.PAID) {
			return duplicatePaymentCanceller.classifyPaymentOnPaidOrder(order, paymentId, paymentResponse);
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
			return new Finished(order);
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

			return new Finished(order);  // 이제 PAID 상태로 반환
		}

		// PAID 가 아닌 경우
		log.warn("결제가 아직 완료되지 않았습니다: status={}", paymentStatus.get());
		return new Finished(order);
	}

	/**
	 * 주문 소유자와 요청자를 대조한다. 탈퇴 처리로 userId 가 null 인 주문은 누구의 것도 아니므로 거부한다.
	 */
	private void assertOrderOwnedBy(Order order, User user) {
		if (!order.isOwnedBy(user.getId())) {
			log.warn("권한 없는 결제 완료 시도: 요청자={}, 주문 소유자={}, orderId={}",
				user.getId(), order.getUserId(), order.getId());
			throw new AccessDeniedException("본인의 주문만 결제 완료 처리할 수 있습니다.");
		}
	}
}
