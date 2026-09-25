package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 인물 두 명이 필요한 궁합 프롬프트의 입력 묶음.
 *
 * <p>이름·만세력·작품명이 사람마다 하나씩 있어 평평하게 펴면 같은 타입 인자가 여섯 개 늘어선다.
 * 사람 단위로 한 번 묶어 두면 호출부에서 첫 번째와 두 번째를 뒤바꿀 여지가 사라진다.
 */
public record CompatibilityPromptContext(PromptContext person1, PromptContext person2) {

	public static CompatibilityPromptContext of(
		String person1Name, ManseryeokCalculationResponse person1Response,
		String person2Name, ManseryeokCalculationResponse person2Response
	) {
		return of(person1Name, person1Response, null, person2Name, person2Response, null);
	}

	public static CompatibilityPromptContext of(
		String person1Name, ManseryeokCalculationResponse person1Response,
		String person1SourceTitle,
		String person2Name, ManseryeokCalculationResponse person2Response,
		String person2SourceTitle
	) {
		return new CompatibilityPromptContext(
			PromptContext.of(person1Name, person1Response, person1SourceTitle),
			PromptContext.of(person2Name, person2Response, person2SourceTitle)
		);
	}

	CompatibilityPromptContext sanitized() {
		return new CompatibilityPromptContext(person1.sanitized(), person2.sanitized());
	}
}
