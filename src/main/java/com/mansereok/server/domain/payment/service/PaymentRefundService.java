package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 직접 환불. 돈(포트원)과 DB 가 어긋나지 않도록 세 단계로 나눈다.
 *
 * <ol>
 *   <li>트랜잭션 A: 검증 후 Payment 를 CANCEL_REQUESTED 로 기록하고 커밋한다. 이 흔적이 동시 환불을 막고,
 *       포트원 호출 도중 프로세스가 죽어도 수동 확인 대상이 남는다.</li>
 *   <li>트랜잭션 밖: 포트원 취소 API. 실패하면 포트원에서 결제를 다시 조회해, 취소돼 있으면 B 로 가고, 거절이 확인되면
 *       트랜잭션 C 로 PAID 로 되돌리고, 알 수 없으면 CANCEL_REQUESTED 로 남긴다({@link #cancel} 참고). 이 단계가 DB 커넥션을
 *       쥐지 않는 것은 spring.jpa.open-in-view 가 꺼져 있을 때다(아래 참고).</li>
 *   <li>트랜잭션 B: Payment·Order 를 CANCELLED 로 확정하고 초기 Result 를 지우고 할인을 복구한다.
 *       여기서 실패하면 CANCEL_REQUESTED 로 남겨 수동 확인 대상으로 둔다(포트원 환불은 되돌릴 수 없다).</li>
 * </ol>
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. 같은 빈 안의
 * 메서드 호출은 프록시를 타지 않아 {@code @Transactional} 로는 단계를 나눌 수 없기 때문이다. 전파 속성은
 * {@code REQUIRES_NEW} 로 고정해, 바깥 트랜잭션 안에서 호출되더라도 세 단계가 한 트랜잭션으로 합쳐지지 않게 한다.
 *
 * <p>트랜잭션을 나눠도 커넥션까지 놓으려면 spring.jpa.open-in-view 가 꺼져 있어야 한다. 켜져 있으면 웹 요청은 A 에서 잡은
 * 커넥션을 요청이 끝날 때까지 놓지 않는다. 그러면 행 잠금은 풀렸어도 포트원 취소를 기다리는 동안(연결 3초·읽기 10초까지)
 * 커넥션 풀의 한 자리를 차지해, 포트원이 느릴 때 환불 요청이 몰리면 풀이 바닥난다.
 *
 * <p>잠금 순서는 A·B 모두 결제 행 → 주문 행 → 결과 행이다. 순서가 다르면 포트원 취소 직후 도착한 두 번째 환불 요청과
 * 데드락이 날 수 있고, B 가 희생되면 포트원 환불은 끝났는데 DB 는 CANCEL_REQUESTED 로 남는다. 해석 시작
 * (PaymentEntitlementService#startInterpretation)도 결제 행을 먼저 잠그므로 환불 A 와 해석 시작은 결제 행에서 줄을 선다.
 * 결과 행은 일반 사주(results)와 궁합(compatibility_results) 중 행이 있는 표에서만 잠근다. 행이 없는 표를 잠그면 간격 잠금이
 * 걸려 다른 결제의 결제 확정(초기 결과 INSERT)이 기다리고, 그 확정이 할인 코드 행을 먼저 쥐었다면 B 와 교착이 난다.
 */
@Service
@Slf4j
public class PaymentRefundService {

	/** 포트원 조회 API 의 전액 취소 상태. 부분 취소(PARTIAL_CANCELLED)는 포함하지 않는다. */
	private static final String PORTONE_CANCELLED = "CANCELLED";
	/** 포트원 조회 API 의 결제 완료 상태. 이 값일 때만 "취소되지 않았다"고 본다. */
	private static final String PORTONE_PAID = "PAID";

	private final PaymentUserLookup paymentUserLookup;
	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final ResultService resultService;
	private final OrderDiscountRestorer orderDiscountRestorer;
	private final PortOneClient portOneClient;
	private final TransactionTemplate transactionTemplate;

	public PaymentRefundService(
		PaymentUserLookup paymentUserLookup,
		PaymentRepository paymentRepository,
		OrderRepository orderRepository,
		ResultService resultService,
		OrderDiscountRestorer orderDiscountRestorer,
		PortOneClient portOneClient,
		PlatformTransactionManager transactionManager
	) {
		this.paymentUserLookup = paymentUserLookup;
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
		this.resultService = resultService;
		this.orderDiscountRestorer = orderDiscountRestorer;
		this.portOneClient = portOneClient;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 바깥 트랜잭션에 합류하면 A 의 CANCEL_REQUESTED 커밋이 포트원 호출 전에 일어나지 않고 B 실패 시 A 까지 롤백된다.
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 사용자 직접 환불 (ResultStatus 가 INPUT_REQUIRED 일 때만 가능).
	 *
	 * <p>포트원 취소가 실패로 보여도 곧바로 PAID 로 되돌리지 않는다. 포트원은 클라이언트가 연결을 끊어도 진행 중인 요청을 멈추지
	 * 않으므로, 읽기 시간 초과나 5xx 뒤에도 취소가 반영돼 있을 수 있다. 되돌렸다가 돈만 환불된 결제가 PAID 로 남으면 환불받은
	 * 사용자가 해석을 시작할 수 있고, 다시 환불을 눌러도 포트원이 "이미 취소됨"(409 PAYMENT_ALREADY_CANCELLED)을 돌려줘
	 * 또 되돌리므로 스스로 회복되지 않는다. 그래서 실패하면 포트원에서 결제를 다시 조회해 세 갈래로 나눈다.
	 * <ul>
	 *   <li>포트원에서 전액 취소돼 있다 → 취소는 끝난 것이므로 B(DB 확정)로 간다. 응답만 늦게 온 경우와 "이미 취소됨" 거절이 여기 든다.</li>
	 *   <li>취소돼 있지 않고 포트원이 요청을 거절했다(4xx) → 취소는 반영되지 않았으므로 C 로 PAID 로 되돌린다.</li>
	 *   <li>그 밖(응답을 받지 못했거나 재조회도 실패) → 취소가 반영됐는지 알 수 없으므로 CANCEL_REQUESTED 로 남긴다. 해석 시작은
	 *       PAID 만 허용하므로 그동안 막히고, 대사가 CANCEL_REQUESTED_STALE 로 잡는다. 사용자가 다시 환불을 누르면 아래 재개
	 *       경로가 포트원 상태를 다시 본다.</li>
	 * </ul>
	 *
	 * <p>재개: 이미 CANCEL_REQUESTED 인 결제의 환불 요청은 포트원을 다시 조회해, 전액 취소돼 있으면 취소 API 없이 B 만 한다.
	 * 취소돼 있지 않으면 전처럼 "취소가 진행 중입니다." 로 거부한다(앞선 요청의 포트원 호출이 아직 진행 중일 수 있어 취소를 다시
	 * 보내지 않는다).
	 *
	 * @throws PaymentException 검증 실패(결제 없음 · 타인 결제 · 무료 결제 · 상태 부적합 · 해석 진행됨), 포트원이 취소를 거절함
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 포트원 일시 장애. 취소가 반영됐는지 확인하지
	 *                                                                           못하면 CANCEL_REQUESTED 로 남긴 채 전파한다
	 */
	public void cancel(String username, String impUid, String reason) {
		// (1) 트랜잭션 A: 검증 + CANCEL_REQUESTED 커밋. 이미 CANCEL_REQUESTED 면 재개 대상으로 돌려준다.
		CancelRequestOutcome outcome = transactionTemplate.execute(
			status -> markCancelRequested(username, impUid));
		Long paymentPkId = outcome.paymentPkId();

		if (outcome.alreadyRequested()) {
			resumeIfCancelledAtPortOne(paymentPkId, impUid);
			return;
		}

		// (2) 트랜잭션 밖: 포트원 취소. 실패하면 포트원 상태를 다시 보고 되돌릴지, 확정할지, 남길지 정한다.
		try {
			portOneClient.cancelPayment(impUid, reason);
		} catch (RuntimeException e) {
			handleCancelFailure(paymentPkId, impUid, e);
		}

		// (3) 트랜잭션 B: DB 확정. 실패하면 CANCEL_REQUESTED 로 남겨 수동 확인 대상으로 둔다.
		finalizeCancelledOrLeaveRequested(paymentPkId, impUid);
		log.info("사용자 환불 완료: impUid={}, paymentPkId={}, reason={}", impUid, paymentPkId, reason);
	}

	/**
	 * 포트원 취소 호출이 실패했을 때. 포트원에서 취소돼 있으면 그대로 돌아가 B 로 이어지고, 아니면 예외를 던진다.
	 *
	 * @throws RuntimeException 넘겨받은 실패를 그대로 던진다. 취소되지 않은 것이 확인된 거절이면 PAID 로 되돌린 뒤 던지고,
	 *                          확인하지 못했으면 CANCEL_REQUESTED 로 남긴 채 던진다
	 */
	private void handleCancelFailure(Long paymentPkId, String impUid, RuntimeException failure) {
		PortOneCancellation cancellation = lookupCancellationAtPortOne(impUid);
		if (cancellation == PortOneCancellation.CANCELLED) {
			log.warn("포트원 취소 응답은 실패였지만 포트원에서 취소돼 있어 DB 확정으로 진행합니다: impUid={}, paymentPkId={}, cause={}",
				impUid, paymentPkId, failure.getMessage());
			return;
		}

		// 거절로 보는 것은 포트원이 4xx 로 답한 경우(PaymentException)뿐이다. 그 밖의 예외는 취소가 반영됐는지 알 수 없다.
		boolean rejectedByPortOne = failure instanceof PaymentException;
		if (cancellation == PortOneCancellation.NOT_CANCELLED && rejectedByPortOne) {
			log.warn("포트원이 취소를 거절했고 취소되지 않은 것을 확인해 취소 요청을 되돌립니다: impUid={}, paymentPkId={}, reason={}",
				impUid, paymentPkId, failure.getMessage());
			revertCancelRequest(paymentPkId, impUid);
			throw failure;
		}

		log.error("포트원 취소가 반영됐는지 확인하지 못해 CANCEL_REQUESTED 로 남깁니다. 다음 환불 요청이나 대사에서 다시 봅니다: "
			+ "impUid={}, paymentPkId={}, portOneState={}", impUid, paymentPkId, cancellation, failure);
		throw failure;
	}

	/**
	 * CANCEL_REQUESTED 로 남아 있는 결제의 환불 요청. 앞선 요청이 포트원 취소 뒤 끊겼다면 포트원에는 취소가 돼 있으므로 B 만
	 * 다시 한다. 취소돼 있지 않으면 앞선 요청이 아직 진행 중일 수 있어 전처럼 거부한다.
	 */
	private void resumeIfCancelledAtPortOne(Long paymentPkId, String impUid) {
		if (lookupCancellationAtPortOne(impUid) != PortOneCancellation.CANCELLED) {
			throw new PaymentException("취소가 진행 중입니다.");
		}
		log.warn("CANCEL_REQUESTED 로 남아 있던 결제가 포트원에서 취소돼 있어 DB 확정만 다시 합니다: impUid={}, paymentPkId={}",
			impUid, paymentPkId);
		finalizeCancelledOrLeaveRequested(paymentPkId, impUid);
		log.info("사용자 환불 완료(재개): impUid={}, paymentPkId={}", impUid, paymentPkId);
	}

	private void finalizeCancelledOrLeaveRequested(Long paymentPkId, String impUid) {
		try {
			transactionTemplate.executeWithoutResult(status -> finalizeCancelled(impUid));
		} catch (RuntimeException e) {
			log.error("포트원 취소는 성공했지만 DB 확정에 실패해 CANCEL_REQUESTED 로 남습니다. 수동 확인 필요: "
				+ "impUid={}, paymentPkId={}", impUid, paymentPkId, e);
			throw e;
		}
	}

	/**
	 * 포트원에서 결제가 전액 취소된 상태인지 다시 조회한다. 원문 상태가 CANCELLED 면 취소됨, PAID 면 취소되지 않음, 그 밖은 알 수
	 * 없음이다. 부분 취소(PARTIAL_CANCELLED)는 남은 금액이 청구된 상태라 취소로도, 취소되지 않음으로도 보지 않는다
	 * ({@link PaymentStatus#fromPortOneStatus} 는 부분 취소를 CANCELLED 로 접으므로 여기서는 원문 상태를 본다). 조회에 실패해도
	 * 알 수 없음이다.
	 */
	private PortOneCancellation lookupCancellationAtPortOne(String impUid) {
		try {
			Optional<String> status = portOneClient.findPayment(impUid).map(PortOnePaymentResponse::getStatus);
			if (status.isEmpty()) {
				return PortOneCancellation.UNKNOWN;
			}
			return switch (status.get().trim().toUpperCase(Locale.ROOT)) {
				case PORTONE_CANCELLED -> PortOneCancellation.CANCELLED;
				case PORTONE_PAID -> PortOneCancellation.NOT_CANCELLED;
				default -> PortOneCancellation.UNKNOWN;
			};
		} catch (RuntimeException e) {
			log.warn("포트원 취소 실패 뒤 결제 상태를 다시 조회하지 못했습니다: impUid={}", impUid, e);
			return PortOneCancellation.UNKNOWN;
		}
	}

	/** 포트원 재조회로 본 취소 여부. */
	private enum PortOneCancellation {
		CANCELLED, NOT_CANCELLED, UNKNOWN
	}

	/**
	 * 트랜잭션 A 의 결과. {@code alreadyRequested} 가 참이면 이 요청이 기록한 것이 아니라 이미 CANCEL_REQUESTED 였다는 뜻이다.
	 */
	private record CancelRequestOutcome(Long paymentPkId, boolean alreadyRequested) {
	}

	/**
	 * 트랜잭션 A. 결제 행과 주문 행을 잠근 채 검증하고 CANCEL_REQUESTED 로 바꾼다.
	 *
	 * <p>결제 행을 먼저 잠그는 이유는 뒤진 동시 요청이 앞선 커밋(CANCEL_REQUESTED)을 읽게 하기 위해서다. 주문 행을
	 * 먼저 잠그면 그 전에 읽어 둔 Payment 가 영속성 컨텍스트에 남아 PAID 로 보인다. 잠금 순서(결제 → 주문)는
	 * 결제 완료 경로(주문만 잠금)와 충돌하지 않으며, B 도 같은 순서로 잠근다.
	 *
	 * @return 취소 요청을 기록한 Payment 의 PK. 이미 CANCEL_REQUESTED 였으면 기록하지 않고 재개 대상으로 표시한다
	 */
	private CancelRequestOutcome markCancelRequested(String username, String impUid) {
		// 1. 사용자 조회
		User user = paymentUserLookup.getByUsername(username);

		// 2. 결제 조회 (행 잠금)
		Payment payment = paymentRepository.findByImpUidWithLock(impUid)
			.orElseThrow(() -> new PaymentException("결제 정보를 찾을 수 없습니다."));

		// 3. 소유자 검사. 탈퇴 처리로 userId 가 빈 결제는 누구의 것도 아니다.
		if (!payment.isOwnedBy(user.getId())) {
			throw new PaymentException("본인의 결제 건만 취소할 수 있습니다.");
		}

		// 4. 무료 결제(0원 또는 free_ 결제 번호) 환불 시도 원천 차단
		if (payment.isFree()) {
			throw new PaymentException("무료 이벤트 결제는 환불/취소 대상이 아닙니다.");
		}

		// 5. 주문 잠금
		Order order = orderRepository.findByMerchantUidWithLock(payment.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));

		// 6. 결제 상태 검사
		if (payment.getStatus() == PaymentStatus.CANCELLED) {
			throw new PaymentException("이미 취소된 결제입니다.");
		}
		if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED && order.getStatus() == OrderStatus.EXPIRED) {
			// 만료된 주문의 늦은 결제를 자동 취소하는 중이다(DuplicatePaymentCanceller.rejectLatePayment). 주문은 CANCELLED 로 갈 수 없다.
			throw new PaymentException("자동으로 취소되는 중인 결제입니다.");
		}
		if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
			// 앞선 환불이 포트원 취소 뒤 끊겼을 수 있다. 잠금을 쥔 채 포트원을 부르지 않도록 트랜잭션 밖에서 다시 본다.
			return new CancelRequestOutcome(payment.getId(), true);
		}
		if (payment.getStatus() != PaymentStatus.PAID) {
			throw new PaymentException("결제 완료 상태가 아니라 취소할 수 없습니다.");
		}

		// 7. 주문 전이 사전 검사. 포트원 환불은 되돌릴 수 없으므로 B 에서 던질 상황이면 여기서 먼저 거른다.
		if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
			throw new PaymentException("취소할 수 없는 주문 상태입니다.");
		}

		// 8. 결과 상태 검증 (핵심: 사주 정보를 입력하기 전인가?). 일반 사주(Result)와 궁합(CompatibilityResult) 모두 본다.
		//    결과 행을 잠가 읽어, 결제 행 잠금을 기다리는 동안 해석 시작이 커밋한 PROCESSING 도 본다.
		Optional<ResultStatus> resultStatus = resultService.findStatusByPaymentId(payment.getId());
		if (resultStatus.isEmpty()) {
			throw new PaymentException("해당 결제에 대한 결과 정보를 찾을 수 없습니다.");
		}
		// 위에서 PAID·무료 아님을 확인했으므로 여기서 남는 조건은 정보 입력 전(INPUT_REQUIRED)이다. 결제 목록의 환불 가능
		// 표시(PaymentResponseDto)와 같은 판정을 거쳐, 판정에 조건이 늘면 환불 API 도 함께 막는다.
		if (!payment.isRefundable(resultStatus.get())) {
			throw new PaymentException("이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.");
		}

		// 9. 취소 요청 기록 (PAID 에서만 허용)
		payment.markCancelRequested();
		log.info("환불 취소 요청 기록: userId={}, impUid={}, paymentPkId={}", user.getId(), impUid,
			payment.getId());
		return new CancelRequestOutcome(payment.getId(), false);
	}

	/**
	 * 트랜잭션 C. 포트원 취소가 거절됐을 때 CANCEL_REQUESTED 를 PAID 로 되돌린다.
	 *
	 * <p>조건부 UPDATE 한 문장으로 되돌린다. 엔티티를 읽어 바꾸면 그 사이 다른 요청(재개 경로의 B)이 커밋한 CANCELLED 를
	 * 보지 못하고 PAID 로 덮어쓸 수 있다. 조건부 UPDATE 는 B 의 행 잠금 뒤에 실행되어 B 가 확정했으면 0 건으로 끝난다.
	 *
	 * <p>되돌리기 자체가 실패하면 로그만 남긴다. 호출자는 원래 예외를 그대로 던지고, 결제는 CANCEL_REQUESTED 로 남아
	 * 수동 확인 대상이 된다.
	 */
	private void revertCancelRequest(Long paymentPkId, String impUid) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				int reverted = paymentRepository.updateStatusIf(paymentPkId, PaymentStatus.CANCEL_REQUESTED,
					PaymentStatus.PAID);
				if (reverted == 0) {
					log.warn("취소 요청을 되돌리려 했지만 결제가 더 이상 CANCEL_REQUESTED 가 아니라 그대로 둡니다: impUid={}, paymentPkId={}",
						impUid, paymentPkId);
				}
			});
		} catch (RuntimeException e) {
			log.error("취소 요청 되돌리기에 실패해 CANCEL_REQUESTED 로 남습니다. 수동 확인 필요: impUid={}, paymentPkId={}",
				impUid, paymentPkId, e);
		}
	}

	/**
	 * 트랜잭션 B. 포트원 취소가 끝난 뒤 DB 를 확정한다. A 에서 검증을 마쳤으므로 여기서는 재조회와 전이만 한다.
	 *
	 * <p>A 와 같은 순서(결제 → 주문)로 잠근다. 결제 행을 잠그지 않고 주문 행만 잠그면, 그 사이 들어온 두 번째 환불
	 * 요청의 A(결제 잠금 후 주문 대기)와 여기서의 payments UPDATE(결제 잠금 대기)가 서로를 기다려 데드락이 된다.
	 * OSIV 환경에서 {@code findById} 는 영속성 컨텍스트에서 바로 돌려줘 결제 행을 전혀 잠그지 않으므로 잠금 조회를 쓴다.
	 */
	private void finalizeCancelled(String impUid) {
		Payment payment = paymentRepository.findByImpUidWithLock(impUid)
			.orElseThrow(() -> new PaymentException("결제 정보를 찾을 수 없습니다."));
		// 재개 경로가 겹치면 먼저 잠근 쪽이 확정하고 뒤쪽은 여기서 CANCELLED 를 본다. 두 번 확정하지 않는다.
		// 엔티티가 아니라 DB 를 본다. open-in-view 에서는 위 잠금 조회가 A 에서 읽어 둔 엔티티(CANCEL_REQUESTED)를 그대로 돌려준다.
		if (paymentRepository.findStatusById(payment.getId()) == PaymentStatus.CANCELLED) {
			log.info("이미 CANCELLED 로 확정된 결제라 DB 확정을 건너뜁니다: impUid={}, paymentPkId={}", impUid, payment.getId());
			return;
		}
		Order order = orderRepository.findByMerchantUidWithLock(payment.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));

		// 1. Payment 상태 변경 (CANCEL_REQUESTED 또는 PAID 에서 허용)
		payment.markCancelled();

		// 2. Order 상태 변경
		order.markCancelled();

		// 3. 초기 Result 삭제 (정보 입력 전이므로 삭제). 일반 사주·궁합 중 존재하는 쪽을 지운다.
		//    조건부 DELETE 로 DB 현재 상태를 본다. open-in-view 로 A 에서 읽어 둔 결과 엔티티의 낡은 상태를 보지 않는다.
		resultService.deleteInitialResult(payment.getId());

		// 4. 쿠폰 또는 할인 코드 복구 (규칙은 OrderDiscountRestorer 가 소유)
		orderDiscountRestorer.restore(order);
	}
}
