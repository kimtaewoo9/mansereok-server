package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 선택 입력(출생시간, 작품명)이 비어 있어도 모든 상품의 프롬프트가 만들어지고, 빈 값이 문자열 "null" 로 새지 않는지 본다.
 *
 * <p>출생시간은 요청에서 선택값이라 만세력 계산이 solarTime 을 null 로 넘긴다. 예전에는 1·3·5·13·17번 빌더가
 * 그 값에 toString() 을 불러 NullPointerException 으로 해석이 매번 실패했고, 나머지 상품은 프롬프트에 "null" 을 찍었다.
 * 작품명도 선택값이라 정화 뒤 null 이 되는데, 9·10·11번은 그 값을 그대로 넣어 "작품 'null'" 을 만들었다.
 *
 * <p>절입일에 태어나 출생시간을 모르면 만세력 계산이 대운 필드를 모두 null 로 두므로, 그 모양도 따로 태운다.
 *
 * <p>기대 결과 파일에는 "null" 이 한 번도 나오지 않으므로(9·10·11 edge 를 고친 뒤) 부분 문자열 "null" 자체가 없어야 한다.
 */
@DisplayName("선택 입력이 비어도 프롬프트가 깨지지 않는다")
class PromptMissingInputTest {

	private static final SajuPromptFactory SAJU = new SajuPromptFactory(PromptFixtures.FIXED_CLOCK);
	private static final CompatibilityPromptFactory COMPATIBILITY = new CompatibilityPromptFactory(PromptFixtures.FIXED_CLOCK);

	@Nested
	@DisplayName("출생시간을 모르면")
	class WhenBirthTimeUnknown {

		@ParameterizedTest(name = "유료 사주 {0}번 프롬프트가 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L, 22L, 23L})
		void sajuPromptHasNoNull(long subcategoryId) {
			// when: 예전에는 1·3·5·13·17번이 여기서 NullPointerException 을 던졌다
			String prompt = SAJU.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknown(), "원피스"));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "무료 운세 {0}번 프롬프트가 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {101L, 102L, 103L, 104L, 105L, 106L})
		void freePromptHasNoNull(long subcategoryId) {
			// when
			String prompt = SAJU.createFree(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknown()));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "궁합 {0}번 프롬프트가 두 사람 모두 시간을 몰라도 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L})
		void compatibilityPromptHasNoNull(long subcategoryId) {
			// when
			String prompt = COMPATIBILITY.create(subcategoryId, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.personTimeUnknown(), "원피스",
				"이은정", PromptFixtures.personTimeUnknown(), "귀멸의 칼날"));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@Test
		@DisplayName("기본 정보 데이터 줄의 출생시각 자리에는 시간 모름이라고 쓴다")
		void dataLineSaysTimeUnknown() {
			// when
			String prompt = SAJU.create(1L,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknown(), "원피스"));

			// then
			assertThat(prompt).contains("이름: '김태우' | 남성 | 1990-01-01 시간 모름 | 현재");
		}

		@ParameterizedTest(name = "[{index}] 유료 사주 {0}번 시작 문장은 \"{1}\" 처럼 날짜만 쓴다")
		@CsvSource(delimiter = ';', quoteCharacter = '`', textBlock = """
			# 상품 ; 모델에게 첫 문장으로 쓰라고 주는 문구
			2      ; 김태우님은 1990-01-01에 태어나신
			3      ; "1990년 01월 01일에 태어나신 김태우님의
			5      ; 김태우님은 1990년 01월 01일에 태어나신
			13     ; 김태우님은 1990년 01월 01일에 태어나신
			17     ; 김태우님은 1990년 01월 01일에 태어나신
			""")
		@DisplayName("결과 첫 문장이 되는 시작 문장에는 시간 모름을 넣지 않고 생년월일만 쓴다")
		void openingSentenceLeavesOutUnknownTime(long subcategoryId, String expectedPhrase) {
			// when
			String prompt = SAJU.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknown(), "원피스"));

			// then
			assertThat(prompt)
				.contains(expectedPhrase)
				.doesNotContain("시간 모름에");
		}
	}

	@Nested
	@DisplayName("절입일에 태어나 출생시간을 모르고 대운 필드가 모두 비어 있으면")
	class WhenBirthTimeUnknownOnSeasonStartDay {

		@ParameterizedTest(name = "유료 사주 {0}번 프롬프트가 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L, 22L, 23L})
		void sajuPromptHasNoNull(long subcategoryId) {
			// when
			String prompt = SAJU.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknownOnSeasonStartDay(), "원피스"));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "무료 운세 {0}번 프롬프트가 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {101L, 102L, 103L, 104L, 105L, 106L})
		void freePromptHasNoNull(long subcategoryId) {
			// when
			String prompt = SAJU.createFree(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknownOnSeasonStartDay()));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "궁합 {0}번 프롬프트가 두 사람 모두 이 경우여도 예외 없이 만들어지고 null 이 없다")
		@ValueSource(longs = {4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L})
		void compatibilityPromptHasNoNull(long subcategoryId) {
			// when
			String prompt = COMPATIBILITY.create(subcategoryId, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.personTimeUnknownOnSeasonStartDay(), "원피스",
				"이은정", PromptFixtures.personTimeUnknownOnSeasonStartDay(), "귀멸의 칼날"));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@Test
		@DisplayName("인생총운(1) 프롬프트의 대운 칸에는 대운 정보 없음과 불확정 안내를 쓴다")
		void daewoonSectionSaysNoInformation() {
			// when
			String prompt = SAJU.create(1L,
				PromptContext.of("김태우", PromptFixtures.personTimeUnknownOnSeasonStartDay(), "원피스"));

			// then
			assertThat(prompt).contains("""
				### 대운 ###
				대운 정보 없음
				- 출생시간 미입력: 시주는 계산하지 않았습니다.
				- 출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.
				- 절입일 출생 + 시간 미입력으로 연주/월주 경계가 불확정입니다.
				- 출생시간 미입력으로 대운 시작 나이는 확정할 수 없습니다.
				""");
		}
	}

	@Nested
	@DisplayName("작품명이 없으면")
	class WhenSourceTitleMissing {

		@ParameterizedTest(name = "유료 사주 {0}번 프롬프트에 null 이 없다")
		@ValueSource(longs = {1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L, 22L, 23L})
		void sajuPromptHasNoNull(long subcategoryId) {
			// when
			String prompt = SAJU.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.person1(), null));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "궁합 {0}번 프롬프트에 null 이 없다")
		@ValueSource(longs = {4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L})
		void compatibilityPromptHasNoNull(long subcategoryId) {
			// when
			String prompt = COMPATIBILITY.create(subcategoryId, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "이은정", PromptFixtures.person2()));

			// then
			assertThat(prompt).doesNotContain("null");
		}

		@ParameterizedTest(name = "정화하면 비는 작품명 \"{0}\" 도 캐릭터 사주(9) 프롬프트에 null 로 새지 않는다")
		@ValueSource(strings = {"", "   ", "<<<", "[분석 지시]"})
		void blankAfterSanitizingSourceTitleHasNoNull(String sourceTitle) {
			// when
			String prompt = SAJU.create(9L, PromptContext.of("김태우", PromptFixtures.person1(), sourceTitle));

			// then
			assertThat(prompt)
				.doesNotContain("null")
				.contains("\"'김태우'님은 [임수 자연물 비유]와 같은 기운을 타고나셨군요.\"로 시작");
		}

		@ParameterizedTest(name = "[{index}] 캐릭터 사주(9)는 \"{0}\" 처럼 작품명 없이 캐릭터 이름만 쓴다")
		@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			# 작품명 자리가 비어 있던 문장. 예전에는 한 곳은 '알 수 없는 작품', 다른 곳은 'null' 이었다
			이 사주는 캐릭터 **'김태우'**의 사주입니다.
			"'김태우'님은 [임수 자연물 비유]와 같은 기운을 타고나셨군요."로 시작
			""")
		@DisplayName("작품명이 없으면 캐릭터 사주(9)의 두 문장 모두 작품명 없이 캐릭터 이름부터 쓴다")
		void characterSajuNamesCharacterWithoutTitle(String expectedLine) {
			// when
			String prompt = SAJU.create(9L, PromptContext.of("김태우", PromptFixtures.person1(), null));

			// then
			assertThat(prompt).contains(expectedLine);
		}

		@ParameterizedTest(name = "[{index}] 캐릭터 궁합 {0}번은 \"{1}\" 처럼 작품명 없이 캐릭터 이름만 쓴다")
		@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			# 상품 | 작품명 자리가 비어 있던 문장
			10     | 사용자('김태우')가 캐릭터 **'이은정'**와의 연애 궁합을 의뢰했습니다.
			10     | --- 캐릭터 (최애): 이은정 ---
			11     | - 이 분석은 **'김태우'**와 **'이은정'** 간의 가상 궁합(Coupling)입니다.
			11     | --- 캐릭터 1: 김태우 ---
			11     | 1. 김태우의 연애관과 기질
			""")
		void characterCompatibilityNamesCharacterWithoutTitle(long subcategoryId, String expectedLine) {
			// when
			String prompt = COMPATIBILITY.create(subcategoryId, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "이은정", PromptFixtures.person2()));

			// then
			assertThat(prompt).contains(expectedLine);
		}
	}
}
