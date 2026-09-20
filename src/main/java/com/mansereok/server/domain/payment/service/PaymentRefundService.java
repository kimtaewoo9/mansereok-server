package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.util.Objects;
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
 *   <li>트랜잭션 밖: 포트원 취소 API. 실패하면 트랜잭션 C 로 PAID 로 되돌리고 예외를 다시 던진다.</li>
 *   <li>트랜잭션 B: Payment·Order 를 CANCELLED 로 확정하고 초기 Result 를 지우고 할인을 복구한다.
 *       여기서 실패하면 CANCEL_REQUESTED 로 남겨 수동 확인 대상으로 둔다(포트원 환불은 되돌릴 수 없다).</li>
 * </ol>
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. 같은 빈 안의
 * 메서드 호출은 프록시를 타지 않아 {@code @Transactional} 로는 단계를 나눌 수 없기 때문이다. 전파 속성은
 * {@code REQUIRES_NEW} 로 고정해, 바깥 트랜잭션 안에서 호출되더라도 세 단계가 한 트랜잭션으로 합쳐지지 않게 한다.
 *
 * <p>잠금 순서는 A·B 모두 결제 행 → 주문 행이다. 순서가 다르면 포트원 취소 직후 도착한 두 번째 환불 요청과
 * 데드락이 날 수 있고, B 가 희생되면 포트원 환불은 끝났는데 DB 는 CANCEL_REQUESTED 로 남는다.
 */
@Service
@Slf4j
public class PaymentRefundService {

	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final ResultService resultService;
	private final OrderDiscountRestorer orderDiscountRestorer;
	private final PortOneClient portOneClient;
	private final TransactionTemplate transactionTemplate;

	public PaymentRefundService(
		UserRepository userRepository,
		PaymentRepository paymentRepository,
		OrderRepository orderRepository,
		ResultService resultService,
		OrderDiscountRestorer orderDiscountRestorer,
		PortOneClient portOneClient,
		PlatformTransactionManager transactionManager
	) {
		this.userRepository = userRepository;
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
	 * @throws PaymentException 검증 실패(결제 없음 · 타인 결제 · 무료 결제 · 상태 부적합 · 해석 진행됨)
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 포트원 일시 장애 (PAID 로 되돌린 뒤 전파)
	 */
	public void cancel(String username, String impUid, String reason) {
		// (1) 트랜잭션 A: 검증 + CANCEL_REQUESTED 커밋
		Long paymentPkId = transactionTemplate.execute(
			status -> markCancelRequested(username, impUid));

		// (2) 트랜잭션 밖: 포트원 취소. 실패하면 C 로 되돌리고 다시 던진다.
		try {
			portOneClient.cancelPayment(impUid, reason);
		} catch (RuntimeException e) {
			log.warn("포트원 취소 실패로 취소 요청을 되돌립니다: impUid={}, paymentPkId={}, reason={}",
				impUid, paymentPkId, e.getMessage());
			revertCancelRequest(paymentPkId, impUid);
			throw e;
		}

		// (3) 트랜잭션 B: DB 확정. 실패하면 CANCEL_REQUESTED 로 남겨 수동 확인 대상으로 둔다.
		try {
			transactionTemplate.executeWithoutResult(status -> finalizeCancelled(impUid));
		} catch (RuntimeException e) {
			log.error("포트원 취소는 성공했지만 DB 확정에 실패해 CANCEL_REQUESTED 로 남습니다. 수동 확인 필요: "
				+ "username={}, impUid={}, paymentPkId={}", username, impUid, paymentPkId, e);
			throw e;
		}

		log.info("사용자 환불 완료: username={}, impUid={}, reason={}", username, impUid, reason);
	}

	/**
	 * 트랜잭션 A. 결제 행과 주문 행을 잠근 채 검증하고 CANCEL_REQUESTED 로 바꾼다.
	 *
	 * <p>결제 행을 먼저 잠그는 이유는 뒤진 동시 요청이 앞선 커밋(CANCEL_REQUESTED)을 읽게 하기 위해서다. 주문 행을
	 * 먼저 잠그면 그 전에 읽어 둔 Payment 가 영속성 컨텍스트에 남아 PAID 로 보인다. 잠금 순서(결제 → 주문)는
	 * 결제 완료 경로(주문만 잠금)와 충돌하지 않으며, B 도 같은 순서로 잠근다.
	 *
	 * @return 취소 요청을 기록한 Payment 의 PK
	 */
	private Long markCancelRequested(String username, String impUid) {
		// 1. 사용자 조회
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		// 2. 결제 조회 (행 잠금)
		Payment payment = paymentRepository.findByImpUidWithLock(impUid)
			.orElseThrow(() -> new PaymentException("결제 정보를 찾을 수 없습니다."));

		// 3. 소유자 검사
		if (!Objects.equals(payment.getUserId(), user.getId())) {
			throw new PaymentException("본인의 결제 건만 취소할 수 있습니다.");
		}

		// 4. 무료 결제(0원) 환불 시도 원천 차단
		if (payment.getAmount() == 0
			|| payment.getImpUid().startsWith(MerchantUidGenerator.FREE_PREFIX)) {
			throw new PaymentException("무료 이벤트 결제는 환불/취소 대상이 아닙니다.");
		}

		// 5. 주문 잠금
		Order order = orderRepository.findByMerchantUidWithLock(payment.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));

		// 6. 결제 상태 검사
		if (payment.getStatus() == PaymentStatus.CANCELLED) {
			throw new PaymentException("이미 취소된 결제입니다.");
		}
		if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
			throw new PaymentException("취소가 진행 중입니다.");
		}
		if (payment.getStatus() != PaymentStatus.PAID) {
			throw new PaymentException("결제 완료 상태가 아니라 취소할 수 없습니다.");
		}

		// 7. 주문 전이 사전 검사. 포트원 환불은 되돌릴 수 없으므로 B 에서 던질 상황이면 여기서 먼저 거른다.
		if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
			throw new PaymentException("취소할 수 없는 주문 상태입니다.");
		}

		// 8. 결과 상태 검증 (핵심: 사주 정보를 입력하기 전인가?). 일반 사주(Result)와 궁합(CompatibilityResult) 모두 본다.
		Optional<ResultStatus> resultStatus = resultService.findStatusByPaymentId(payment.getId());
		if (resultStatus.isEmpty()) {
			throw new PaymentException("해당 결제에 대한 결과 정보를 찾을 수 없습니다.");
		}
		if (resultStatus.get() != ResultStatus.INPUT_REQUIRED) {
			throw new PaymentException("이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.");
		}

		// 9. 취소 요청 기록 (PAID 에서만 허용)
		payment.markCancelRequested();
		log.info("환불 취소 요청 기록: username={}, impUid={}, paymentPkId={}", username, impUid,
			payment.getId());
		return payment.getId();
	}

	/**
	 * 트랜잭션 C. 포트원 취소가 실패했을 때 CANCEL_REQUESTED 를 PAID 로 되돌린다.
	 *
	 * <p>되돌리기 자체가 실패하면 로그만 남긴다. 호출자는 원래 예외를 그대로 던지고, 결제는 CANCEL_REQUESTED 로 남아
	 * 수동 확인 대상이 된다.
	 */
	private void revertCancelRequest(Long paymentPkId, String impUid) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				Payment payment = paymentRepository.findById(paymentPkId)
					.orElseThrow(() -> new PaymentException("결제 정보를 찾을 수 없습니다."));
				payment.revertCancelRequest();
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
		Order order = orderRepository.findByMerchantUidWithLock(payment.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));

		// 1. Payment 상태 변경 (CANCEL_REQUESTED 또는 PAID 에서 허용)
		payment.markCancelled();

		// 2. Order 상태 변경
		order.markCancelled();

		// 3. 초기 Result 삭제 (정보 입력 전이므로 삭제). 일반 사주·궁합 중 존재하는 쪽을 지운다.
		resultService.deleteInitialResult(payment.getId());

		// 4. 쿠폰 또는 할인 코드 복구 (규칙은 OrderDiscountRestorer 가 소유)
		orderDiscountRestorer.restore(order);
	}
}
