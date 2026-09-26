package com.mansereok.server.global.exception;

/**
 * OpenAI 호출 계층의 공통 상위 예외.
 * 응답 상태는 하위 타입마다 따로 정한다. 호출 불가는 503, 요청 오류는 400, 미완성·거부는 502 로 나간다.
 */
public class OpenAiException extends RuntimeException {

	public OpenAiException(String message) {
		super(message);
	}

	public OpenAiException(String message, Throwable cause) {
		super(message, cause);
	}
}
