package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프롬프트의 "현재 연도"·"오늘 날짜"·"오늘 일진" 이 주입된 시계의 한국 시각으로 정해지는지 확인한다.
 *
 * <p>시계의 시간대는 서울과 UTC 를 섞는다. 운영 Clock 빈은 서버 기본 시간대를 따르므로, 서버가 UTC 로 떠도 한국 날짜로
 * 프롬프트를 만드는지 봐야 한다. 2027-01-01 00:30(서울)은 UTC 로는 아직 2026-12-31 15:30 이라 그 차이가 드러나는 시각이다.
 *
 * <p>이 테스트는 주입한 시계를 바꿔 가며 보므로, 빌더가 시계를 거치지 않고 {@code LocalDate.now()} 같은 시스템 시계를
 * 직접 읽는 회귀는 잡지 못한다(테스트를 돌리는 해가 2026년이면 2026 을 박아 둔 상품과 구별되지 않는다). 그래서 프롬프트
 * 패키지에서는 {@code now()} 를 부르지 않고, 오늘 날짜는 두 팩토리만 주입받은 시계로 정한다.
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

	/**
	 * 기대 결과 파일의 고정 시계는 2026년이라, 그 파일 안에서는 오늘의 연도와 상품 정책으로 박아 둔 2026 이 똑같이 찍힌다.
	 * 그래서 어떤 상품이 연도를 어디서 가져오는지 바뀌어도 기대 결과 비교로는 잡히지 않는다. 여기서는 2027년 시계 하나로
	 * 모든 상품을 돌려, 오늘의 연도를 쓰는 상품과 2026 을 쓰는 상품(18, 101, 102, 106)이 갈리는지 본다.
	 */
	@Nested
	@DisplayName("2027년 시계로 만들면")
	class InYear2027 {

		// 2027-03-02 10:00 (서울).
		private final Clock clockIn2027 = clockAt("2027-03-02T10:00+09:00", "Asia/Seoul");

		@ParameterizedTest(name = "[{index}] 상품 {0} → {1}, {2}")
		@DisplayName("유료 사주 상품은 오늘의 연도를 쓰고, 신년 운세(18)만 2026년을 쓴다")
		@CsvSource(textBlock = """
			# 상품 번호, 기본 정보 줄의 현재 연도, 대운 기준연도
			 1, 현재 2027년, 기준연도=2027
			 2, 현재 2027년, 기준연도=2027
			 3, 현재 2027년, 기준연도=2027
			 5, 현재 2027년, 기준연도=2027
			 9, 현재 2027년, 기준연도=2027
			13, 현재 2027년, 기준연도=2027
			17, 현재 2027년, 기준연도=2027
			18, 현재 2026년, 기준연도=2026
			20, 현재 2027년, 기준연도=2027
			21, 현재 2027년, 기준연도=2027
			22, 현재 2027년, 기준연도=2027
			23, 현재 2027년, 기준연도=2027
			""")
		void sajuPromptYear(long subcategoryId, String currentYear, String daewoonReferenceYear) {
			// given
			SajuPromptFactory factory = new SajuPromptFactory(clockIn2027);

			// when
			String prompt = factory.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.person1(), "원피스"));

			// then
			assertThat(prompt).contains(currentYear);
			assertThat(prompt).contains(daewoonReferenceYear);
		}

		@ParameterizedTest(name = "[{index}] 상품 {0} → {1}, {2}")
		@DisplayName("무료 운세 상품은 오늘의 연도를 쓰고, 2026년을 두고 푸는 상품(101, 102, 106)만 2026년을 쓴다")
		@CsvSource(textBlock = """
			# 상품 번호, 기본 정보 줄의 현재 연도, 대운 기준연도
			101, 현재 2026년, 기준연도=2026
			102, 현재 2026년, 기준연도=2026
			103, 현재 2027년, 기준연도=2027
			104, 현재 2027년, 기준연도=2027
			105, 현재 2027년, 기준연도=2027
			106, 현재 2026년, 기준연도=2026
			""")
		void freePromptYear(long subcategoryId, String currentYear, String daewoonReferenceYear) {
			// given
			SajuPromptFactory factory = new SajuPromptFactory(clockIn2027);

			// when
			String prompt = factory.createFree(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.person1()));

			// then
			assertThat(prompt).contains(currentYear);
			assertThat(prompt).contains(daewoonReferenceYear);
		}

		/**
		 * 두 사람의 기본 정보 줄과 대운 줄을 사람마다 따로 확인한다. "현재 2027년" 이 한 번이라도 있는지만 보면 한 사람만
		 * 2026 으로 되돌아가도 통과한다.
		 */
		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@DisplayName("궁합 상품은 두 사람 모두 오늘의 연도로 기본 정보 줄과 대운 기준연도를 쓴다")
		@ValueSource(longs = {4, 6, 7, 8, 10, 11, 14, 15})
		void compatibilityPromptYearForBothPeople(long subcategoryId) {
			// given
			CompatibilityPromptFactory factory = new CompatibilityPromptFactory(clockIn2027);

			// when
			String prompt = factory.create(subcategoryId, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "원피스", "이은정", PromptFixtures.person2(), "귀멸의 칼날"));

			// then
			assertThat(prompt).as("첫 번째 사람(김태우)").contains(
				"이름: '김태우' | 남성 | 1990-01-01 12:00 | 현재 2027년",
				"[대운 고정값] 기준연도=2027, 현재대운=기사(己巳), 구간=35~44세, 시작연도=2025");
			assertThat(prompt).as("두 번째 사람(이은정)").contains(
				"이름: '이은정' | 여성 | 1995-08-15 03:30 | 현재 2027년",
				"[대운 고정값] 기준연도=2027, 현재대운=정해(丁亥), 구간=23~32세, 시작연도=2018");
		}

		@Test
		@DisplayName("재회운(19)은 오늘 날짜를 쓰는 세 자리 모두 한국 시각의 오늘 날짜를 쓴다")
		void reunionPromptDate() {
			// given
			CompatibilityPromptFactory factory = new CompatibilityPromptFactory(clockIn2027);

			// when
			String prompt = factory.create(19L, CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "이은정", PromptFixtures.person2()));

			// then
			assertThat(prompt).contains(
				"현재 시점: 2027년 03월 02일",
				"반드시 2027년 03월 02일 이후의 미래여야 합니다.",
				"현재 2027년 03월 02일 이후의 미래 시점만 제시해야 합니다.");
		}
	}

	private static Clock clockAt(String instant, String zone) {
		return Clock.fixed(OffsetDateTime.parse(instant).toInstant(), ZoneId.of(zone));
	}
}
