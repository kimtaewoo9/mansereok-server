package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 인물 두 명이 필요한 궁합 상품의 프롬프트를 만든다.
 *
 * <p>프롬프트의 "현재 연도"·"오늘 날짜" 는 여기서 한 번만 정한다. 개별 빌더는 시계를 읽지 않고 넘겨받은 날짜만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class CompatibilityPromptFactory {

	// 오늘 날짜는 서버 시간대와 상관없이 한국 시각으로 정한다.
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 오늘 날짜를 정하는 시계. 테스트는 Clock.fixed 로 날짜를 고정한다.
	private final Clock clock;

	/**
	 * @throws IllegalArgumentException 지원하지 않거나 null 인 subcategoryId 인 경우
	 */
	public String create(Long subcategoryId, CompatibilityPromptContext context) {
		int categoryId = PromptSections.requireRoutableSubcategoryId(subcategoryId);
		CompatibilityPromptContext sanitized = context.sanitized();
		PromptContext person1 = sanitized.person1();
		PromptContext person2 = sanitized.person2();

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("첫 번째 사람 이름", person1.name());
		userValues.put("첫 번째 사람 작품명", person1.sourceTitle());
		userValues.put("두 번째 사람 이름", person2.name());
		userValues.put("두 번째 사람 작품명", person2.sourceTitle());

		LocalDate today = LocalDate.now(clock.withZone(SEOUL));
		return PromptSections.prependUserInputSection(userValues,
			createAnalysisPrompt(categoryId, person1, person2, today));
	}

	private String createAnalysisPrompt(int subcategoryId, PromptContext person1,
		PromptContext person2, LocalDate today) {
		String person1Name = person1.name();
		String person2Name = person2.name();
		ManseryeokCalculationResponse person1Response = person1.response();
		ManseryeokCalculationResponse person2Response = person2.response();

		if (subcategoryId == 10) {
			return CharacterCompatibilityPrompts.createCharacterCompatibilityPrompt(
				person1Name, person1Response, person2Name, person2Response, person2.sourceTitle(),
				today);
		}
		if (subcategoryId == 11) {
			return CharacterCompatibilityPrompts.createCharacterToCharacterCompatibilityPrompt(
				person1Name, person1Response, person1.sourceTitle(),
				person2Name, person2Response, person2.sourceTitle(), today
			);
		}

		return switch (subcategoryId) {
			case 4, 6 -> CompatibilityPrompts.createLoveStoryPrompt(person1Name, person1Response,
				person2Name, person2Response, today);
			case 7 -> CelebrityCompatibilityPrompts.createIdolCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case 8 -> CompatibilityPrompts.createTriangleRelationshipPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case 14 -> CompatibilityPrompts.createLoveStoryPrompt(person1Name, person1Response,
				person2Name, person2Response, today);
			case 15 -> CelebrityCompatibilityPrompts.createActorCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case 19 -> ReunionPrompts.createReunionPrompt(person1Name, person1Response, person2Name,
				person2Response, today);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
		};
	}
}
