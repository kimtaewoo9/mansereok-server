package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.product.InterpretationProduct;
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
 *
 * <p>상품 분기는 {@link InterpretationProduct} 에 대한 default 없는 switch 다. 상품이 늘면 여기서도 그 상품을 어떻게
 * 다룰지 적어야 컴파일된다.
 */
@Component
@RequiredArgsConstructor
public class CompatibilityPromptFactory {

	// 오늘 날짜는 서버 시간대와 상관없이 한국 시각으로 정한다.
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 오늘 날짜를 정하는 시계. 테스트는 Clock.fixed 로 날짜를 고정한다.
	private final Clock clock;

	/**
	 * @throws IllegalArgumentException subcategoryId 가 null 이거나 궁합 상품이 아닌 경우
	 */
	public String create(Long subcategoryId, CompatibilityPromptContext context) {
		InterpretationProduct product = InterpretationProduct.require(subcategoryId);
		CompatibilityPromptContext sanitized = context.sanitized();
		PromptContext person1 = sanitized.person1();
		PromptContext person2 = sanitized.person2();

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("첫 번째 사람 이름", person1.name());
		userValues.put("첫 번째 사람 작품명", person1.sourceTitle());
		userValues.put("두 번째 사람 이름", person2.name());
		userValues.put("두 번째 사람 작품명", person2.sourceTitle());

		return PromptSections.prependUserInputSection(userValues,
			createAnalysisPrompt(product, person1, person2, todayInSeoul()));
	}

	private String createAnalysisPrompt(InterpretationProduct product, PromptContext person1,
		PromptContext person2, LocalDate today) {
		String person1Name = person1.name();
		String person2Name = person2.name();
		ManseryeokCalculationResponse person1Response = person1.response();
		ManseryeokCalculationResponse person2Response = person2.response();

		return switch (product) {
			case LOVE_STORY_4, LOVE_STORY_6, LOVE_STORY_14 -> CompatibilityPrompts.createLoveStoryPrompt(
				person1Name, person1Response, person2Name, person2Response, today);
			case IDOL_COMPATIBILITY -> CelebrityCompatibilityPrompts.createIdolCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case TRIANGLE_RELATIONSHIP -> CompatibilityPrompts.createTriangleRelationshipPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case CHARACTER_COMPATIBILITY -> CharacterCompatibilityPrompts.createCharacterCompatibilityPrompt(
				person1Name, person1Response, person2Name, person2Response, person2.sourceTitle(),
				today);
			case CHARACTER_TO_CHARACTER_COMPATIBILITY ->
				CharacterCompatibilityPrompts.createCharacterToCharacterCompatibilityPrompt(
					person1Name, person1Response, person1.sourceTitle(),
					person2Name, person2Response, person2.sourceTitle(), today);
			case ACTOR_COMPATIBILITY -> CelebrityCompatibilityPrompts.createActorCompatibilityPrompt(person1Name,
				person1Response, person2Name, person2Response, today);
			case REUNION -> ReunionPrompts.createReunionPrompt(person1Name, person1Response, person2Name,
				person2Response, today);
			// 한 사람짜리 상품은 SajuPromptFactory 가 맡는다.
			case LIFE_OVERALL, PERSONALITY_ANALYSIS, CAREER_APTITUDE, IDOL_ANALYSIS, CHARACTER_SAJU,
				ACTOR_ANALYSIS, LOVE_LUCK, NEW_YEAR_2026, MONEY_LUCK, BUSINESS_LUCK, ACADEMIC_LUCK,
				LIFE_ADVICE,
				CHANGES_2026, KEYWORD_2026, FLIRTING, CHEMISTRY_MATCH, TODAY_FORTUNE,
				MARCH_MONTHLY_FORTUNE -> throw InterpretationProduct.unsupported(product.id());
		};
	}

	private LocalDate todayInSeoul() {
		return LocalDate.now(clock.withZone(SEOUL));
	}
}
