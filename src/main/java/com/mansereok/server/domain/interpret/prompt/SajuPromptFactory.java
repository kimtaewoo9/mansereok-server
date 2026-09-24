package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import org.springframework.stereotype.Component;

/**
 * 인물 한 명짜리 상품의 프롬프트를 만든다. 유료 사주 상품과 무료 운세 상품을 함께 맡는다.
 *
 * <p>사용자 입력(이름, 작품명) 정화는 여기 한 곳에서만 한다. 개별 빌더는 이미 정화된 값만 받는다.
 */
@Component
public class SajuPromptFactory {

	/**
	 * 유료 사주 상품 프롬프트.
	 *
	 * @throws IllegalArgumentException 지원하지 않는 subcategoryId 인 경우
	 */
	public String create(Long subcategoryId, PromptContext context) {
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		String sourceTitle = sanitized.sourceTitle();
		ManseryeokCalculationResponse response = sanitized.response();

		String analysisPrompt = subcategoryId == 9
			? CharacterPrompts.createCharacterSajuPrompt(name, response, sourceTitle)
			: switch (subcategoryId.intValue()) {
				case 1 -> LifeAndPersonalityPrompts.createLifeOverallPrompt(name, response);
				case 2 -> LifeAndPersonalityPrompts.createPersonalityAnalysisPrompt(name, response);
				case 3 -> CareerPrompts.createCareerAptitudePrompt(name, response);
				case 5 -> CharacterPrompts.createIdolAnalysisPrompt(name, response);
				case 13 -> CharacterPrompts.createActorAnalysisPrompt(name, response);
				case 17 -> FortunePrompts.createLoveLuckPrompt(name, response); // 연애운
				case 18 -> FortunePrompts.createNewYear2026Prompt(name, response); // 신년 운세
				case 20 -> FortunePrompts.createMoneyLuckPrompt(name, response);
				case 21 -> CareerPrompts.createBusinessLuckPrompt(name, response);
				case 22 -> CareerPrompts.createAcademicLuckPrompt(name, response); // 학업운
				case 23 -> LifeAndPersonalityPrompts.createLifeAdvicePrompt(name, response); // 인생조언
				default ->
					throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
			};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		userValues.put("작품명", sourceTitle);
		return PromptSections.withSectionBoundary(userValues, analysisPrompt);
	}

	/**
	 * 무료 운세 상품 프롬프트. 작품명을 쓰지 않으므로 사용자 입력 구획에 이름만 선언한다.
	 *
	 * @throws IllegalArgumentException 지원하지 않는 subcategoryId 인 경우
	 */
	public String createFree(Long subcategoryId, PromptContext context) {
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		ManseryeokCalculationResponse response = sanitized.response();

		String analysisPrompt = switch (subcategoryId.intValue()) {
			case 101 -> FreeFortunePrompts.create2026ChangesPrompt(name, response);
			case 102 -> FreeFortunePrompts.create2026KeywordPrompt(name, response);
			case 103 -> FreeFortunePrompts.createFlirtingPrompt(name, response);
			case 104 -> FreeFortunePrompts.createChemistryMatchPrompt(name, response);
			case 105 -> FreeFortunePrompts.createTodayFortunePrompt(name, response);
			case 106 -> FreeFortunePrompts.createMarchMonthlyFortunePrompt(name, response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다.");
		};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		return PromptSections.withSectionBoundary(userValues, analysisPrompt);
	}
}
