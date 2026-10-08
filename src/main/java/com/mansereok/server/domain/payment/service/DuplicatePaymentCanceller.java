package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.event.PaymentAnomalyEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.DuplicatePayment;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.Finished;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.LatePaymentWithoutDiscount;
import com.mansereok.server.global.exception.PaymentException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 이미 결제가 끝난 주문에 다른 결제 ID 로 한 번 더 승인된 결제(중복 결제)를 가려내 포트원에서 취소하고 운영 채널에 알린다.
 * 만료된 주문에 늦게 들어왔지만 할인을 다시 잡지 못한 결제도 같은 방식으로 취소한다({@link #cancelLatePayment}).
 *
 * <p>결제 완료 API(PaymentConfirmService)와 웹훅(PaymentWebhookService)이 함께 쓴다. 일은 두 단계로 나뉜다.
 * <ol>
 *   <li>{@link #classifyPaymentOnPaidOrder}: 주문 행을 잠근 트랜잭션 안에서 부른다. 들어온 결제가 취소할 중복 결제인지 가린다.</li>
 *   <li>{@link #cancel}: 잠금과 트랜잭션을 모두 놓은 뒤에 부른다. 포트원 취소는 돈을 움직이는 외부 호출이라, 잠금을 쥔 채 기다리면
 *       같은 주문의 다른 요청이 그 시간만큼 묶인다. 다만 open-in-view 가 켜져 있어 웹 요청 안에서는 커밋 뒤에도 DB 커넥션이 요청이
 *       끝날 때까지 잡혀 있다(포트원 읽기 시간 제한 안에서).</li>
 * </ol>
 *
 * <p>자동 취소는 네 조건이 모두 맞을 때만 한다. 결제의 customData 가 이 주문을 가리키고, 포트원 상태가 PAID 이고, 주문에 기록된
 * 결제 ID 와 다르고, payments 표에 아직 기록되지 않은 결제다. 다른 주문을 위해 만든 결제는 여기서 취소하지 않고 거부한다. 다음 세
 * 경우는 취소하지 않고 알리기만 한다.
 * <ul>
 *   <li>주문에 결제 ID 가 기록되지 않은 예전 주문. 같은 결제인지 가릴 수 없다.</li>
 *   <li>payments 표에 이미 기록된 결제. customData 대조가 없던 예전 코드가 이 결제를 다른 주문의 결제로 기록했을 수 있어, 취소하면
 *       그 주문은 결제 완료로 남은 채 돈만 돌아간다.</li>
 *   <li>포트원에서 부분 취소된 결제. 남은 금액이 아직 청구돼 있지만, 누군가 이미 손을 댄 결제라 나머지를 자동으로 취소하지 않는다.</li>
 * </ul>
 *
 * <p>알림은 {@link PaymentAnomalyEvent} 로 보낸다. 리스너가 커밋 뒤에 보내고, 트랜잭션 밖에서 발행하면 곧바로 보낸다. 알림은 요청마다
 * 발행하고 같은 결제의 알림을 거르지 않는다. 그래서 알리기만 하는 세 경우는 완료 API 와 웹훅이 함께 오면 보통 알림이 두 건 가고,
 * 완료 API 를 다시 부를 때마다 한 건씩 더 간다. 드문 경로라 중복을 거르지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicatePaymentCanceller {

	/** 포트원 취소 API 에 남기는 취소 사유. */
	static final String CANCEL_REASON = "같은 주문의 중복 결제 자동 취소";
	/** 할인을 다시 잡지 못한 늦은 결제를 취소할 때 포트원에 남기는 사유. */
	static final String LATE_PAYMENT_CANCEL_REASON = "만료된 주문의 할인을 다시 적용할 수 없어 자동 취소";

	/** 포트원 조회 API 의 부분 취소 상태. {@link PaymentStatus#fromPortOneStatus} 는 이 값을 CANCELLED 로 접는다. */
	private static final String PORTONE_PARTIAL_CANCELLED = "PARTIAL_CANCELLED";

	private final PortOneClient portOneClient;
	private final PaymentVerifier paymentVerifier;
	private final PaymentRepository paymentRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 이미 결제가 끝난 주문에 들어온 결제를 가린다. 주문 행을 잠근 트랜잭션 안에서 부른다.
	 *
	 * <ul>
	 *   <li>주문에 기록된 결제와 같은 결제 → 같은 결제의 재요청·재전송이므로 {@link Finished}</li>
	 *   <li>customData 가 이 주문이 아닌 결제 → PaymentException 으로 거부한다. 취소하지 않는다.</li>
	 *   <li>포트원에서 부분 취소된 결제 → 남은 금액이 아직 청구돼 있으므로 알림만 발행하고 {@link Finished}</li>
	 *   <li>그 밖에 포트원 상태가 PAID 가 아닌 결제 → 돈이 빠져나가지 않았으므로 {@link Finished}</li>
	 *   <li>주문에 결제 ID 가 기록되지 않은 예전 주문 → 같은 결제인지 가릴 수 없으므로 알림만 발행하고 {@link Finished}</li>
	 *   <li>payments 표에 이미 기록된 결제 → 다른 주문의 결제로 기록됐을 수 있으므로 알림만 발행하고 {@link Finished}</li>
	 *   <li>그 밖 → 한 번 더 승인된 결제이므로 {@link DuplicatePayment}. 호출자는 트랜잭션이 끝난 뒤 {@link #cancel} 을 부른다.</li>
	 * </ul>
	 *
	 * @param paidOrder 잠근 PAID 주문
	 * @param paymentId 포트원 응답과 대조를 마친 결제 ID
	 * @throws PaymentException customData 가 비어 있거나 이 주문을 가리키지 않을 때
	 */
	ConfirmOutcome classifyPaymentOnPaidOrder(Order paidOrder, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		if (Objects.equals(paidOrder.getPaymentId(), paymentId)) {
			log.info("이미 같은 결제로 확정된 주문입니다: orderId={}, paymentId={}", paidOrder.getId(), paymentId);
			return new Finished(paidOrder);
		}

		// 다른 결제. 이 주문을 위해 만든 결제가 아니면 여기서 취소하지 않고 거부한다.
		paymentVerifier.assertCustomDataMatchesOrder(paidOrder, paymentId, paymentResponse);

		// 상태 매핑은 부분 취소를 CANCELLED 로 접지만, 부분 취소는 남은 금액이 아직 청구된 상태라 따로 본다.
		if (isPartiallyCancelled(paymentResponse)) {
			log.error("이미 결제가 끝난 주문에 다른 결제가 부분 취소 상태로 왔습니다. 남은 금액이 청구돼 있지만 자동 취소하지 않습니다: "
					+ "orderId={}, merchantUid={}, recordedPaymentId={}, paymentId={}", paidOrder.getId(),
				paidOrder.getMerchantUid(), paidOrder.getPaymentId(), paymentId);
			publishAnomaly("이미 결제가 끝난 주문에 다른 결제가 부분 취소 상태로 왔습니다. 남은 금액이 아직 청구돼 있으니 확인해 주세요. "
				+ "자동 취소는 하지 않았습니다.", paidOrder, paymentId, Map.of("포트원 결제 상태", paymentResponse.getStatus()));
			return new Finished(paidOrder);
		}

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

		// customData 대조가 없던 예전 코드는 이 결제를 다른 주문의 결제로 기록했을 수 있다. 취소하면 그 주문은 결제 완료로 남은 채
		// 돈만 돌아가므로, 이미 기록된 결제는 취소하지 않는다.
		Optional<Payment> recordedPayment = paymentRepository.findByImpUid(paymentId);
		if (recordedPayment.isPresent()) {
			String recordedMerchantUid = Objects.requireNonNullElse(recordedPayment.get().getMerchantUid(), "없음");
			log.error("이미 결제가 끝난 주문에 payments 표에 기록된 다른 결제가 왔습니다. 다른 주문의 결제로 기록됐을 수 있어 취소하지 "
					+ "않습니다: orderId={}, merchantUid={}, recordedPaymentId={}, paymentId={}, paymentRecordedMerchantUid={}",
				paidOrder.getId(), paidOrder.getMerchantUid(), paidOrder.getPaymentId(), paymentId, recordedMerchantUid);
			publishAnomaly("이미 결제 기록이 있는 결제가 결제가 끝난 주문에 또 왔습니다. 다른 주문의 결제로 기록됐을 수 있어 자동 취소는 "
				+ "하지 않았습니다. 결제가 기록된 주문을 확인해 주세요.", paidOrder, paymentId,
				Map.of("결제가 기록된 주문 번호", recordedMerchantUid));
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
	 * 조회한다. 그렇게 해서 전액 취소돼 있으면 실패 원인에 따라 나눈다.
	 * <ul>
	 *   <li>포트원이 취소 요청을 거절했으면(PaymentException, 4xx) 이 요청은 취소하지 않았다. 같은 결제의 다른 요청(결제 완료 API 와
	 *       웹훅은 보통 함께 온다)이 먼저 취소하고 알렸으므로 알림 없이 넘어간다.</li>
	 *   <li>응답을 받지 못했으면(시간 초과·5xx 등) 이 요청의 취소가 포트원에 반영됐을 수 있다. 그때 알리지 않으면 중복 결제가 있었다는
	 *       알림이 한 건도 나가지 않을 수 있어, 취소돼 있다는 알림을 한 번 보낸다. 다른 요청도 거의 같은 때 취소했으면 알림이 두 건
	 *       갈 수 있지만, 알림이 빠지는 것보다 낫다고 본다.</li>
	 * </ul>
	 * 전액 취소돼 있지 않으면 error 로그와 알림을 남겨 운영자가 손으로 취소하게 한다. 남은 결제는 다음 날 대사에서도 한 번 더 잡힌다.
	 */
	void cancel(DuplicatePayment duplicate) {
		cancelAtPortOne(duplicate.duplicatePaymentId(),
			paymentDetails(duplicate.paidOrder(), duplicate.duplicatePaymentId()), CANCEL_REASON,
			"이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 자동으로 취소했습니다.",
			"이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 취소를 요청했습니다.",
			"이미 결제가 끝난 주문에 결제가 한 번 더 승인됐는데 자동 취소에 실패했습니다. 포트원에서 손으로 취소해 주세요.");
	}

	/**
	 * 만료된 주문에 늦게 들어왔지만 할인을 다시 잡지 못한 결제를 포트원에서 전액 취소하고 운영 채널에 알린다. 주문 행 잠금과 트랜잭션을
	 * 모두 놓은 뒤에 부른다. 실패 처리는 {@link #cancel} 과 같다.
	 */
	void cancelLatePayment(LatePaymentWithoutDiscount late) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", late.expiredOrder().getMerchantUid());
		details.put("주문 ID", String.valueOf(late.expiredOrder().getId()));
		details.put("취소한 결제 ID", late.paymentId());
		cancelAtPortOne(late.paymentId(), details, LATE_PAYMENT_CANCEL_REASON,
			"만료된 주문에 결제가 늦게 들어왔는데 할인이 그사이 다른 주문에 쓰여 결제를 자동으로 취소했습니다.",
			"만료된 주문의 늦은 결제를 할인을 다시 적용할 수 없어 취소를 요청했습니다.",
			"만료된 주문의 늦은 결제를 할인을 다시 적용할 수 없어 취소하려 했지만 실패했습니다. 포트원에서 손으로 취소해 주세요.");
	}

	/**
	 * @param details 알림에 실을 주문·결제 정보. 실패하면 원인을 덧붙인다.
	 */
	private void cancelAtPortOne(String paymentId, Map<String, String> details, String reason,
		String cancelledSummary, String requestedSummary, String failedSummary) {
		try {
			portOneClient.cancelPayment(paymentId, reason);
		} catch (RuntimeException e) {
			handleCancelFailure(paymentId, details, e, requestedSummary, failedSummary);
			return;
		}

		log.warn("결제를 자동 취소했습니다: {}, reason={}", details, reason);
		eventPublisher.publishEvent(new PaymentAnomalyEvent(cancelledSummary, details));
	}

	private void handleCancelFailure(String paymentId, Map<String, String> details, RuntimeException failure,
		String requestedSummary, String failedSummary) {
		if (isCancelledAtPortOne(paymentId)) {
			if (failure instanceof PaymentException) {
				log.info("포트원이 취소 요청을 거절했지만 이미 취소돼 있습니다. 같은 결제의 다른 요청이 먼저 취소한 것으로 봅니다: "
					+ "{}, cause={}", details, failure.getMessage());
				return;
			}

			log.warn("취소 응답은 받지 못했지만 포트원에서 취소돼 있습니다. 이 요청의 취소가 반영됐을 수 있어 알립니다: {}, cause={}",
				details, failure.getMessage());
			eventPublisher.publishEvent(new PaymentAnomalyEvent(
				requestedSummary + " 취소 응답은 받지 못했지만 포트원에서 취소된 것을 확인했습니다. 따로 할 일은 없습니다.",
				withDetail(details, "취소 요청 실패 원인", String.valueOf(failure.getMessage()))));
			return;
		}

		log.error("결제 자동 취소에 실패했습니다. 포트원에서 손으로 취소해야 합니다: {}", details, failure);
		eventPublisher.publishEvent(new PaymentAnomalyEvent(failedSummary,
			withDetail(details, "실패 원인", String.valueOf(failure.getMessage()))));
	}

	private static Map<String, String> withDetail(Map<String, String> details, String key, String value) {
		Map<String, String> extended = new LinkedHashMap<>(details);
		extended.put(key, value);
		return extended;
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

	/** 포트원 상태가 부분 취소인지 본다. 상태 매핑({@link PaymentStatus#fromPortOneStatus})처럼 앞뒤 공백과 대소문자를 무시한다. */
	private static boolean isPartiallyCancelled(PortOnePaymentResponse paymentResponse) {
		String status = paymentResponse.getStatus();
		return status != null && PORTONE_PARTIAL_CANCELLED.equals(status.trim().toUpperCase(Locale.ROOT));
	}

	private void publishAnomaly(String summary, Order paidOrder, String anotherPaymentId,
		Map<String, String> extraDetails) {
		Map<String, String> details = paymentDetails(paidOrder, anotherPaymentId);
		details.putAll(extraDetails);
		eventPublisher.publishEvent(new PaymentAnomalyEvent(summary, details));
	}

	private static Map<String, String> paymentDetails(Order paidOrder, String anotherPaymentId) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", paidOrder.getMerchantUid());
		details.put("주문 ID", String.valueOf(paidOrder.getId()));
		details.put("주문에 기록된 결제 ID", Objects.requireNonNullElse(paidOrder.getPaymentId(), "없음"));
		details.put("한 번 더 온 결제 ID", anotherPaymentId);
		return details;
	}
}
