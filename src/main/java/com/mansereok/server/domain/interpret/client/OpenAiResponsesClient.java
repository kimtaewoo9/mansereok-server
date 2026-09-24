package com.mansereok.server.domain.interpret.client;

import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;

/**
 * OpenAI Responses API 호출 경계.
 * 호출자는 HTTP 도, 재시도도, 응답 봉투(output / content / output_text)도 알 필요가 없다.
 * 구현이 돌려주는 값은 모델이 생성한 output_text, 즉 Structured Outputs 스키마를 따르는 JSON 문자열이다.
 */
public interface OpenAiResponsesClient {

	/**
	 * @param request 모델·토큰·reasoning 설정이 담긴 요청
	 * @return 모델이 생성한 output_text (JSON 문자열)
	 */
	String createResponse(Gpt5Request request);
}
