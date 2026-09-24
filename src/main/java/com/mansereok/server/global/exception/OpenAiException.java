package com.mansereok.server.global.exception;

/**
 * OpenAI 호출 계층의 공통 상위 예외.
 * 기존 {@link GptApiFailedException} 을 상속하지 않는다. 그 핸들러는 원인 예외를 보고 503 으로 떨어뜨리는
 * 단일 매핑이라, 요청 오류(400)와 미완성·거부(502)를 구분할 수 없기 때문이다.
 */
public class OpenAiException extends RuntimeException {

	public OpenAiException(String message) {
		super(message);
	}

	public OpenAiException(String message, Throwable cause) {
		super(message, cause);
	}
}
