package com.mansereok.server.global.exception;

/**
 * 모델이 응답을 거부한 경우(content 의 refusal 타입). 재시도로 풀리지 않는 최종 실패다.
 */
public class OpenAiRefusalException extends OpenAiException {

	private final String refusal;

	public OpenAiRefusalException(String refusal, String message) {
		super(message);
		this.refusal = refusal;
	}

	public String getRefusal() {
		return refusal;
	}
}
