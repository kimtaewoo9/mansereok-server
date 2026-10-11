package com.mansereok.server.domain.payment.client;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
	 * 결제 단건 조회. "포트원에 없는 결제"(404)를 실패로 접지 않고 빈 Optional 로 돌려준다.
	 *
	 * <p>{@link #getPayment} 는 404 도 {@link com.mansereok.server.global.exception.PaymentException} 으로 뭉개서
	 * "없는 결제"와 "조회 실패"를 구분할 수 없다. 대사는 둘을 다른 불일치로 기록해야 하므로 이 메서드를 쓴다.
	 *
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 네트워크 오류, 타임아웃, 5xx 응답 (재시도 가능)
	 * @throws com.mansereok.server.global.exception.PaymentException            404 를 제외한 4xx 응답, 비어있는 응답, 파싱 실패
	 */
	Optional<PortOnePaymentResponse> findPayment(String paymentId);

	/**
	 * 상태가 바뀐 결제 다건 조회 (GET /payments). 창 안에서 PAID·CANCELLED·PARTIAL_CANCELLED 가 된 결제를
	 * 페이지 끝까지 순회해 합친다.
	 *
	 * @param from  창 시작(포함)
	 * @param until 창 끝(제외)
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 네트워크 오류, 타임아웃, 5xx 응답 (재시도 가능)
	 * @throws com.mansereok.server.global.exception.PaymentException            4xx 응답, 파싱 실패, 페이지 순회 상한 초과
	 */
	List<PortOnePaymentResponse> listPaymentsChangedBetween(Instant from, Instant until);

	/**
	 * 결제 취소 (POST /payments/{paymentId}/cancel)
	 *
	 * @throws com.mansereok.server.global.exception.PortOneUnavailableException 네트워크 오류, 타임아웃, 5xx 응답 (재시도 가능)
	 * @throws com.mansereok.server.global.exception.PaymentException            4xx 응답 등 그 외 실패
	 */
	void cancelPayment(String paymentId, String reason);
}
