package com.mansereok.server.global.exception;

/**
 * OpenAI 일시 장애. 재시도와 fallback 을 모두 쓰고도 실패했거나, 응답 형식이 알 수 없게 깨진 경우다.
 * 잠시 뒤 다시 시도하면 풀릴 수 있다는 뜻으로 503 에 매핑한다.
 */
public class OpenAiUnavailableException extends OpenAiException {

	public OpenAiUnavailableException(String message) {
		super(message);
	}

	public OpenAiUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
