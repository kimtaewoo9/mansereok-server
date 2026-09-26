package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 프롬프트의 "현재 연도"·"오늘 날짜"·"오늘 일진" 이 주입된 시계의 한국 시각으로 정해지는지 확인한다.
 *
 * <p>시계의 시간대는 서울과 UTC 를 섞는다. 운영 Clock 빈은 서버 기본 시간대를 따르므로, 서버가 UTC 로 떠도 한국 날짜로
 * 프롬프트를 만드는지 봐야 한다. 2027-01-01 00:30(서울)은 UTC 로는 아직 2026-12-31 15:30 이라 그 차이가 드러나는 시각이다.
 */
@DisplayName("프롬프트의 현재 연도와 오늘 날짜")
class PromptTodayDateTest {

	@Nested
	@DisplayName("새해 경계 전후로 만들면")
	class AroundNewYear {

		@ParameterizedTest(name = "[{index}] 시계 {0} ({1} 시간대) → {2}, {3}")
		@DisplayName("유료 사주 상품의 현재 연도와 대운 기준연도는 한국 시각의 연도다")
		@CsvSource(textBlock = """
			# 시계가 가리키는 순간,  시계의 시간대, 기본 정보 줄의 현재 연도, 대운 기준연도
			2026-12-31T23:30+09:00, Asia/Seoul, 현재 2026년, 기준연도=2026
			2027-01-01T00:30+09:00, Asia/Seoul, 현재 2027년, 기준연도=2027
			2027-01-01T00:30+09:00, UTC,        현재 2027년, 기준연도=2027
			""")
		void sajuPromptUsesYearInSeoul(String instant, String clockZone, String currentYear,
			String daewoonReferenceYear) {
			// given
			SajuPromptFactory factory = new SajuPromptFactory(clockAt(instant, clockZone));

			// when
			String prompt = factory.create(1L, PromptContext.of("김태우", PromptFixtures.person1()));

			// then
			assertThat(prompt).contains(currentYear);
			assertThat(prompt).contains(daewoonReferenceYear);
		}

		@ParameterizedTest(name = "[{index}] 시계 {0} ({1} 시간대) → {2}")
		@DisplayName("재회운(궁합)의 현재 시점은 한국 시각의 날짜다")
		@CsvSource(textBlock = """
			# 시계가 가리키는 순간,  시계의 시간대, 프롬프트에 들어갈 현재 시점
			2026-12-31T23:30+09:00, Asia/Seoul, 현재 시점: 2026년 12월 31일
			2027-01-01T00:30+09:00, Asia/Seoul, 현재 시점: 2027년 01월 01일
			2027-01-01T00:30+09:00, UTC,        현재 시점: 2027년 01월 01일
			""")
		void reunionPromptUsesDateInSeoul(String instant, String clockZone, String currentDate) {
			// given
			CompatibilityPromptFactory factory = new CompatibilityPromptFactory(
				clockAt(instant, clockZone));

			// when
			String prompt = factory.create(19L, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "이은정", PromptFixtures.person2()));

			// then
			assertThat(prompt).contains(currentDate);
		}

		@ParameterizedTest(name = "[{index}] 시계 {0} ({1} 시간대) → {2}, {3}")
		@DisplayName("오늘의 운세(무료)의 날짜와 일진은 한국 시각의 날짜로 정한다")
		@CsvSource(textBlock = """
			# 시계가 가리키는 순간,  시계의 시간대, 오늘 날짜 줄, 오늘 일진 줄
			2026-12-31T23:30+09:00, Asia/Seoul, **날짜**: 2026년 12월 31일 목요일, **오늘의 일진(Input)**: 기묘(己卯)
			2027-01-01T00:30+09:00, Asia/Seoul, **날짜**: 2027년 01월 01일 금요일, **오늘의 일진(Input)**: 경진(庚辰)
			2027-01-01T00:30+09:00, UTC,        **날짜**: 2027년 01월 01일 금요일, **오늘의 일진(Input)**: 경진(庚辰)
			""")
		void todayFortunePromptUsesDateInSeoul(String instant, String clockZone, String dateLine,
			String dayPillarLine) {
			// given
			SajuPromptFactory factory = new SajuPromptFactory(clockAt(instant, clockZone));

			// when
			String prompt = factory.createFree(105L, PromptContext.of("김태우", PromptFixtures.person1()));

			// then
			assertThat(prompt).contains(dateLine);
			assertThat(prompt).contains(dayPillarLine);
		}
	}

	@Nested
	@DisplayName("대운이 다음 칸으로 넘어가는 해의 경계 전후로 만들면")
	class AroundDaewoonChange {

		/**
		 * person2 는 대운 시작연도가 1998 이라 2018 부터 정해(丁亥) 대운이고, 2028 부터 다음 대운이다.
		 */
		@ParameterizedTest(name = "[{index}] 시계 {0} ({1} 시간대) → {2}")
		@DisplayName("대운 현재 칸은 한국 시각으로 해가 바뀌는 순간 다음 칸으로 넘어간다")
		@CsvSource(delimiter = '|', textBlock = """
			# 시계가 가리키는 순간 | 시계의 시간대 | 대운 고정값 줄
			2027-12-31T23:30+09:00 | Asia/Seoul | [대운 고정값] 기준연도=2027, 현재대운=정해(丁亥), 구간=23~32세, 시작연도=2018
			2028-01-01T00:30+09:00 | Asia/Seoul | [대운 고정값] 기준연도=2028, 현재대운=무자(戊子), 구간=33~42세, 시작연도=2028
			2028-01-01T00:30+09:00 | UTC        | [대운 고정값] 기준연도=2028, 현재대운=무자(戊子), 구간=33~42세, 시작연도=2028
			""")
		void currentDaewoonMovesAtNewYearInSeoul(String instant, String clockZone,
			String daewoonLine) {
			// given
			SajuPromptFactory factory = new SajuPromptFactory(clockAt(instant, clockZone));

			// when
			String prompt = factory.create(1L, PromptContext.of("이은정", PromptFixtures.person2()));

			// then
			assertThat(prompt).contains(daewoonLine);
		}
	}

	private static Clock clockAt(String instant, String zone) {
		return Clock.fixed(OffsetDateTime.parse(instant).toInstant(), ZoneId.of(zone));
	}
}
