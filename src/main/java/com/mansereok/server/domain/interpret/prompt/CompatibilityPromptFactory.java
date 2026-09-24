package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import org.springframework.stereotype.Component;

/**
 * 인물 두 명이 필요한 궁합 상품의 프롬프트를 만든다.
 */
@Component
public class CompatibilityPromptFactory {

	/**
	 * @throws IllegalArgumentException 지원하지 않는 subcategoryId 인 경우
	 */
	public String create(Long subcategoryId, CompatibilityPromptContext context) {
		CompatibilityPromptContext sanitized = context.sanitized();
		PromptContext person1 = sanitized.person1();
		PromptContext person2 = sanitized.person2();

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("첫 번째 사람 이름", person1.name());
		userValues.put("첫 번째 사람 작품명", person1.sourceTitle());
		userValues.put("두 번째 사람 이름", person2.name());
		userValues.put("두 번째 사람 작품명", person2.sourceTitle());

		return PromptSections.withSectionBoundary(userValues,
			createAnalysisPrompt(subcategoryId, person1, person2));
	}

	private String createAnalysisPrompt(Long subcategoryId, PromptContext person1,
		PromptContext person2) {
		String person1Name = person1.name();
		String person2Name = person2.name();
		ManseryeokCalculationResponse person1Response = person1.response();
		ManseryeokCalculationResponse person2Response = person2.response();

		if (subcategoryId == 10) {
			return CharacterCompatibilityPrompts.createCharacterCompatibilityPrompt(
				person1Name, person1Response, person2Name, person2Response, person2.sourceTitle());
		}
		if (subcategoryId == 11) {
			return CharacterCompatibilityPrompts.createCharacterToCharacterCompatibilityPrompt(
				person1Name, person1Response, person1.sourceTitle(),
				person2Name, person2Response, person2.sourceTitle()
			);
		}

		return switch (subcategoryId.intValue()) {
			case 4, 6 -> CompatibilityPrompts.createLoveStoryPrompt(person1Name, person1Response,
				person2Name, person2Response);
			case 7 -> CompatibilityPrompts.createIdolCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response);
			case 8 -> CompatibilityPrompts.createTriangleRelationshipPrompt(person1Name,
				person1Response, person2Name, person2Response);
			case 14 -> CompatibilityPrompts.createLoveStoryPrompt(person1Name, person1Response,
				person2Name, person2Response);
			case 15 -> CompatibilityPrompts.createActorCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response);
			case 19 -> ReunionPrompts.createReunionPrompt(person1Name, person1Response, person2Name,
				person2Response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
		};
	}
}
