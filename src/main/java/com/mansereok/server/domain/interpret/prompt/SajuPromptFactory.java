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
 * 인물 한 명짜리 상품의 프롬프트를 만든다. 유료 사주 상품과 무료 운세 상품을 함께 맡는다.
 *
 * <p>사용자 입력(이름, 작품명) 정화는 여기 한 곳에서만 한다. 개별 빌더는 이미 정화된 값만 받는다.
 *
 * <p>프롬프트의 "현재 연도"·"오늘 날짜" 도 여기서 한 번만 정한다. 개별 빌더는 시계를 읽지 않고 넘겨받은 날짜만 쓴다.
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
	 * @throws IllegalArgumentException 지원하지 않거나 null 인 subcategoryId 인 경우
	 */
	public String create(Long subcategoryId, PromptContext context) {
		int categoryId = PromptSections.requireRoutableSubcategoryId(subcategoryId);
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		String sourceTitle = sanitized.sourceTitle();
		ManseryeokCalculationResponse response = sanitized.response();
		LocalDate today = todayInSeoul();

		String analysisPrompt = categoryId == 9
			? CharacterPrompts.createCharacterSajuPrompt(name, response, sourceTitle, today)
			: switch (categoryId) {
				case 1 -> LifeAndPersonalityPrompts.createLifeOverallPrompt(name, response, today);
				case 2 -> LifeAndPersonalityPrompts.createPersonalityAnalysisPrompt(name, response, today);
				case 3 -> CareerPrompts.createCareerAptitudePrompt(name, response, today);
				case 5 -> CharacterPrompts.createIdolAnalysisPrompt(name, response, today);
				case 13 -> CharacterPrompts.createActorAnalysisPrompt(name, response, today);
				case 17 -> FortunePrompts.createLoveLuckPrompt(name, response, today); // 연애운
				case 18 -> FortunePrompts.createNewYear2026Prompt(name, response); // 신년 운세. 2026년을 두고 푸는 상품이라 기준연도 2026 고정
				case 20 -> FortunePrompts.createMoneyLuckPrompt(name, response, today);
				case 21 -> BusinessAndAcademicPrompts.createBusinessLuckPrompt(name, response, today);
				case 22 -> BusinessAndAcademicPrompts.createAcademicLuckPrompt(name, response, today); // 학업운
				case 23 -> LifeAndPersonalityPrompts.createLifeAdvicePrompt(name, response, today); // 인생조언
				default ->
					throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
			};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		userValues.put("작품명", sourceTitle);
		return PromptSections.prependUserInputSection(userValues, analysisPrompt);
	}

	/**
	 * 무료 운세 상품 프롬프트. 작품명을 쓰지 않으므로 사용자 입력 구획에 이름만 선언한다.
	 *
	 * @throws IllegalArgumentException 지원하지 않거나 null 인 subcategoryId 인 경우
	 */
	public String createFree(Long subcategoryId, PromptContext context) {
		int categoryId = PromptSections.requireRoutableSubcategoryId(subcategoryId);
		PromptContext sanitized = context.sanitized();
		String name = sanitized.name();
		ManseryeokCalculationResponse response = sanitized.response();
		LocalDate today = todayInSeoul();

		// 101, 102, 106 은 2026년을 두고 푸는 상품이라 기준연도가 2026 으로 고정이고, 오늘 날짜를 받지 않는다.
		String analysisPrompt = switch (categoryId) {
			case 101 -> FreeFortunePrompts.create2026ChangesPrompt(name, response);
			case 102 -> FreeFortunePrompts.create2026KeywordPrompt(name, response);
			case 103 -> FreeFortunePrompts.createFlirtingPrompt(name, response, today);
			case 104 -> FreeFortunePrompts.createChemistryMatchPrompt(name, response, today);
			case 105 -> FreeFortunePrompts.createTodayFortunePrompt(name, response, today);
			case 106 -> FreeFortunePrompts.createMarchMonthlyFortunePrompt(name, response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다.");
		};

		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", name);
		return PromptSections.prependUserInputSection(userValues, analysisPrompt);
	}

	private LocalDate todayInSeoul() {
		return LocalDate.now(clock.withZone(SEOUL));
	}
}
