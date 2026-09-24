package com.mansereok.server.global.exception;

/**
 * 모델이 응답을 끝내지 못한 경우(status=incomplete). reason 이 max_output_tokens 인 경우가 대부분이다.
 * 잘린 JSON 을 억지로 복구하면 잘린 해석문이 그대로 저장되므로, 복구하지 않고 최종 실패로 다룬다.
 */
public class OpenAiIncompleteResponseException extends OpenAiException {

	private final String reason;

	public OpenAiIncompleteResponseException(String reason, String message) {
		super(message);
		this.reason = reason;
	}

	public String getReason() {
		return reason;
	}
}
