package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 오늘 일진과 대운 방향을 <b>프로덕션 함수를 쓰지 않고</b> 손으로 계산한 값으로 고정한다.
 *
 * <p>PromptMatchesExpectedFileTest 는 기대값을 {@code DaewoonSections.calculateTodayDayPillar} 로 만들기 때문에
 * 그 함수 안을 어떻게 훼손해도 기대값과 실제값이 같이 틀려서 54개 기대 결과 파일이 전부 통과한다(자기참조 오라클).
 * 그래서 일진의 진짜 오라클은 이 파일이다. 아래 기대값은 전부 손으로 계산했고 근거를 주석에 남겼으므로
 * 절대 프로덕션 함수로 다시 만들지 말 것.
 *
 * <p>계산 규칙(프로덕션 구현이 아니라 명리 규칙 자체):
 * <ul>
 *   <li>기준일 1900-01-01 은 60갑자 10번 인덱스, 갑술(甲戌)이다.</li>
 *   <li>인덱스 = (10 + 기준일로부터의 경과일수) mod 60. 음수면 60을 더한다.</li>
 *   <li>인덱스 i 의 천간 = i mod 10 (갑을병정무기경신임계),
 *       지지 = i mod 12 (자축인묘진사오미신유술해).</li>
 *   <li>표기는 "한글천간+한글지지(한자천간+한자지지)". 사이에 공백이 없다.</li>
 * </ul>
 */
@DisplayName("대운·일진 계산")
class DaewoonSectionsTest {

	@Nested
	@DisplayName("오늘 일진")
	class TodayDayPillar {

		@Test
		@DisplayName("기준일 1900-01-01 은 갑술이다")
		void baseDateIsGapsul() {
			// 경과일수 0 → 인덱스 (10 + 0) % 60 = 10
			// 천간 10 % 10 = 0 → 갑(甲), 지지 10 % 12 = 10 → 술(戌)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 1, 1)))
				.isEqualTo("갑술(甲戌)");
		}

		@Test
		@DisplayName("기준일 다음 날은 60갑자가 한 칸 나아가 을해가 된다")
		void nextDayAdvancesOneStep() {
			// 경과일수 1 → 인덱스 11
			// 천간 11 % 10 = 1 → 을(乙), 지지 11 % 12 = 11 → 해(亥)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 1, 2)))
				.isEqualTo("을해(乙亥)");
		}

		@Test
		@DisplayName("10일 뒤에는 천간만 제자리로 돌아오고 지지는 두 칸 밀린다")
		void heavenlyStemCyclesEveryTenDays() {
			// 경과일수 10 → 인덱스 20
			// 천간 20 % 10 = 0 → 갑(甲) (기준일과 같음), 지지 20 % 12 = 8 → 신(申)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 1, 11)))
				.isEqualTo("갑신(甲申)");
		}

		@Test
		@DisplayName("12일 뒤에는 지지만 제자리로 돌아오고 천간은 두 칸 밀린다")
		void earthlyBranchCyclesEveryTwelveDays() {
			// 경과일수 12 → 인덱스 22
			// 천간 22 % 10 = 2 → 병(丙), 지지 22 % 12 = 10 → 술(戌) (기준일과 같음)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 1, 13)))
				.isEqualTo("병술(丙戌)");
		}

		@Test
		@DisplayName("60일 뒤에는 기준일과 같은 갑술로 돌아온다")
		void sixtyDaysLaterRepeats() {
			// 1900-01-01 + 60일: 1월 31일(+30) → 2월 28일(+58, 1900년은 평년) → 3월 2일(+60)
			// 인덱스 (10 + 60) % 60 = 10 → 갑술(甲戌)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 3, 2)))
				.isEqualTo("갑술(甲戌)");
		}

		@Test
		@DisplayName("기준일 이전 날짜도 60갑자를 거꾸로 따라간다")
		void beforeBaseDateWalksBackwards() {
			// 1899-12-01 → 1900-01-01 까지 31일이므로 경과일수 -31
			// 10 - 31 = -21, 자바에서 -21 % 60 = -21 이므로 60을 더해 39
			// 천간 39 % 10 = 9 → 계(癸), 지지 39 % 12 = 3 → 묘(卯)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1899, 12, 1)))
				.isEqualTo("계묘(癸卯)");
		}

		@Test
		@DisplayName("음수 나머지가 정확히 -60이 되는 날도 갑자로 보정된다")
		void negativeRemainderExactlyOneCycle() {
			// 1899-10-23 → 10월 남은 8일 + 11월 30일 + 12월 31일 + 1월 1일 = 70일,
			// 즉 경과일수 -70. 10 - 70 = -60 이고 -60 % 60 = 0 이라 보정 없이 인덱스 0.
			// 천간 0 → 갑(甲), 지지 0 → 자(子)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(1899, 10, 23)))
				.isEqualTo("갑자(甲子)");
		}

		@Test
		@DisplayName("100년 뒤인 2000-01-01 은 무오다")
		void millenniumDayIsMuo() {
			// 1900-01-01 → 2000-01-01 은 365 * 100 + 윤일 24일 = 36524일.
			// (1904년부터 1996년까지 4년마다 24번이고, 1900년은 400의 배수가 아니라 평년이다.)
			// 10 + 36524 = 36534, 36534 = 60 * 608 + 54 이므로 인덱스 54
			// 천간 54 % 10 = 4 → 무(戊), 지지 54 % 12 = 6 → 오(午)
			assertThat(DaewoonSections.calculateTodayDayPillar(LocalDate.of(2000, 1, 1)))
				.isEqualTo("무오(戊午)");
		}

		@Test
		@DisplayName("반환 형식은 한글 두 글자 뒤에 괄호로 한자 두 글자를 붙이고 공백이 없다")
		void formatHasNoWhitespace() {
			String pillar = DaewoonSections.calculateTodayDayPillar(LocalDate.of(1900, 1, 1));

			assertThat(pillar).hasSize(6); // 한글 2 + 괄호 2 + 한자 2
			assertThat(pillar).doesNotContain(" ");
			assertThat(pillar).matches("[가-힣]{2}\\(.{2}\\)");
		}

		@Test
		@DisplayName("서로 다른 60일 안의 날짜는 모두 다른 일진을 갖는다")
		void sixtyConsecutiveDaysAreAllDistinct() {
			LocalDate start = LocalDate.of(1900, 1, 1);

			assertThat(java.util.stream.IntStream.range(0, 60)
				.mapToObj(i -> DaewoonSections.calculateTodayDayPillar(start.plusDays(i)))
				.distinct()
				.count()).isEqualTo(60);
		}
	}

	@Nested
	@DisplayName("대운 방향")
	class Direction {

		/**
		 * 양남음녀는 순행, 음남양녀는 역행이다. 픽스처 세 개가 덮는 조합은
		 * 남성·양(순행), 여성·음(순행), 남성·음(역행) 뿐이라 여성·양(역행)은 여기서만 고정된다.
		 */
		@ParameterizedTest(name = "{0} 이고 년간이 {1} 이면 {2}")
		@CsvSource({
			"MALE, 양, 순행",
			"MALE, 음, 역행",
			"FEMALE, 음, 순행",
			"FEMALE, 양, 역행"
		})
		void followsYangMaleYinFemaleRule(String gender, String yearSkyMinusPlus, String expected) {
			assertThat(DaewoonSections.getDaewoonDirection(sajuWithYearSky(yearSkyMinusPlus),
				gender)).isEqualTo(expected);
		}

		@Test
		@DisplayName("년간 음양을 알 수 없으면 물음표를 돌려준다")
		void unknownPolarityReturnsQuestionMark() {
			assertThat(DaewoonSections.getDaewoonDirection(sajuWithYearSky(null), "MALE"))
				.isEqualTo("?");
			assertThat(DaewoonSections.getDaewoonDirection(SajuInfo.builder().build(), "MALE"))
				.isEqualTo("?");
		}

		private SajuInfo sajuWithYearSky(String minusPlus) {
			return SajuInfo.builder()
				.yearSky(PillarElement.builder().minusPlus(minusPlus).build())
				.build();
		}
	}
}
