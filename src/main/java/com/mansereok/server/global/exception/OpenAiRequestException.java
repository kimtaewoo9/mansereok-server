package com.mansereok.server.global.exception;

/**
 * 요청 자체가 잘못된 경우(400·401·403 등 429 를 제외한 4xx).
 * 재시도해도 결과가 같으므로 즉시 실패시킨다.
 */
public class OpenAiRequestException extends OpenAiException {

	public OpenAiRequestException(String message) {
		super(message);
	}

	public OpenAiRequestException(String message, Throwable cause) {
		super(message, cause);
	}
}
