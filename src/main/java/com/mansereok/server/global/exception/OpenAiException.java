package com.mansereok.server.global.exception;

/**
 * OpenAI 호출 계층의 공통 상위 예외.
 * {@link GlobalExceptionHandler} 가 하위 타입마다 상태를 따로 매핑한다. 호출 불가는 503, 요청 오류는 400, 미완성·거부는 502 다.
 *
 * <p>다만 지금은 모든 OpenAI 호출이 비동기 해석 흐름(InterpretationPipeline) 안에서 일어나고, 그 흐름이 예외를 잡아 결과 상태를
 * 되돌리고 끝낸다. 그래서 이 매핑이 HTTP 응답에 쓰이지는 않는다.
 */
public class OpenAiException extends RuntimeException {

	public OpenAiException(String message) {
		super(message);
	}

	public OpenAiException(String message, Throwable cause) {
		super(message, cause);
	}
}
