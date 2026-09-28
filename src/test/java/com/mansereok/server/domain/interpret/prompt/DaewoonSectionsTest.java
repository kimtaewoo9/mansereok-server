package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.calculator.DaewoonDirection;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
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

	/**
	 * 대운 방향은 만세력 계산이 성별과 년간 음양으로 정해 SajuInfo 에 싣고, 프롬프트는 그 값만 읽는다. 양남음녀 규칙 자체는
	 * ManseCalculationServiceTest 가 네 조합으로 확인한다.
	 *
	 * <p>여기서는 person1(남성·년간 甲 양, 원래 순행)에 방향을 일부러 바꿔 넣어도, 대운 칸의 두 줄("방향:", "흐름:")과 현재 대운 간지가
	 * 성별·년간이 아니라 실린 방향을 따르는지 본다. person1 의 월주는 乙丑(60갑자 1번), 대운 시작은 5세·1995년이고 기준 연도 2026
	 * 이면 현재 대운은 네 번째(35~44세)다. 순행이면 1 + 4 = 5번 己巳, 역행이면 1 - 4 = -3 → 57번 辛酉 다.
	 */
	@Nested
	@DisplayName("대운 방향")
	class Direction {

		private static final int REFERENCE_YEAR = 2026;

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(textBlock = """
			# 실린 방향, 시작 줄,             흐름 줄,                    현재 대운 줄
			FORWARD,  시작:5세 | 방향:순행, 대운 시작: 5세 | 흐름: 순행, ▶ 35~44세: 기사(己巳) (현재)
			BACKWARD, 시작:5세 | 방향:역행, 대운 시작: 5세 | 흐름: 역행, ▶ 35~44세: 신유(辛酉) (현재)
			""")
		@DisplayName("대운 칸의 방향 줄과 흐름 줄, 현재 대운 간지가 모두 SajuInfo 에 실린 방향을 따른다")
		void bothLinesFollowDirectionInSajuInfo(DaewoonDirection direction, String startLine, String flowLine,
			String currentLine) {
			// given
			ManseryeokCalculationResponse response = PromptFixtures.person1();
			response.getSaju().setDaewoonDirection(direction);

			// when
			String prompt = personDetailInfo(response);

			// then
			assertThat(prompt.lines()).contains(startLine, flowLine, currentLine);
		}

		/**
		 * 출생시간을 비우면 대운 시작 나이가 범위로만 있어서 "시작:4~6세 | 방향:… (출생시간 미입력 추정)" 줄을 따로 쓴다.
		 * 기대 결과 파일(1-time-unknown.txt)은 순행 한 가지뿐이라, 역행인 사람이 출생시간을 비운 경우를 여기서 함께 본다.
		 */
		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(textBlock = """
			# 실린 방향, 시작 줄
			FORWARD,  시작:4~6세 | 방향:순행 (출생시간 미입력 추정)
			BACKWARD, 시작:4~6세 | 방향:역행 (출생시간 미입력 추정)
			""")
		@DisplayName("출생시간을 비워 대운 시작 나이가 범위뿐이어도 방향 줄은 SajuInfo 에 실린 방향을 따른다")
		void rangeLineFollowsDirectionWhenBirthTimeUnknown(DaewoonDirection direction, String startLine) {
			// given
			ManseryeokCalculationResponse response = PromptFixtures.personTimeUnknown();
			response.getSaju().setDaewoonDirection(direction);

			// when
			String prompt = personDetailInfo(response);

			// then
			assertThat(prompt.lines()).contains(startLine);
		}

		@Test
		@DisplayName("실린 방향이 없으면 방향 줄은 물음표로 쓰고 대운 목록 대신 방향 정보가 없다고 쓴다")
		void writesUnknownWhenDirectionIsMissing() {
			// given
			ManseryeokCalculationResponse response = PromptFixtures.person1();
			response.getSaju().setDaewoonDirection(null);

			// when
			String prompt = personDetailInfo(response);

			// then
			assertThat(prompt.lines()).contains("시작:5세 | 방향:?", "대운 정보 없음 (방향 정보 누락)");
			assertThat(prompt).doesNotContain("흐름:");
		}

		private String personDetailInfo(ManseryeokCalculationResponse response) {
			StringBuilder prompt = new StringBuilder();
			SajuProfileSections.appendPersonDetailInfo(prompt, "테스트", response, REFERENCE_YEAR);
			return prompt.toString();
		}
	}
}
