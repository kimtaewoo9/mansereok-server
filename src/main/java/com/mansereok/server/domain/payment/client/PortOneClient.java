package com.mansereok.server.domain.payment.client;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;

/**
 * 포트원(PortOne) V2 API 호출을 추상화한 클라이언트.
 * <p>
 * 결제 서비스(PaymentConfirmService·PaymentWebhookService·PaymentRefundService)가 외부 HTTP 호출에 직접 의존하지 않도록 분리한 이음새이며,
 * 단위 테스트에서는 이 인터페이스를 mock 으로 대체한다.
 */
public interface PortOneClient {

	/**
	 * 결제 단건 조회 (GET /payments/{paymentId})
	 *
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 네트워크 오류, 타임아웃, 5xx 응답 (재시도 가능)
	 * @throws com.mansereok.server.global.exception.PaymentException            4xx 응답, 비어있는 응답, 파싱 실패
	 */
	PortOnePaymentResponse getPayment(String paymentId);

	/**
	 * 결제 취소 (POST /payments/{paymentId}/cancel)
	 *
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 네트워크 오류, 타임아웃, 5xx 응답 (재시도 가능)
	 * @throws com.mansereok.server.global.exception.PaymentException            4xx 응답 등 그 외 실패
	 */
	void cancelPayment(String paymentId, String reason);
}
