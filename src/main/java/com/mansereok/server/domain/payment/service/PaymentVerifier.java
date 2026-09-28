package com.mansereok.server.domain.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.dto.response.WebhookCustomData;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.global.exception.PaymentException;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 포트원 조회 응답과 주문을 대조하는 공통 검증. 결제 완료 API(PaymentConfirmService)와 웹훅(PaymentWebhookService)이 같은
 * 규칙을 두 곳에 복사하지 않도록 여기에 모은다.
 *
 * <p>검증 실패를 어떻게 다룰지는 호출자가 정한다. 완료 API 는 금액 불일치를 400 으로 던지고, 웹훅은 예외 없이
 * 주문을 FAILED 로 기록하므로 금액 비교는 boolean 으로 돌려준다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentVerifier {

	private final ObjectMapper objectMapper;

	/**
	 * 포트원 응답 customData 에서 주문 번호(merchantUid)를 꺼낸다. 웹훅이 주문을 찾는 키다.
	 *
	 * @throws PaymentException customData 가 비어 있거나 형식이 어긋난 경우 ({@link WebhookCustomData#from})
	 */
	public String merchantUidFromCustomData(PortOnePaymentResponse paymentResponse) {
		return WebhookCustomData.from(paymentResponse.getCustomData(), objectMapper).merchantUid();
	}

	/**
	 * 포트원 응답의 결제 ID 가 요청한 결제 ID 와 글자까지 같은지 확인한다. 조회 주소가 잘리거나 바뀌어 다른 결제를 받아 온
	 * 경우("pay_A#1" 을 요청했는데 pay_A 가 온 경우)를 걸러, 결제 한 건이 여러 주문에 붙는 것을 막는다.
	 *
	 * <p>이 검사를 통과한 뒤에는 요청값이 아니라 응답의 결제 ID 를 중복 검사와 Payment.impUid 저장에 쓴다.
	 *
	 * <p>응답의 결제 ID 가 없으면 요청값과 상관없이 거부한다. 웹훅 본문에는 payment_id 검증이 없어 요청값이 null 로 올 수
	 * 있는데, 둘 다 null 인 것을 같은 값으로 보고 통과시키지 않기 위해서다.
	 *
	 * @throws PaymentException 응답의 결제 ID 가 없거나 요청한 결제 ID 와 다른 경우
	 */
	public void assertPaymentIdMatches(String requestedPaymentId,
		PortOnePaymentResponse paymentResponse) {
		String responsePaymentId = paymentResponse.getId();
		if (responsePaymentId == null || !responsePaymentId.equals(requestedPaymentId)) {
			log.warn("결제 정보의 결제 ID 불일치: requestedPaymentId={}, responsePaymentId={}",
				requestedPaymentId, responsePaymentId);
			throw new PaymentException("결제 정보의 결제 ID가 일치하지 않습니다.");
		}
	}

	/**
	 * 포트원 응답 customData 의 merchantUid 를 잠근 주문의 merchantUid(요청값이 아니라 DB 값)와 대조한다.
	 * 결제 한 건이 다른 주문에 붙는 것을 막는다.
	 *
	 * <p>customData 가 비어 있으면 거부한다. customData 는 결제를 만드는 쪽(사용자 브라우저)이 정하는 값이라, 비어 있을 때
	 * 통과시키면 customData 없이 만든 결제 한 건으로 대조를 건너뛸 수 있다. 형식이 어긋난 customData 는
	 * {@link WebhookCustomData#from} 의 PaymentException 이 그대로 전파된다.
	 *
	 * @throws PaymentException customData 가 비어 있거나, 그 merchantUid 가 주문의 merchantUid 와 다른 경우
	 */
	public void assertCustomDataMatchesOrder(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		String customData = paymentResponse.getCustomData();
		if (customData == null || customData.isBlank()) {
			log.warn("포트원 응답에 customData 가 없어 결제를 확정하지 않습니다: orderId={}, paymentId={}",
				order.getId(), paymentId);
			throw new PaymentException("결제 정보에 주문 번호가 없습니다.");
		}

		String paidMerchantUid = merchantUidFromCustomData(paymentResponse);
		if (!Objects.equals(paidMerchantUid, order.getMerchantUid())) {
			log.warn("결제 정보의 주문 번호 불일치: orderId={}, orderMerchantUid={}, customDataMerchantUid={}, paymentId={}",
				order.getId(), order.getMerchantUid(), paidMerchantUid, paymentId);
			throw new PaymentException("결제 정보의 주문 번호가 일치하지 않습니다.");
		}
	}

	/**
	 * 포트원 결제 금액이 주문 금액과 같은지 비교한다. 포트원 응답에 금액이 없으면 다르다고 본다.
	 */
	public boolean amountMatches(Order order, PortOnePaymentResponse paymentResponse) {
		Long paidAmount = paymentResponse.getAmount().getTotal();
		return paidAmount != null && order.amountEquals(paidAmount);
	}

	/**
	 * 포트원 상태 문자열을 {@link PaymentStatus} 로 매핑한다. 모르는 상태는 "아직 완료되지 않음" 으로 보고 warn 로그를
	 * 남긴 뒤 빈 Optional 을 돌려준다. 호출자는 그 경우 주문을 건드리지 않는다.
	 */
	public Optional<PaymentStatus> resolveStatus(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		Optional<PaymentStatus> paymentStatus = PaymentStatus.fromPortOneStatus(
			paymentResponse.getStatus());
		if (paymentStatus.isEmpty()) {
			log.warn("알 수 없는 포트원 결제 상태라 미완료로 취급합니다: orderId={}, paymentId={}, rawStatus={}",
				order.getId(), paymentId, paymentResponse.getStatus());
		}
		return paymentStatus;
	}
}
