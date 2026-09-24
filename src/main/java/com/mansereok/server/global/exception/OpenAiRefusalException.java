package com.mansereok.server.global.exception;

/**
 * 모델이 응답을 거부한 경우(content 의 refusal 타입). 재시도로 풀리지 않는 최종 실패다.
 */
public class OpenAiRefusalException extends OpenAiException {

	private final String refusal;

	/**
	 * 거부 사유만 받고 메시지는 여기서 조립한다. 같은 타입 파라미터 두 개를 나란히 받지 않게 한다.
	 */
	public OpenAiRefusalException(String refusal) {
		super("OpenAI 가 응답을 거부했습니다: " + refusal);
		this.refusal = refusal;
	}

	public String getRefusal() {
		return refusal;
	}
}
