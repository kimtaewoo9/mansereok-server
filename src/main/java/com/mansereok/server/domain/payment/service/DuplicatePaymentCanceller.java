package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.event.PaymentAnomalyEvent;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.DuplicatePayment;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.Finished;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 이미 결제가 끝난 주문에 다른 결제 ID 로 한 번 더 승인된 결제(중복 결제)를 가려내 포트원에서 취소하고 운영 채널에 알린다.
 *
 * <p>결제 완료 API(PaymentConfirmService)와 웹훅(PaymentWebhookService)이 함께 쓴다. 일은 두 단계로 나뉜다.
 * <ol>
 *   <li>{@link #classifyPaymentOnPaidOrder}: 주문 행을 잠근 트랜잭션 안에서 부른다. 들어온 결제가 취소할 중복 결제인지 가린다.</li>
 *   <li>{@link #cancel}: 잠금과 트랜잭션을 모두 놓은 뒤에 부른다. 포트원 취소는 돈을 움직이는 외부 호출이라, 잠금을 쥔 채 기다리면
 *       같은 주문의 다른 요청과 DB 커넥션이 그 시간만큼 묶인다.</li>
 * </ol>
 *
 * <p>자동 취소는 세 조건이 모두 맞을 때만 한다. 결제의 customData 가 이 주문을 가리키고, 포트원 상태가 PAID 이고, 주문에 기록된
 * 결제 ID 와 다르다. 다른 주문을 위해 만든 결제는 여기서 취소하지 않고 거부한다. 주문에 결제 ID 가 기록되지 않은 예전 주문은 같은
 * 결제인지 가릴 수 없으므로 취소하지 않고 알리기만 한다.
 *
 * <p>알림은 {@link PaymentAnomalyEvent} 로 보낸다. 리스너가 커밋 뒤에 보내고, 트랜잭션 밖에서 발행하면 곧바로 보낸다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicatePaymentCanceller {

	/** 포트원 취소 API 에 남기는 취소 사유. */
	static final String CANCEL_REASON = "같은 주문의 중복 결제 자동 취소";

	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 이미 결제가 끝난 주문에 들어온 결제를 가린다. 주문 행을 잠근 트랜잭션 안에서 부른다.
	 *
	 * <ul>
	 *   <li>주문에 기록된 결제와 같은 결제 → 같은 결제의 재요청·재전송이므로 {@link Finished}</li>
	 *   <li>customData 가 이 주문이 아닌 결제 → PaymentException 으로 거부한다. 취소하지 않는다.</li>
	 *   <li>포트원 상태가 PAID 가 아닌 결제 → 돈이 빠져나가지 않았으므로 {@link Finished}</li>
	 *   <li>주문에 결제 ID 가 기록되지 않은 예전 주문 → 같은 결제인지 가릴 수 없으므로 알림만 발행하고 {@link Finished}</li>
	 *   <li>그 밖 → 한 번 더 승인된 결제이므로 {@link DuplicatePayment}. 호출자는 트랜잭션이 끝난 뒤 {@link #cancel} 을 부른다.</li>
	 * </ul>
	 *
	 * @param paidOrder 잠근 PAID 주문
	 * @param paymentId 포트원 응답과 대조를 마친 결제 ID
	 * @throws com.mansereok.server.global.exception.PaymentException customData 가 비어 있거나 이 주문을 가리키지 않을 때
	 */
	ConfirmOutcome classifyPaymentOnPaidOrder(Order paidOrder, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		if (Objects.equals(paidOrder.getPaymentId(), paymentId)) {
			log.info("이미 같은 결제로 확정된 주문입니다: orderId={}, paymentId={}", paidOrder.getId(), paymentId);
			return new Finished(paidOrder);
		}

		// 다른 결제. 이 주문을 위해 만든 결제가 아니면 여기서 취소하지 않고 거부한다.
		paymentVerifier.assertCustomDataMatchesOrder(paidOrder, paymentId, paymentResponse);

		Optional<PaymentStatus> status = PaymentStatus.fromPortOneStatus(paymentResponse.getStatus());
		if (status.filter(PaymentStatus.PAID::equals).isEmpty()) {
			log.info("이미 결제가 끝난 주문에 다른 결제가 왔지만 승인된 결제가 아니라 그대로 둡니다: orderId={}, recordedPaymentId={}, "
				+ "paymentId={}, status={}", paidOrder.getId(), paidOrder.getPaymentId(), paymentId,
				paymentResponse.getStatus());
			return new Finished(paidOrder);
		}

		if (paidOrder.getPaymentId() == null) {
			log.error("결제 ID 가 기록되지 않은 결제 완료 주문에 승인된 결제가 또 왔습니다. 같은 결제인지 가릴 수 없어 취소하지 않습니다: "
				+ "orderId={}, merchantUid={}, paymentId={}", paidOrder.getId(), paidOrder.getMerchantUid(), paymentId);
			publishAnomaly("결제 ID 가 기록되지 않은 예전 결제 완료 주문에 승인된 결제가 또 왔습니다. 두 번째 결제인지 확인해 주세요. "
				+ "자동 취소는 하지 않았습니다.", paidOrder, paymentId, Map.of());
			return new Finished(paidOrder);
		}

		log.error("이미 결제가 끝난 주문에 다른 결제가 한 번 더 승인됐습니다. 잠금을 놓은 뒤 자동 취소합니다: orderId={}, merchantUid={}, "
				+ "recordedPaymentId={}, duplicatePaymentId={}", paidOrder.getId(), paidOrder.getMerchantUid(),
			paidOrder.getPaymentId(), paymentId);
		return new DuplicatePayment(paidOrder, paymentId);
	}

	/**
	 * 중복 결제를 포트원에서 전액 취소하고 운영 채널에 알린다. 주문 행 잠금과 트랜잭션을 모두 놓은 뒤에 부른다.
	 *
	 * <p>취소에 실패해도 예외를 던지지 않는다. 호출자는 취소 결과와 관계없이 같은 응답을 돌려준다. 실패하면 포트원에서 결제를 다시
	 * 조회해, 이미 취소돼 있으면 같은 결제의 다른 요청(결제 완료 API 와 웹훅은 보통 함께 온다)이 먼저 취소한 것으로 보고 넘어간다.
	 * 그렇지 않으면 error 로그와 알림을 남겨 운영자가 손으로 취소하게 한다. 남은 결제는 다음 날 대사에서도 한 번 더 잡힌다.
	 */
	void cancel(DuplicatePayment duplicate) {
		Order paidOrder = duplicate.paidOrder();
		String duplicatePaymentId = duplicate.duplicatePaymentId();
		try {
			portOneClient.cancelPayment(duplicatePaymentId, CANCEL_REASON);
		} catch (RuntimeException e) {
			handleCancelFailure(paidOrder, duplicatePaymentId, e);
			return;
		}

		log.warn("같은 주문의 중복 결제를 자동 취소했습니다: orderId={}, recordedPaymentId={}, duplicatePaymentId={}",
			paidOrder.getId(), paidOrder.getPaymentId(), duplicatePaymentId);
		publishAnomaly("이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 자동으로 취소했습니다.", paidOrder,
			duplicatePaymentId, Map.of());
	}

	private void handleCancelFailure(Order paidOrder, String duplicatePaymentId, RuntimeException failure) {
		if (isCancelledAtPortOne(duplicatePaymentId)) {
			log.info("중복 결제 취소가 실패했지만 포트원에서 이미 취소돼 있습니다. 같은 결제의 다른 요청이 먼저 취소한 것으로 봅니다: "
				+ "orderId={}, duplicatePaymentId={}, cause={}", paidOrder.getId(), duplicatePaymentId, failure.getMessage());
			return;
		}

		log.error("중복 결제 자동 취소에 실패했습니다. 포트원에서 손으로 취소해야 합니다: orderId={}, merchantUid={}, "
				+ "recordedPaymentId={}, duplicatePaymentId={}", paidOrder.getId(), paidOrder.getMerchantUid(),
			paidOrder.getPaymentId(), duplicatePaymentId, failure);
		publishAnomaly("이미 결제가 끝난 주문에 결제가 한 번 더 승인됐는데 자동 취소에 실패했습니다. 포트원에서 손으로 취소해 주세요.",
			paidOrder, duplicatePaymentId, Map.of("실패 원인", String.valueOf(failure.getMessage())));
	}

	/**
	 * 포트원에서 결제가 전액 취소된 상태인지 다시 조회한다. 부분 취소는 남은 금액이 빠져나간 상태라 취소로 보지 않는다. 조회에
	 * 실패하면 취소됐는지 알 수 없으므로 false 를 돌려준다.
	 */
	private boolean isCancelledAtPortOne(String paymentId) {
		try {
			return portOneClient.findPayment(paymentId)
				.map(PortOnePaymentResponse::getStatus)
				.filter("CANCELLED"::equalsIgnoreCase)
				.isPresent();
		} catch (RuntimeException e) {
			log.warn("중복 결제 취소 실패 뒤 결제 상태를 다시 조회하지 못했습니다: paymentId={}", paymentId, e);
			return false;
		}
	}

	private void publishAnomaly(String summary, Order paidOrder, String anotherPaymentId,
		Map<String, String> extraDetails) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", paidOrder.getMerchantUid());
		details.put("주문 ID", String.valueOf(paidOrder.getId()));
		details.put("주문에 기록된 결제 ID", Objects.requireNonNullElse(paidOrder.getPaymentId(), "없음"));
		details.put("한 번 더 온 결제 ID", anotherPaymentId);
		details.putAll(extraDetails);
		eventPublisher.publishEvent(new PaymentAnomalyEvent(summary, details));
	}
}
