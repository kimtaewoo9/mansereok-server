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
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포트원 웹훅 처리. 서명 검증은 컨트롤러가 마친 뒤 호출한다.
 *
 * <p>순서: 페이로드 파싱 → Paid 이벤트 필터 → 포트원 재조회 → 주문 잠금과 멱등 검사 → 금액 검증 → 확정.
 *
 * <p>금액 불일치와 결제 실패 상태는 재전송으로 해결되지 않는 최종 실패라서 예외 없이 주문을 FAILED 로 기록하고
 * 정상 반환한다. 예외를 던지면 같은 트랜잭션의 FAILED 저장이 롤백되고 포트원이 재시도를 반복하므로, 정상 반환으로
 * FAILED 를 커밋하고 포트원에는 200 을 돌려준다. 포트원 일시 장애(PortOneUnavailableException, 503)와
 * DB 장애(500)는 그대로 전파해 포트원이 재시도하게 둔다.
 *
 * <p>클래스 수준 {@code @Transactional} 을 둔다. 주문 잠금부터 FAILED 저장·할인 복구까지 한 트랜잭션이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentWebhookService {

	private final ObjectMapper objectMapper;
	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final PaidOrderFinalizer paidOrderFinalizer;
	private final OrderDiscountRestorer orderDiscountRestorer;

	public void processWebhook(String body) {
		PortoneWebhookDto webhook = parseWebhookBody(body);
		String paymentId = webhook.getPaymentId();
		log.info("웹훅 수신: status={}, paymentId={}", webhook.getStatus(), paymentId);

		// Ready 상태는 결제 완료가 아님 (가상계좌 발급, 결제 시작 등)
		if (!"Paid".equals(webhook.getStatus())) {
			log.info("결제 완료 이벤트가 아님: status={}, paymentId={}", webhook.getStatus(), paymentId);
			return;
		}

		// 웹훅 본문은 신뢰하지 않고 포트원 API 로 재조회한다
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(paymentId);
		String merchantUid = paymentVerifier.merchantUidFromCustomData(paymentResponse);

		Optional<Order> unprocessed = lockUnprocessedOrder(merchantUid, paymentId);
		if (unprocessed.isEmpty()) {
			return;
		}
		Order order = unprocessed.get();

		if (!verifyWebhookAmount(order, paymentResponse)) {
			return;
		}

		confirmByPortOneStatus(order, paymentId, paymentResponse);
	}

	private PortoneWebhookDto parseWebhookBody(String body) {
		try {
			return objectMapper.readValue(body, PortoneWebhookDto.class);
		} catch (JsonProcessingException e) {
			log.error("웹훅 페이로드 파싱 실패", e);
			throw new PaymentException("웹훅 페이로드 파싱 실패");
		}
	}

	/**
	 * 주문을 비관적 락으로 조회하고 멱등 검사를 한다. 이미 처리된 주문(PAID 이거나 같은 paymentId 의 Payment 가
	 * 있음)이면 빈 Optional 을 돌려준다.
	 *
	 * @throws PaymentException merchantUid 에 해당하는 주문이 없는 경우
	 */
	private Optional<Order> lockUnprocessedOrder(String merchantUid, String paymentId) {
		Order order = orderRepository.findByMerchantUidWithLock(merchantUid)
			.orElseThrow(() -> {
				log.error("웹훅 주문 조회 실패: merchantUid={}, paymentId={}", merchantUid, paymentId);
				return new PaymentException("주문을 찾을 수 없습니다.");
			});

		if (order.getStatus() == OrderStatus.PAID) {
			log.info("이미 처리된 주문: orderId={}, merchantUid={}", order.getId(), merchantUid);
			return Optional.empty();
		}

		if (paymentRepository.findByImpUid(paymentId).isPresent()) {
			log.warn("이미 존재하는 결제입니다: paymentId={}", paymentId);
			return Optional.empty();
		}

		return Optional.of(order);
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
	 *   <li>모르는 상태 → "아직 완료되지 않음" 으로 보고 주문을 건드리지 않는다</li>
	 * </ul>
	 */
	private void confirmByPortOneStatus(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		Optional<PaymentStatus> paymentStatus = paymentVerifier.resolveStatus(order, paymentId,
			paymentResponse);
		if (paymentStatus.isEmpty()) {
			return;
		}

		PaymentStatus resolved = paymentStatus.get();
		if (resolved == PaymentStatus.READY || resolved == PaymentStatus.VIRTUAL_ACCOUNT_ISSUED) {
			log.warn("결제가 아직 완료되지 않았습니다: orderId={}, paymentId={}, status={}",
				order.getId(), paymentId, resolved);
			return;
		}

		if (resolved != PaymentStatus.PAID) { // FAILED, CANCELLED
			log.error("웹훅 결제 실패 상태: orderId={}, paymentId={}, status={}",
				order.getId(), paymentId, paymentResponse.getStatus());
			markOrderFailed(order);
			return;
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
