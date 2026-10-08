package com.mansereok.server.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.dto.response.PortoneWebhookDto;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.DuplicatePayment;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.Finished;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.LatePaymentWithoutDiscount;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포트원 웹훅 처리. 서명 검증은 컨트롤러가 마친 뒤 호출한다.
 *
 * <p>순서: 페이로드 파싱 → Paid 이벤트 필터 → 포트원 재조회 → 결제 ID 대조 → (트랜잭션) 주문 잠금과 멱등 검사 → 금액 검증 → 확정
 * → (트랜잭션 밖) 중복 결제 취소. 결제 ID 가 다르면 받아 온 결제를 믿을 수 없으므로 어느 주문도 건드리지 않고
 * PaymentException(400)을 던진다. 대조를 통과한 뒤에는 웹훅 본문의 값이 아니라 포트원 응답의 결제 ID 로 중복을 검사하고
 * Payment.impUid 에 저장한다.
 *
 * <p>금액 불일치와 결제 실패 상태는 재전송으로 해결되지 않는 최종 실패라서 예외 없이 주문을 FAILED 로 기록하고
 * 정상 반환한다. 예외를 던지면 같은 트랜잭션의 FAILED 저장이 롤백되고 포트원이 재시도를 반복하므로, 정상 반환으로
 * FAILED 를 커밋하고 포트원에는 200 을 돌려준다. 포트원 일시 장애(PortOneUnavailableException, 503)와
 * DB 장애(500)는 그대로 전파해 포트원이 재시도하게 둔다.
 *
 * <p>이미 다른 결제로 확정된 주문에 결제가 한 번 더 승인됐으면 트랜잭션이 끝난 뒤 그 결제를 포트원에서 취소하고 알린 다음 정상
 * 반환한다({@link DuplicatePaymentCanceller}). 재전송해도 결과가 같으므로 포트원에는 200 을 돌려준다.
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. 포트원 조회와 중복 결제
 * 취소는 트랜잭션 밖에서, 주문 잠금부터 FAILED 저장·할인 복구·확정까지는 한 트랜잭션에서 한다.
 */
@Service
@Slf4j
public class PaymentWebhookService {

	private final ObjectMapper objectMapper;
	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PaidOrderFinalizer paidOrderFinalizer;
	private final OrderDiscountRestorer orderDiscountRestorer;
	private final DuplicatePaymentCanceller duplicatePaymentCanceller;
	private final TransactionTemplate transactionTemplate;

	public PaymentWebhookService(
		ObjectMapper objectMapper,
		PortOneClient portOneClient,
		PaymentVerifier paymentVerifier,
		OrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PaidOrderFinalizer paidOrderFinalizer,
		OrderDiscountRestorer orderDiscountRestorer,
		DuplicatePaymentCanceller duplicatePaymentCanceller,
		PlatformTransactionManager transactionManager
	) {
		this.objectMapper = objectMapper;
		this.portOneClient = portOneClient;
		this.paymentVerifier = paymentVerifier;
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.paidOrderFinalizer = paidOrderFinalizer;
		this.orderDiscountRestorer = orderDiscountRestorer;
		this.duplicatePaymentCanceller = duplicatePaymentCanceller;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public void processWebhook(String body) {
		PortoneWebhookDto webhook = parseWebhookBody(body);
		String paymentId = webhook.getPaymentId();
		log.info("웹훅 수신: status={}, paymentId={}", webhook.getStatus(), paymentId);

		// 이 서버는 status 가 있는 예전 형식만 읽는다. 포트원 콘솔에서 웹훅 버전을 바꾸면 status 없이 type 만 온다.
		// 200 을 받은 포트원은 재전송하지 않으므로, 결제가 조용히 무시되지 않게 warn 으로 남긴다.
		if (webhook.getStatus() == null && webhook.getType() != null) {
			log.warn("status 없이 type 만 있는 웹훅 본문이라 처리하지 않습니다. 포트원 콘솔의 웹훅 버전을 확인하세요: type={}",
				webhook.getType());
			return;
		}

		// Ready 상태는 결제 완료가 아님 (가상계좌 발급, 결제 시작 등)
		if (!"Paid".equals(webhook.getStatus())) {
			log.info("결제 완료 이벤트가 아님: status={}, paymentId={}", webhook.getStatus(), paymentId);
			return;
		}

		// 트랜잭션 밖: 웹훅 본문은 신뢰하지 않고 포트원 API 로 재조회한다. 받아 온 결제가 웹훅이 가리킨 결제인지부터 확인한다.
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(paymentId);
		paymentVerifier.assertPaymentIdMatches(paymentId, paymentResponse);
		String verifiedPaymentId = paymentResponse.getId();
		String merchantUid = paymentVerifier.merchantUidFromCustomData(paymentResponse);

		// 트랜잭션 안: 잠금·검증·확정
		ConfirmOutcome outcome = transactionTemplate.execute(
			status -> confirmUnderOrderLock(merchantUid, verifiedPaymentId, paymentResponse));

		// 트랜잭션 밖: 중복 결제 취소. 취소에 실패해도 예외 없이 끝내 포트원에는 200 을 돌려준다.
		switch (outcome) {
			case Finished finished -> {
				// 잠금 안에서 할 일을 모두 마쳤다
			}
			case DuplicatePayment duplicate -> duplicatePaymentCanceller.cancel(duplicate);
			case LatePaymentWithoutDiscount late -> duplicatePaymentCanceller.cancelLatePayment(late);
		}
	}

	private PortoneWebhookDto parseWebhookBody(String body) {
		try {
			return objectMapper.readValue(body, PortoneWebhookDto.class);
		} catch (JsonProcessingException e) {
			log.error("웹훅 페이로드 파싱 실패", e);
			throw new PaymentException("웹훅 페이로드 파싱 실패", e);
		}
	}

	/**
	 * 주문을 잠근 트랜잭션에서 멱등 검사·금액 검증·상태별 처리를 한다.
	 *
	 * @throws PaymentException merchantUid 에 해당하는 주문이 없는 경우
	 */
	private ConfirmOutcome confirmUnderOrderLock(String merchantUid, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		Order order = orderRepository.findByMerchantUidWithLock(merchantUid)
			.orElseThrow(() -> {
				log.error("웹훅 주문 조회 실패: merchantUid={}, paymentId={}", merchantUid, paymentId);
				return new PaymentException("주문을 찾을 수 없습니다.");
			});

		// 이미 결제가 끝난 주문: 같은 결제의 재전송이면 끝내고, 한 번 더 승인된 다른 결제면 트랜잭션 밖에서 취소한다
		if (order.getStatus() == OrderStatus.PAID) {
			return duplicatePaymentCanceller.classifyPaymentOnPaidOrder(order, paymentId, paymentResponse);
		}

		// 결제가 이미 기록돼 있으면(예: 환불로 CANCELLED 가 된 주문에 원래 결제의 웹훅이 늦게 옴) 아무것도 하지 않는다
		if (paymentRepository.findByImpUid(paymentId).isPresent()) {
			log.warn("이미 존재하는 결제입니다: paymentId={}", paymentId);
			return new Finished(order);
		}

		if (!verifyWebhookAmount(order, paymentResponse)) {
			return new Finished(order);
		}

		return confirmByPortOneStatus(order, paymentId, paymentResponse);
	}

	/**
	 * 포트원 결제 금액과 주문 금액을 비교한다. 불일치는 최종 실패이므로 주문을 FAILED 로 기록하고 false 를 돌려준다.
	 */
	private boolean verifyWebhookAmount(Order order, PortOnePaymentResponse paymentResponse) {
		if (paymentVerifier.amountMatches(order, paymentResponse)) {
			return true;
		}
		log.error("웹훅 금액 불일치: orderId={}, expected={}, actual={}",
			order.getId(), order.getAmount(), paymentResponse.getAmount().getTotal());
		markOrderFailed(order);
		return false;
	}

	/**
	 * 포트원 재조회 상태로 주문을 확정한다.
	 *
	 * <ul>
	 *   <li>PAID → 결제 확정</li>
	 *   <li>READY, VIRTUAL_ACCOUNT_ISSUED → 진행 중이므로 "아직 완료되지 않음" 으로 보고 주문을 건드리지 않는다.
	 *       Paid 웹훅과 조회 API 반영 사이의 지연 같은 일시 상태를 종단 상태 FAILED 로 굳히지 않기 위해서다.</li>
	 *   <li>FAILED, CANCELLED → 최종 실패로 FAILED 기록</li>
	 *   <li>CANCEL_REQUESTED → 이 서버가 환불 도중 남기는 내부 상태라 포트원 상태에서 나오지 않는다. 나오면 warn 만 남긴다.</li>
	 *   <li>모르는 상태 → "아직 완료되지 않음" 으로 보고 주문을 건드리지 않는다</li>
	 * </ul>
	 *
	 * <p>default 없는 switch 식이라 {@link PaymentStatus} 에 상수가 늘면 여기서 컴파일 오류가 난다. 새 상태가 조용히
	 * 실패(FAILED)로 떨어지지 않게 하려는 것이다.
	 */
	private ConfirmOutcome confirmByPortOneStatus(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		Optional<PaymentStatus> paymentStatus = paymentVerifier.resolveStatus(order, paymentId,
			paymentResponse);
		if (paymentStatus.isEmpty()) {
			return new Finished(order);
		}

		return switch (paymentStatus.get()) {
			case PAID -> {
				if (order.getStatus() == OrderStatus.EXPIRED && !orderDiscountRestorer.reclaim(order)) {
					yield new LatePaymentWithoutDiscount(order, paymentId);
				}
				// 주문 PAID 확정, Payment 저장, 연관관계 연결, 초기 Result 생성, 완료 이벤트 발행(알림은 커밋 뒤 리스너)
				paidOrderFinalizer.finalizePaid(
					order,
					paymentId,
					paymentResponse.getAmount().getTotal(),
					LocalDateTime.now()
				);
				log.info("웹훅으로 결제 완료 처리: orderId={}, paymentId={}, discountCode={}, amount={}/{}",
					order.getId(), paymentId, order.getAppliedDiscountCode(), order.getAmount(),
					order.getOriginalAmount());
				yield new Finished(order);
			}
			case READY, VIRTUAL_ACCOUNT_ISSUED -> {
				log.warn("결제가 아직 완료되지 않았습니다: orderId={}, paymentId={}, status={}",
					order.getId(), paymentId, paymentStatus.get());
				yield new Finished(order);
			}
			case FAILED, CANCELLED -> {
				log.error("웹훅 결제 실패 상태: orderId={}, paymentId={}, status={}",
					order.getId(), paymentId, paymentResponse.getStatus());
				markOrderFailed(order);
				yield new Finished(order);
			}
			case CANCEL_REQUESTED -> {
				log.warn("포트원 상태에서 나올 수 없는 내부 상태라 주문을 그대로 둡니다: orderId={}, paymentId={}, status={}",
					order.getId(), paymentId, paymentResponse.getStatus());
				yield new Finished(order);
			}
		};
	}

	/**
	 * 주문을 FAILED 로 기록하고 쓴 쿠폰·할인코드를 복구한다. FAILED 로 갈 수 없는 상태(EXPIRED 등)면 상태는
	 * 그대로 두고 복구도 하지 않는다(만료 경로가 이미 복구했다).
	 *
	 * <p>예외를 던지지 않으므로 호출자의 트랜잭션이 커밋되며 FAILED 가 실제로 저장된다. FAILED 는 종단 상태라
	 * 만료 스케줄러(PENDING 만 조회)가 다시 다루지 않으므로, 환불·만료와 같은 규칙으로 여기서 바로 복구한다.
	 */
	private void markOrderFailed(Order order) {
		if (!order.getStatus().canTransitionTo(OrderStatus.FAILED)) {
			log.warn("FAILED 로 전이할 수 없는 주문 상태라 그대로 둡니다: orderId={}, status={}",
				order.getId(), order.getStatus());
			return;
		}
		order.markFailed();
		orderRepository.save(order);
		orderDiscountRestorer.restore(order); // 환불·만료와 같은 규칙, 같은 트랜잭션에 참여
		log.info("주문을 FAILED 로 기록: orderId={}, merchantUid={}", order.getId(),
			order.getMerchantUid());
	}
}
