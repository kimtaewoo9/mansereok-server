package com.mansereok.server.global.exception;

/**
 * 포트원 API 호출이 일시 장애(네트워크 오류, 타임아웃, 5xx 응답)로 실패했을 때 던진다.
 *
 * <p>요청 자체가 잘못된 4xx 나 응답 파싱 실패({@link PaymentException}, 400)와 달리 재시도로 해결될 수
 * 있는 실패라서 GlobalExceptionHandler 가 503 으로 매핑한다. 웹훅 엔드포인트가 5xx 를 돌려주면
 * 포트원이 같은 웹훅을 재전송한다.
 */
public class PortOneUnavailableException extends RuntimeException {

	public PortOneUnavailableException(String message) {
		super(message);
	}

	public PortOneUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
