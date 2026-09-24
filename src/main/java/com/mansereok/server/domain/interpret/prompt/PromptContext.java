package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 인물 한 명 기준으로 프롬프트 빌더가 필요로 하는 입력 묶음.
 *
 * <p>String 인자 세 개를 호출부에서 줄줄이 늘어놓으면 순서를 바꿔 넣어도 컴파일이 되므로
 * 한 타입으로 묶어 이름을 붙인다.
 *
 * @param name        사용자가 입력한 이름. {@link #sanitized()} 를 거치기 전에는 정화되지 않은 값이다.
 * @param response    만세력 계산 결과
 * @param sourceTitle 캐릭터 상품에서만 쓰는 작품명. 나머지 상품에서는 null 이다.
 */
public record PromptContext(
	String name,
	ManseryeokCalculationResponse response,
	String sourceTitle
) {

	public static PromptContext of(String name, ManseryeokCalculationResponse response) {
		return new PromptContext(name, response, null);
	}

	public static PromptContext of(String name, ManseryeokCalculationResponse response,
		String sourceTitle) {
		return new PromptContext(name, response, sourceTitle);
	}

	/**
	 * 사용자 입력을 정화한 사본을 돌려준다. 정화는 팩토리 진입점 한 곳에서만 하고,
	 * 개별 프롬프트 빌더는 이미 정화된 값만 받는다.
	 */
	PromptContext sanitized() {
		return new PromptContext(
			UserInputSanitizer.sanitizeName(name),
			response,
			UserInputSanitizer.sanitizeSourceTitle(sourceTitle)
		);
	}
}
