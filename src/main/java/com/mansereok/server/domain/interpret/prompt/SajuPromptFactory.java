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
 * 인물 한 명짜리 상품의 프롬프트를 만든다. 유료 사주 상품과 무료 운세 상품을 함께 맡는다.
 *
 * <p>사용자 입력(이름, 작품명) 정화는 여기 한 곳에서만 한다. 개별 빌더는 이미 정화된 값만 받는다.
 *
 * <p>프롬프트의 "현재 연도"·"오늘 날짜" 도 여기서 한 번만 정한다. 개별 빌더는 시계를 읽지 않고 넘겨받은 날짜만 쓴다.
 * 2026년을 두고 푸는 상품(18, 101, 102, 106)은 예외로, 날짜를 넘겨받지 않고 빌더가 기준연도 2026 을 쓴다.
 *
 * <p>상품 분기는 {@link InterpretationProduct} 에 대한 default 없는 switch 다. 상품이 늘면 두 메서드 모두 그 상품을
 * 어떻게 다룰지 적어야 컴파일된다.
 */
@Component
@RequiredArgsConstructor
public class SajuPromptFactory {

	// 오늘 날짜는 서버 시간대와 상관없이 한국 시각으로 정한다.
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 오늘 날짜를 정하는 시계. 테스트는 Clock.fixed 로 날짜를 고정한다.
	private final Clock clock;

	/**
	 * 유료 사주 상품 프롬프트.
	 *
	 * @throws IllegalArgumentException subcategoryId 가 null 이거나 유료 사주 상품이 아닌 경우
	 */
	public String create(Long subcategoryId, PromptContext context) {
		InterpretationProduct product = InterpretationProduct.require(subcategoryId);
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		String sourceTitle = sanitized.sourceTitle();
		ManseryeokCalculationResponse response = sanitized.response();
		LocalDate today = todayInSeoul();

		String analysisPrompt = switch (product) {
			case LIFE_OVERALL -> LifeAndPersonalityPrompts.createLifeOverallPrompt(name, response, today);
			case PERSONALITY_ANALYSIS -> LifeAndPersonalityPrompts.createPersonalityAnalysisPrompt(name, response, today);
			case CAREER_APTITUDE -> CareerPrompts.createCareerAptitudePrompt(name, response, today);
			case IDOL_ANALYSIS -> CharacterPrompts.createIdolAnalysisPrompt(name, response, today);
			case CHARACTER_SAJU -> CharacterPrompts.createCharacterSajuPrompt(name, response, sourceTitle, today);
			case ACTOR_ANALYSIS -> CharacterPrompts.createActorAnalysisPrompt(name, response, today);
			case LOVE_LUCK -> FortunePrompts.createLoveLuckPrompt(name, response, today);
			// 2026년을 두고 푸는 상품이라 기준연도 2026 고정
			case NEW_YEAR_2026 -> FortunePrompts.createNewYear2026Prompt(name, response);
			case MONEY_LUCK -> FortunePrompts.createMoneyLuckPrompt(name, response, today);
			case BUSINESS_LUCK -> BusinessAndAcademicPrompts.createBusinessLuckPrompt(name, response, today);
			case ACADEMIC_LUCK -> BusinessAndAcademicPrompts.createAcademicLuckPrompt(name, response, today);
			case LIFE_ADVICE -> LifeAndPersonalityPrompts.createLifeAdvicePrompt(name, response, today);
			// 궁합 상품은 CompatibilityPromptFactory, 무료 운세 상품은 createFree 가 맡는다.
			case LOVE_STORY_4, LOVE_STORY_6, IDOL_COMPATIBILITY, TRIANGLE_RELATIONSHIP,
				CHARACTER_COMPATIBILITY, CHARACTER_TO_CHARACTER_COMPATIBILITY, LOVE_STORY_14,
				ACTOR_COMPATIBILITY, REUNION,
				CHANGES_2026, KEYWORD_2026, FLIRTING, CHEMISTRY_MATCH, TODAY_FORTUNE,
				MARCH_MONTHLY_FORTUNE -> throw InterpretationProduct.unsupported(product.id());
		};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		userValues.put("작품명", sourceTitle);
		return PromptSections.prependUserInputSection(userValues, analysisPrompt);
	}

	/**
	 * 무료 운세 상품 프롬프트. 작품명을 쓰지 않으므로 사용자 입력 구획에 이름만 선언한다.
	 *
	 * @throws IllegalArgumentException subcategoryId 가 null 이거나 무료 운세 상품이 아닌 경우
	 */
	public String createFree(Long subcategoryId, PromptContext context) {
		InterpretationProduct product = InterpretationProduct.require(subcategoryId);
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		ManseryeokCalculationResponse response = sanitized.response();
		LocalDate today = todayInSeoul();

		// 2026 변화·2026 키워드·3월 월운은 2026년을 두고 푸는 상품이라 기준연도가 2026 으로 고정이고, 오늘 날짜를 받지 않는다.
		String analysisPrompt = switch (product) {
			case CHANGES_2026 -> FreeFortunePrompts.create2026ChangesPrompt(name, response);
			case KEYWORD_2026 -> FreeFortunePrompts.create2026KeywordPrompt(name, response);
			case FLIRTING -> FreeFortunePrompts.createFlirtingPrompt(name, response, today);
			case CHEMISTRY_MATCH -> FreeFortunePrompts.createChemistryMatchPrompt(name, response, today);
			case TODAY_FORTUNE -> FreeFortunePrompts.createTodayFortunePrompt(name, response, today);
			case MARCH_MONTHLY_FORTUNE -> FreeFortunePrompts.createMarchMonthlyFortunePrompt(name, response);
			// 유료 사주 상품은 create, 궁합 상품은 CompatibilityPromptFactory 가 맡는다.
			case LIFE_OVERALL, PERSONALITY_ANALYSIS, CAREER_APTITUDE, IDOL_ANALYSIS, CHARACTER_SAJU,
				ACTOR_ANALYSIS, LOVE_LUCK, NEW_YEAR_2026, MONEY_LUCK, BUSINESS_LUCK, ACADEMIC_LUCK,
				LIFE_ADVICE,
				LOVE_STORY_4, LOVE_STORY_6, IDOL_COMPATIBILITY, TRIANGLE_RELATIONSHIP,
				CHARACTER_COMPATIBILITY, CHARACTER_TO_CHARACTER_COMPATIBILITY, LOVE_STORY_14,
				ACTOR_COMPATIBILITY, REUNION -> throw InterpretationProduct.unsupported(product.id());
		};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		return PromptSections.prependUserInputSection(userValues, analysisPrompt);
	}

	private LocalDate todayInSeoul() {
		return LocalDate.now(clock.withZone(SEOUL));
	}
}
