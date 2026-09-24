package com.mansereok.server.global.exception;

/**
 * 모델이 응답을 끝내지 못한 경우(status=incomplete). reason 이 max_output_tokens 인 경우가 대부분이다.
 * 잘린 JSON 을 억지로 복구하면 잘린 해석문이 그대로 저장되므로, 복구하지 않고 최종 실패로 다룬다.
 */
public class OpenAiIncompleteResponseException extends OpenAiException {

	private final String reason;

	/**
	 * reason 만 받고 메시지는 여기서 조립한다. (reason, message) 처럼 String 두 개를 잇달아 받으면
	 * 호출부가 순서를 바꿔 넣어도 컴파일되어 아무도 잡지 못한다.
	 */
	public OpenAiIncompleteResponseException(String reason) {
		super("OpenAI 응답이 완성되지 않았습니다. reason: " + reason);
		this.reason = reason;
	}

	public String getReason() {
		return reason;
	}
}
