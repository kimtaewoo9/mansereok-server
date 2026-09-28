package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.calculator.DaewoonDirection;
import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.MonthlyFortune;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import com.mansereok.server.support.fixture.ManseTableFixture;
import com.mansereok.server.support.fixture.ManseTableFixture.ManseRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 만세력 계산(네 기둥, 대운수, 대운 시작 연도, 불확정 안내, 월운)을 검증한다.
 *
 * <p>두 묶음으로 나눈다.
 * <ul>
 *   <li>손으로 만든 행: 조회 인자를 정확한 값으로 스텁한다. 코드가 다른 조회 메서드나 다른 시각으로 조회하면 MockitoExtension 의
 *   strict stubs 가 테스트를 실패시킨다. 대운 순행은 절입 시각과 같은 시각을 빼는 GreaterThan 조회를 쓴다. 이 행들의 간지와
 *   절입 시각은 조회 경로를 보려고 지어낸 값이라 같은 날짜의 실데이터와 다르다(예: 1990-01-01 의 연주는 실데이터로 己巳 인데 여기서는
 *   庚午 로 둔다).</li>
 *   <li>실데이터: 운영 DB 에 넣는 manses.sql 을 {@link ManseTableFixture} 로 읽어 대표 사주를 계산하고, 지금 코드가 내는 값을 그대로
 *   고정한다. 뒤 정리 PR 이 계산 결과를 바꾸면 이 표가 실패한다. 기대값은 외부 만세력과 대조한 값이 아니라 지금 출력이므로, 틀린
 *   계산도 함께 고정돼 있을 수 있다.</li>
 * </ul>
 *
 * <p>월운은 "지금" 이 든 절기부터 세므로 시계를 2026-09-26 12:00(서울)로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class ManseCalculationServiceTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 2026-09-26 12:00 (서울). 백로(2026-09-08 00:05)와 한로(2026-10-08 15:41) 사이다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"), SEOUL);

	private static ManseCalculationService serviceWith(ManseRepository manseRepository, Clock clock) {
		return new ManseCalculationService(
			manseRepository,
			new SajuDataService(),
			new UnseongCalculator(),
			new SinsalCalculator(),
			new RelationCalculator(),
			new YongsinCalculator(),
			clock
		);
	}

	private static ManseryeokCalculationRequest solarRequest(LocalDate solarDate, LocalTime solarTime,
		String gender) {
		return ManseryeokCalculationRequest.builder()
			.name("테스트").solarDate(solarDate).solarTime(solarTime).gender(gender).isLunar(false)
			.build();
	}

	/** 연주 월주 일주 시주를 "己巳 丁丑 壬辰 辛亥" 처럼 한 줄로 쓴다. 없는 기둥은 "--" 다. */
	private static String pillarsOf(SajuInfo saju) {
		return String.join(" ",
			chineseOf(saju.getYearSky()) + chineseOf(saju.getYearGround()),
			chineseOf(saju.getMonthSky()) + chineseOf(saju.getMonthGround()),
			chineseOf(saju.getDaySky()) + chineseOf(saju.getDayGround()),
			chineseOf(saju.getTimeSky()) + chineseOf(saju.getTimeGround()));
	}

	private static String chineseOf(PillarElement element) {
		return element == null ? "-" : element.getChinese();
	}

	/**
	 * 간지와 절입 시각은 조회 경로를 보려고 지어낸 값이며 실데이터와 다르다. 실데이터로 본 같은 날짜의 결과는 {@link WithRealTable}
	 * 에 있다.
	 */
	@Nested
	@DisplayName("손으로 만든 행으로 계산하면")
	class WithHandMadeRows {

		@Mock
		private ManseRepository manseRepository;

		private ManseCalculationService service;

		@BeforeEach
		void setUp() {
			service = serviceWith(manseRepository, FIXED_CLOCK);
		}

		@Test
		@DisplayName("출생시간이 없으면 시주를 비우고, 하루의 처음과 끝으로 찾은 대운수가 같으면 그 나이로 추정한다고 안내한다")
		void shouldSkipTimePillarWhenSolarTimeIsMissing() {
			// given
			LocalDate birthDate = LocalDate.of(1990, 1, 1);
			Manse nextSeason = ManseRow.on(LocalDate.of(1990, 1, 4))
				.season("소한", LocalDateTime.of(1990, 1, 4, 0, 0)).build();
			given(manseRepository.findBySolarDate(birthDate))
				.willReturn(Optional.of(ManseRow.on(birthDate).yearPillar("庚", "午").build()));
			// 시간을 모르면 그날 00:00:00 과 23:59:59 두 시각으로 다음 절입을 찾는다(연간을 庚 양간으로 지어 두어 남자라 순행)
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1990, 1, 1, 0, 0))).willReturn(Optional.of(nextSeason));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1990, 1, 1, 23, 59, 59))).willReturn(Optional.of(nextSeason));

			// when
			ManseryeokCalculationResponse response = service.calculate(solarRequest(birthDate, null, "MALE"));

			// then
			assertThat(response.getInput().getSolarTime()).isNull();
			assertThat(response.getInput().getTimeUnknown()).isTrue();
			assertThat(response.getSaju().getTimeSky()).isNull();
			assertThat(response.getSaju().getTimeGround()).isNull();
			assertThat(response.getSaju().getUncertaintyNotes()).containsExactly(
				"출생시간 미입력: 시주는 계산하지 않았습니다.",
				"출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.",
				"대운 시작 나이는 1세로 추정됩니다.");
		}

		@Test
		@DisplayName("음력을 양력으로 바꾼 뒤 23:30 이후 출생이면 일주만 다음 날로 넘기고, 대운은 바꾼 양력 날짜의 출생 시각으로 센다")
		void shouldApplyYajasiShiftAfterLunarToSolarConversion() {
			// given
			LocalDate lunarDate = LocalDate.of(1990, 1, 1);
			LocalDate civilSolarDate = LocalDate.of(1990, 1, 27);
			given(manseRepository.findAllByLunarDateOrderBySolarDateAsc(lunarDate)).willReturn(List.of(
				ManseRow.on(civilSolarDate).yearPillar("庚", "午").monthPillar("己", "丑").dayPillar("甲", "子").build()));
			// 다음 날 행은 일주만 바꾼다. 연주·월주를 이 행에서 읽으면 기본값(甲子 丙子)이 나와 실패한다.
			given(manseRepository.findBySolarDate(civilSolarDate.plusDays(1))).willReturn(Optional.of(
				ManseRow.on(civilSolarDate.plusDays(1)).dayPillar("乙", "丑").build()));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(civilSolarDate, LocalTime.of(23, 40)))).willReturn(Optional.of(
				ManseRow.on(LocalDate.of(1990, 1, 31)).season("입춘", LocalDateTime.of(1990, 1, 31, 0, 0)).build()));
			ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
				.name("테스트").solarDate(lunarDate).solarTime(LocalTime.of(23, 40)).gender("MALE").isLunar(true)
				.leapMonth(false)
				.build();

			// when
			SajuInfo saju = service.calculate(request).getSaju();

			// then
			assertThat(pillarsOf(saju)).isEqualTo("庚午 己丑 乙丑 丙子");
		}

		@Test
		@DisplayName("제보된 1998-09-02 12:02 남자 사주는 년주에 문창귀인, 일주에 월덕귀인이 붙는다")
		void shouldReturnExpandedGwiinForReportedCase() {
			// given
			LocalDate birthDate = LocalDate.of(1998, 9, 2);
			given(manseRepository.findBySolarDate(birthDate)).willReturn(Optional.of(
				ManseRow.on(birthDate).yearPillar("戊", "寅").monthPillar("庚", "申").dayPillar("壬", "子").build()));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1998, 9, 2, 12, 2))).willReturn(Optional.of(
				ManseRow.on(LocalDate.of(1998, 9, 7)).season("백로", LocalDateTime.of(1998, 9, 7, 0, 0)).build()));

			// when
			SajuInfo saju = service.calculate(solarRequest(birthDate, LocalTime.of(12, 2), "MALE")).getSaju();

			// then
			assertThat(saju.getSinsalInfo().get("년주")).contains("문창귀인");
			assertThat(saju.getSinsalInfo().get("일주")).contains("월덕귀인");
		}

		/**
		 * 제보 사례. 이 테스트의 원래 기대값은 대운수 2, 대운 시작 1995년이었다. fdd4ac2(대운수 경계값, 절입 정각 수정)가 절입까지
		 * 4일 미만이면 나머지 보정 없이 대운수 1 로 바로 돌려주게 바꾼 뒤로는 1, 1994년이 나온다. 바뀐 값이 맞는지는 아직 도메인
		 * 담당자에게 확인받지 않았다.
		 */
		@Test
		@DisplayName("1993-06-03 10:30 여자(癸 음간, 순행)는 망종(06-06 01:12)까지 4일 미만(2일 14시간)이라 대운수 1, 대운 시작 1994년이다")
		void shouldCalculateExpectedBigFortuneForFemale19930603Case() {
			// given
			LocalDate birthDate = LocalDate.of(1993, 6, 3);
			given(manseRepository.findBySolarDate(birthDate))
				.willReturn(Optional.of(ManseRow.on(birthDate).yearPillar("癸", "酉").build()));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1993, 6, 3, 10, 30))).willReturn(Optional.of(
				ManseRow.on(LocalDate.of(1993, 6, 6)).season("망종", LocalDateTime.of(1993, 6, 6, 1, 12)).build()));

			// when
			SajuInfo saju = service.calculate(solarRequest(birthDate, LocalTime.of(10, 30), "FEMALE"))
				.getSaju();

			// then
			assertThat(saju.getBigFortuneNumber()).isEqualTo(1);
			assertThat(saju.getBigFortuneStartYear()).isEqualTo(1994);
		}

		@Test
		@DisplayName("월운은 지금이 든 절기부터 다음 절입 1초 전까지를 한 달로 나누고, 다음 절입이 없는 달에서 멈춘다")
		void shouldCalculateMonthlyFortunesUsingSeasonBoundaries() {
			// given: 지금(2026-09-26 12:00)보다 앞선 절입 하나와 뒤의 절입 둘만 있는 표
			LocalDate birthDate = LocalDate.of(2026, 9, 24);
			ManseRepository threeBoundaries = ManseTableFixture.of(
				ManseRow.on(birthDate).build(),
				ManseRow.on(LocalDate.of(2026, 9, 25)).monthPillar("丙", "寅")
					.season("입춘", LocalDateTime.of(2026, 9, 25, 12, 0)).build(),
				ManseRow.on(LocalDate.of(2026, 10, 25)).monthPillar("丁", "卯")
					.season("경칩", LocalDateTime.of(2026, 10, 25, 12, 0)).build(),
				ManseRow.on(LocalDate.of(2026, 11, 24)).monthPillar("戊", "辰")
					.season("청명", LocalDateTime.of(2026, 11, 24, 12, 0)).build()
			).newRepository();

			// when
			List<MonthlyFortune> monthlyFortunes = serviceWith(threeBoundaries, FIXED_CLOCK)
				.calculate(solarRequest(birthDate, LocalTime.of(12, 0), "MALE"))
				.getSaju().getMonthlyFortunes();

			// then
			assertThat(monthlyFortunes)
				.extracting(MonthlyFortune::getSeason, MonthlyFortune::getPeriodStart, MonthlyFortune::getPeriodEnd,
					fortune -> chineseOf(fortune.getMonthSky()) + chineseOf(fortune.getMonthGround()))
				.containsExactly(
					tuple("입춘", LocalDateTime.of(2026, 9, 25, 12, 0), LocalDateTime.of(2026, 10, 25, 11, 59, 59),
						"丙寅"),
					tuple("경칩", LocalDateTime.of(2026, 10, 25, 12, 0), LocalDateTime.of(2026, 11, 24, 11, 59, 59),
						"丁卯"),
					tuple("청명", LocalDateTime.of(2026, 11, 24, 12, 0), null, "戊辰"));
		}

		/**
		 * 사용자 입력이 아니라 서버가 가진 표(음양 표, 시주 표)에 값이 없는 경우다. 원래 예외를 다른 예외로 감싸지 않아 로그에 처음 던진
		 * 곳의 스택이 그대로 남는다.
		 */
		@ParameterizedTest(name = "[{index}] {0}")
		@DisplayName("만세력 행의 간지가 서버의 음양 표나 시주 표에 없으면 IllegalStateException 을 감싸지 않고 그대로 던진다")
		@CsvSource(delimiter = '|', textBlock = """
			# 사례                                    | 연간 | 일간 | 오류 메시지
			연간이 음양 표에 없으면 대운 방향을 정하지 못한다 | X    | 甲   | 연간 X의 음양 정보를 찾을 수 없습니다
			일간이 시주 표에 없으면 시주를 정하지 못한다      | 庚   | X    | 일간 X의 시주 데이터를 찾을 수 없습니다
			""")
		void throwsIllegalStateWhenServerTableLacksStem(String description, String yearSky, String daySky,
			String message) {
			// given: 연간 庚 은 양간이라 남자는 다음 절입(소한)으로 대운을 센다
			LocalDate birthDate = LocalDate.of(1990, 1, 1);
			ManseRepository table = ManseTableFixture.of(
				ManseRow.on(birthDate).yearPillar(yearSky, "午").dayPillar(daySky, "子").build(),
				ManseRow.on(LocalDate.of(1990, 1, 5)).season("소한", LocalDateTime.of(1990, 1, 5, 12, 0)).build()
			).newRepository();
			ManseCalculationService serviceWithBrokenRow = serviceWith(table, FIXED_CLOCK);

			// when & then
			assertThatThrownBy(() -> serviceWithBrokenRow.calculate(solarRequest(birthDate, LocalTime.of(12, 0), "MALE")))
				.isExactlyInstanceOf(IllegalStateException.class)
				.hasMessage(message);
		}

		/**
		 * 지지 寅·巳·申·亥 는 여섯 짝 모두 관계가 있다(寅巳 형·해, 寅申 충·형, 巳申 형·파, 寅亥 파, 巳亥 충, 申亥 해). 천간 합·충은
		 * 이어진 글자끼리만 생겨 한 사주로 여섯 짝을 다 채울 수 없으므로, 관계가 생기는 짝이 서로 다른 두 사주로 나눠 본다. 어느 짝이
		 * 다른 기둥 글자를 읽으면 관계가 없던 짝에 관계가 생기거나 있던 관계가 빠진다.
		 *
		 * <p>시주는 일간과 출생시각으로 정해진다. 22:00 은 해시라 일간 戊 이면 癸亥, 일간 甲 이면 乙亥 다.
		 */
		@Nested
		@DisplayName("지지·천간 관계를 모을 때")
		class WhenCollectingRelations {

			private final LocalDate birthDate = LocalDate.of(2000, 1, 1);

			/** 연주·월주·일주만 정한 행 하나와, 연간이 양간이라 남자가 순행으로 대운을 셀 다음 절입 하나로 된 표. */
			private ManseCalculationService serviceWithPillars(Manse birthRow) {
				ManseRepository table = ManseTableFixture.of(
					birthRow,
					ManseRow.on(LocalDate.of(2000, 1, 6)).season("소한", LocalDateTime.of(2000, 1, 6, 12, 0)).build()
				).newRepository();
				return serviceWith(table, FIXED_CLOCK);
			}

			@Test
			@DisplayName("시주가 있으면 지지 여섯 짝을 년지-월지, 년지-일지, 월지-일지, 년지-시지, 월지-시지, 일지-시지 순서로 적는다")
			void listsSixGroundPairsInOrderWhenTimeIsKnown() {
				// given
				ManseCalculationService serviceWithRows = serviceWithPillars(ManseRow.on(birthDate)
					.yearPillar("戊", "寅").monthPillar("癸", "巳").dayPillar("戊", "申").build());

				// when
				SajuInfo saju = serviceWithRows.calculate(solarRequest(birthDate, LocalTime.of(22, 0), "MALE")).getSaju();

				// then
				assertThat(pillarsOf(saju)).as("준비한 네 기둥").isEqualTo("戊寅 癸巳 戊申 癸亥");
				assertThat(saju.getGroundRelations()).containsExactly(
					"년지-월지: 형, 해",
					"년지-일지: 충, 형",
					"월지-일지: 형, 파",
					"년지-시지: 파",
					"월지-시지: 충",
					"일지-시지: 해");
			}

			@Test
			@DisplayName("시주가 있으면 천간 짝 가운데 관계가 있는 짝만 년간-월간, 월간-일간, 년간-시간, 일간-시간 순서로 적는다")
			void listsSkyPairsWithRelationInOrderWhenTimeIsKnown() {
				// given: 戊·癸 는 합이고 같은 글자끼리는 관계가 없다
				ManseCalculationService serviceWithRows = serviceWithPillars(ManseRow.on(birthDate)
					.yearPillar("戊", "寅").monthPillar("癸", "巳").dayPillar("戊", "申").build());

				// when
				SajuInfo saju = serviceWithRows.calculate(solarRequest(birthDate, LocalTime.of(22, 0), "MALE")).getSaju();

				// then
				assertThat(saju.getSkyRelations()).containsExactly(
					"년간-월간: 천간합",
					"월간-일간: 천간합",
					"년간-시간: 천간합",
					"일간-시간: 천간합");
			}

			@Test
			@DisplayName("시주가 있으면 앞 사주에서 비었던 년간-일간, 월간-시간 짝도 관계가 있을 때 제자리에 적는다")
			void listsRemainingSkyPairsWhenTimeIsKnown() {
				// given: 庚甲 충, 庚乙 합, 辛乙 충이고 나머지 짝(庚辛, 辛甲, 甲乙)은 관계가 없다
				ManseCalculationService serviceWithRows = serviceWithPillars(ManseRow.on(birthDate)
					.yearPillar("庚", "寅").monthPillar("辛", "巳").dayPillar("甲", "申").build());

				// when
				SajuInfo saju = serviceWithRows.calculate(solarRequest(birthDate, LocalTime.of(22, 0), "MALE")).getSaju();

				// then
				assertThat(pillarsOf(saju)).as("준비한 네 기둥").isEqualTo("庚寅 辛巳 甲申 乙亥");
				assertThat(saju.getSkyRelations()).containsExactly(
					"년간-일간: 천간충",
					"년간-시간: 천간합",
					"월간-시간: 천간충");
			}

			@Test
			@DisplayName("출생시간을 몰라 시주가 없으면 연·월·일 세 기둥끼리의 짝만 적는다")
			void listsOnlyThreePillarPairsWhenTimeIsUnknown() {
				// given
				ManseCalculationService serviceWithRows = serviceWithPillars(ManseRow.on(birthDate)
					.yearPillar("戊", "寅").monthPillar("癸", "巳").dayPillar("戊", "申").build());

				// when
				SajuInfo saju = serviceWithRows.calculate(solarRequest(birthDate, null, "MALE")).getSaju();

				// then
				assertThat(pillarsOf(saju)).as("준비한 네 기둥").isEqualTo("戊寅 癸巳 戊申 --");
				assertThat(saju.getGroundRelations()).containsExactly(
					"년지-월지: 형, 해",
					"년지-일지: 충, 형",
					"월지-일지: 형, 파");
				assertThat(saju.getSkyRelations()).containsExactly(
					"년간-월간: 천간합",
					"월간-일간: 천간합");
			}
		}
	}

	@Nested
	@DisplayName("실데이터 만세력 표로 계산하면")
	class WithRealTable {

		private final ManseRepository realTable = ManseTableFixture.realTable().newRepository();
		private final ManseCalculationService service = serviceWith(realTable, FIXED_CLOCK);

		@ParameterizedTest(name = "[{index}] {0}")
		@DisplayName("대표 사주의 네 기둥, 대운수, 대운 시작 연도를 지금 코드가 내는 값 그대로 돌려준다")
		@CsvSource(delimiter = '|', textBlock = """
			# 사례                                                | 음력  | 날짜       | 시각  | 윤달  | 성별   | 연주 월주 일주 시주 | 대운수 | 시작 연도
			야자시 직전 23:29 는 그날 일주를 쓴다                    | false | 1990-01-27 | 23:29 |       | MALE   | 己巳 丁丑 壬辰 辛亥 | 7      | 1997
			야자시 23:30 부터는 일주만 다음 날로 넘긴다               | false | 1990-01-27 | 23:30 |       | MALE   | 己巳 丁丑 癸巳 壬子 | 7      | 1997
			입춘(2000-02-04 21:24) 1분 전은 연주·월주가 전 해·전 달   | false | 2000-02-04 | 21:23 |       | MALE   | 己卯 丁丑 壬辰 庚戌 | 10     | 2010
			입춘 정각은 절입이 지난 것으로 보고 다음 절입(경칩)까지 센다 | false | 2000-02-04 | 21:24 |       | MALE   | 庚辰 戊寅 壬辰 庚戌 | 10     | 2010
			망종(1993-06-06 01:12) 42분 전 순행은 4일 미만이라 대운수 1 | false | 1993-06-06 | 00:30 |       | FEMALE | 癸酉 丁巳 戊午 壬子 | 1      | 1994
			음력 2020년 윤4월 15일(양력 2020-06-06)                  | true  | 2020-04-15 | 12:00 | true  | MALE   | 庚子 壬午 庚辰 壬午 | 10     | 2030
			음력 2020년 평4월 15일(양력 2020-05-07)                  | true  | 2020-04-15 | 12:00 | false | MALE   | 庚子 辛巳 庚戌 壬午 | 10     | 2030
			음력 윤달 여부를 비우면 평달로 본다                       | true  | 2020-04-15 | 12:00 |       | MALE   | 庚子 辛巳 庚戌 壬午 | 10     | 2030
			戊 양간 해 남자는 순행(백로 1998-09-08 05:13 까지 5일)     | false | 1998-09-02 | 12:02 |       | MALE   | 戊寅 庚申 壬子 丙午 | 2      | 2000
			戊 양간 해 여자는 역행(입추 1998-08-08 02:21 부터 25일)    | false | 1998-09-02 | 12:02 |       | FEMALE | 戊寅 庚申 壬子 丙午 | 8      | 2006
			# 제보 사례의 원래 기대값은 대운수 2, 시작 1995 였다. fdd4ac2 의 "4일 미만이면 대운수 1" 이후 값이며 도메인 담당자 확인 전이다
			癸 음간 해 여자는 순행(망종까지 2일, 제보 사례)            | false | 1993-06-03 | 10:30 |       | FEMALE | 癸酉 丁巳 乙卯 辛巳 | 1      | 1994
			癸 음간 해 남자는 역행(입하 1993-05-05 20:59 부터 28일)    | false | 1993-06-03 | 10:30 |       | MALE   | 癸酉 丁巳 乙卯 辛巳 | 9      | 2002
			표 첫날 1900-01-01 여자(순행, 소한 1900-01-06 까지 4일)   | false | 1900-01-01 | 12:00 |       | FEMALE | 己亥 丙子 甲戌 庚午 | 1      | 1901
			표 끝 무렵 2100-12-30 여자(역행, 대설 2100-12-07 부터 23일) | false | 2100-12-30 | 12:00 |       | FEMALE | 庚申 戊子 丙午 甲午 | 8      | 2108
			""")
		void representativeSaju(String description, boolean lunar, LocalDate date, LocalTime time,
			Boolean leapMonth, String gender, String pillars, int bigFortuneNumber, int bigFortuneStartYear) {
			// given
			ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
				.name("대표 사주").solarDate(date).solarTime(time).gender(gender).isLunar(lunar).leapMonth(leapMonth)
				.build();

			// when
			SajuInfo saju = service.calculate(request).getSaju();

			// then
			assertThat(pillarsOf(saju)).as("네 기둥").isEqualTo(pillars);
			assertThat(saju)
				.extracting(SajuInfo::getBigFortuneNumber, SajuInfo::getBigFortuneNumberMin,
					SajuInfo::getBigFortuneNumberMax)
				.as("대운수와 그 최소·최대")
				.containsOnly(bigFortuneNumber);
			assertThat(saju)
				.extracting(SajuInfo::getBigFortuneStartYear, SajuInfo::getBigFortuneStartYearMin,
					SajuInfo::getBigFortuneStartYearMax)
				.as("대운 시작 연도와 그 최소·최대")
				.containsOnly(bigFortuneStartYear);
			assertThat(saju.getUncertaintyNotes()).as("출생시간을 알면 불확정 안내가 null 이 아닌 빈 목록이다").isEmpty();
		}

		/**
		 * 1990-01-27 23:29 남자(己巳 丁丑 壬辰 辛亥, 일간 壬)의 기둥 한 칸이 조회표에서 채우는 값을 모두 본다. 십성은 일간 壬(양수)에서 본
		 * 관계이고, 색은 오행마다 정해진 값(토 #FFD600, 수 #039BE5, 목 #4CAF50)이다.
		 */
		@Nested
		@DisplayName("기둥 한 칸을 채울 때")
		class WhenFillingPillarElement {

			private final SajuInfo saju = service.calculate(
				solarRequest(LocalDate.of(1990, 1, 27), LocalTime.of(23, 29), "MALE")).getSaju();

			@Test
			@DisplayName("천간은 한자·한글 이름·오행·색·일간 기준 십성·음양을 채운다")
			void fillsStemFromLookupTables() {
				// when
				List<PillarElement> stems = List.of(saju.getYearSky(), saju.getDaySky());

				// then
				assertThat(stems)
					.extracting(PillarElement::getChinese, PillarElement::getKorean, PillarElement::getFiveCircle,
						PillarElement::getFiveCircleColor, PillarElement::getTenStar, PillarElement::getMinusPlus)
					.containsExactly(
						tuple("己", "기", "토", "#FFD600", "정관", "음"),
						tuple("壬", "임", "수", "#039BE5", "비견", "양"));
			}

			@Test
			@DisplayName("지지의 지장간은 한자·한글 이름·오행·색·음양·비율과 일간 기준 십성을 채우고, 없는 칸은 비운다")
			void fillsHiddenStemsFromLookupTables() {
				// when
				JijangganInfo dayBranch = saju.getDayGround().getJijanggan();
				JijangganInfo timeBranch = saju.getTimeGround().getJijanggan();

				// then: 일지 辰 은 戊 乙 癸, 시지 亥 는 壬 甲 두 칸이다
				assertThat(List.of(dayBranch.getFirst(), dayBranch.getSecond(), dayBranch.getThird(),
					timeBranch.getFirst(), timeBranch.getSecond()))
					.extracting(JijangganElement::getChinese, JijangganElement::getKorean,
						JijangganElement::getFiveCircle, JijangganElement::getFiveCircleColor,
						JijangganElement::getMinusPlus, JijangganElement::getRate, JijangganElement::getTenStar)
					.containsExactly(
						tuple("戊", "무", "토", "#FFD600", "양", 18, "편관"),
						tuple("乙", "을", "목", "#4CAF50", "음", 9, "상관"),
						tuple("癸", "계", "수", "#039BE5", "음", 3, "겁재"),
						tuple("壬", "임", "수", "#039BE5", "양", 20, "비견"),
						tuple("甲", "갑", "목", "#4CAF50", "양", 10, "식신"));
				assertThat(timeBranch.getThird()).isNull();
			}

			@Test
			@DisplayName("지지는 일간 기준 12운성과 그 설명을 채운다")
			void fillsTwelveStagesOnBranches() {
				// when
				List<PillarElement> branches = List.of(saju.getYearGround(), saju.getMonthGround(), saju.getDayGround(),
					saju.getTimeGround());

				// then: 일간 壬 기준 巳 절, 丑 쇠, 辰 묘, 亥 건록
				assertThat(branches)
					.extracting(PillarElement::getChinese, PillarElement::getUnseong, PillarElement::getUnseongDescription)
					.containsExactly(
						tuple("巳", "절", "극복과 인내, 재기"),
						tuple("丑", "쇠", "쇠퇴의 시작, 정리"),
						tuple("辰", "묘", "잠재력 저장, 휴식"),
						tuple("亥", "건록", "왕성한 활동, 안정과 번영"));
			}

			@Test
			@DisplayName("같은 오행이면 천간·지지 글자와 지장간 글자의 색이 같다")
			void paintsSameElementWithSameColorInPillarsAndHiddenStems() {
				// when: 네 기둥 여덟 글자와 지지 네 개의 지장간 열한 글자. 목은 지장간(乙·甲)에만 있다
				List<Object> letters = List.of(
					saju.getYearSky(), saju.getYearGround(), saju.getMonthSky(), saju.getMonthGround(),
					saju.getDaySky(), saju.getDayGround(), saju.getTimeSky(), saju.getTimeGround(),
					saju.getYearGround().getJijanggan().getFirst(), saju.getYearGround().getJijanggan().getSecond(),
					saju.getYearGround().getJijanggan().getThird(),
					saju.getMonthGround().getJijanggan().getFirst(), saju.getMonthGround().getJijanggan().getSecond(),
					saju.getMonthGround().getJijanggan().getThird(),
					saju.getDayGround().getJijanggan().getFirst(), saju.getDayGround().getJijanggan().getSecond(),
					saju.getDayGround().getJijanggan().getThird(),
					saju.getTimeGround().getJijanggan().getFirst(), saju.getTimeGround().getJijanggan().getSecond());

				// then: 오행과 색 짝이 다섯 가지뿐이다. 한쪽 색만 바뀌면 짝이 여섯 가지가 된다
				assertThat(letters)
					.extracting("fiveCircle", "fiveCircleColor")
					.containsOnly(
						tuple("목", "#4CAF50"),
						tuple("화", "#F44336"),
						tuple("토", "#FFD600"),
						tuple("금", "#E0E0E0"),
						tuple("수", "#039BE5"));
			}
		}

		@Nested
		@DisplayName("출생시간을 모르면")
		class WhenBirthTimeIsUnknown {

			@Test
			@DisplayName("하루의 처음과 끝으로 센 대운수가 같으면 그 나이로 추정하고 대운수를 채운다")
			void estimatesSingleAgeWhenBothEndsOfDayAgree() {
				// when: 己 음간 해 남자라 역행. 대설(1989-12-07 12:25)부터 24일·25일 모두 대운수 8
				SajuInfo saju = service.calculate(solarRequest(LocalDate.of(1990, 1, 1), null, "MALE"))
					.getSaju();

				// then
				assertThat(pillarsOf(saju)).isEqualTo("己巳 丙子 丙寅 --");
				assertThat(saju)
					.extracting(SajuInfo::getBigFortuneNumber, SajuInfo::getBigFortuneNumberMin,
						SajuInfo::getBigFortuneNumberMax, SajuInfo::getBigFortuneStartYear,
						SajuInfo::getBigFortuneStartYearMin, SajuInfo::getBigFortuneStartYearMax)
					.containsExactly(8, 8, 8, 1998, 1998, 1998);
				assertThat(saju.getUncertaintyNotes()).containsExactly(
					"출생시간 미입력: 시주는 계산하지 않았습니다.",
					"출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.",
					"대운 시작 나이는 8세로 추정됩니다.");
			}

			@Test
			@DisplayName("하루의 처음과 끝에서 대운수가 갈리면 대운수와 시작 연도는 비우고 범위만 준다")
			void givesRangeWhenBothEndsOfDayDisagree() {
				// when: 庚 양간 해 남자라 순행. 경칩(2000-03-05 15:27)까지 00:00 은 5일(대운수 2), 23:59:59 는 4일(대운수 1)
				SajuInfo saju = service.calculate(solarRequest(LocalDate.of(2000, 2, 29), null, "MALE"))
					.getSaju();

				// then
				assertThat(pillarsOf(saju)).isEqualTo("庚辰 戊寅 丁巳 --");
				assertThat(saju)
					.extracting(SajuInfo::getBigFortuneNumber, SajuInfo::getBigFortuneNumberMin,
						SajuInfo::getBigFortuneNumberMax, SajuInfo::getBigFortuneStartYear,
						SajuInfo::getBigFortuneStartYearMin, SajuInfo::getBigFortuneStartYearMax)
					.containsExactly(null, 1, 2, null, 2001, 2002);
				assertThat(saju.getUncertaintyNotes()).containsExactly(
					"출생시간 미입력: 시주는 계산하지 않았습니다.",
					"출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.",
					"대운 시작 나이는 1~2세 범위입니다.");
			}

			@Test
			@DisplayName("절입일에 태어났으면 연주·월주 경계가 불확정이라고 안내하고 대운은 범위도 내지 않는다")
			void leavesBigFortuneEmptyOnSeasonStartDay() {
				// when: 1993-06-06 은 망종(01:12) 절입일
				SajuInfo saju = service.calculate(solarRequest(LocalDate.of(1993, 6, 6), null, "FEMALE"))
					.getSaju();

				// then: 연주·월주는 절입 뒤(그날 행) 값을 쓴다
				assertThat(pillarsOf(saju)).isEqualTo("癸酉 戊午 戊午 --");
				assertThat(saju)
					.extracting(SajuInfo::getBigFortuneNumber, SajuInfo::getBigFortuneNumberMin,
						SajuInfo::getBigFortuneNumberMax, SajuInfo::getBigFortuneStartYear,
						SajuInfo::getBigFortuneStartYearMin, SajuInfo::getBigFortuneStartYearMax)
					.containsOnlyNulls();
				assertThat(saju.getUncertaintyNotes()).containsExactly(
					"출생시간 미입력: 시주는 계산하지 않았습니다.",
					"출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.",
					"절입일 출생 + 시간 미입력으로 연주/월주 경계가 불확정입니다.",
					"출생시간 미입력으로 대운 시작 나이는 확정할 수 없습니다.");
			}
		}

		/**
		 * 대운 방향은 대운수만으로는 우연히 같은 값이 나올 수 있어, 대운을 셀 절입을 어느 쪽으로 찾았는지 조회 인자로 직접 본다.
		 * 월운도 같은 저장소를 조회하지만 인자가 시계의 "지금"(2026년)이라, 출생 시각을 인자로 받은 호출은 대운용 조회뿐이다.
		 */
		@Nested
		@DisplayName("대운을 셀 절입을 찾을 때")
		class WhenLookingUpSeasonForBigFortune {

			private final LocalDateTime birth = LocalDateTime.of(1998, 9, 2, 12, 2);

			@Test
			@DisplayName("양간(戊) 해에 태어난 남자는 출생 시각 뒤의 첫 절입(같은 시각 제외)으로 센다")
			void shouldUseForwardSeasonLookupForMaleWithYangYearStem() {
				// when
				service.calculate(solarRequest(birth.toLocalDate(), birth.toLocalTime(), "MALE"));

				// then
				then(realTable).should().findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(birth);
				then(realTable).should(never())
					.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(birth);
			}

			@Test
			@DisplayName("양간(戊) 해에 태어난 여자는 출생 시각 이전의 마지막 절입(같은 시각 포함)으로 센다")
			void shouldUseReverseSeasonLookupForFemaleWithYangYearStem() {
				// when
				service.calculate(solarRequest(birth.toLocalDate(), birth.toLocalTime(), "FEMALE"));

				// then
				then(realTable).should().findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(birth);
				then(realTable).should(never()).findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(any());
			}
		}

		/**
		 * 대운 방향은 응답에 실려 프롬프트가 그대로 읽는다. 성별 둘과 년간 음양 둘의 네 조합을 모두 본다.
		 */
		@Nested
		@DisplayName("대운 방향은")
		class WhenDecidingDaewoonDirection {

			@ParameterizedTest(name = "[{index}] {0}")
			@DisplayName("양간 해 남자와 음간 해 여자는 순행, 음간 해 남자와 양간 해 여자는 역행으로 응답에 싣는다")
			@CsvSource(delimiter = '|', textBlock = """
				# 사례            | 날짜       | 시각  | 성별   | 대운 방향
				戊 양간 해 남자   | 1998-09-02 | 12:02 | MALE   | FORWARD
				戊 양간 해 여자   | 1998-09-02 | 12:02 | FEMALE | BACKWARD
				癸 음간 해 여자   | 1993-06-03 | 10:30 | FEMALE | FORWARD
				癸 음간 해 남자   | 1993-06-03 | 10:30 | MALE   | BACKWARD
				""")
			void carriesDirectionByGenderAndYearStem(String description, LocalDate date, LocalTime time,
				String gender, DaewoonDirection expected) {
				// when
				SajuInfo saju = service.calculate(solarRequest(date, time, gender)).getSaju();

				// then
				assertThat(saju.getDaewoonDirection()).isEqualTo(expected);
			}

			@ParameterizedTest(name = "[{index}] {0} → \"{1}\"")
			@DisplayName("응답 JSON 의 saju.daewoon_direction 에는 enum 이름이 아니라 순행·역행이 나간다")
			@CsvSource(textBlock = """
				# 성별(1998-09-02 12:02, 戊 양간 해), JSON 값
				MALE,   순행
				FEMALE, 역행
				""")
			void writesDirectionLabelToJson(String gender, String label) throws Exception {
				// given
				ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
				ManseryeokCalculationResponse response = service.calculate(
					solarRequest(LocalDate.of(1998, 9, 2), LocalTime.of(12, 2), gender));

				// when
				JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

				// then
				assertThat(json.path("saju").path("daewoon_direction").asText()).isEqualTo(label);
			}
		}

		/**
		 * 불확정 안내는 출생시간을 알면 비고, 월운은 지금이 표의 첫 절입(1900-01-06 소한)보다 앞서면 빈다. 두 목록은 비어도 null 이
		 * 아니어서 쓰는 쪽이 null 을 검사하지 않아도 된다. 응답 JSON 에서는 예전에 null 로 나가던 두 필드를 빼고, 값이 null 이거나
		 * 빈 다른 필드는 그대로 싣는다.
		 */
		@Nested
		@DisplayName("불확정 안내와 월운이 비면")
		class WhenListsAreEmpty {

			// 1900-01-02 00:00 (서울). 표의 첫 절입보다 앞이라 월운을 셀 기준 절입이 없다.
			private final ManseCalculationService serviceBeforeFirstSeason = serviceWith(realTable,
				Clock.fixed(LocalDateTime.of(1900, 1, 2, 0, 0).atZone(SEOUL).toInstant(), SEOUL));

			// 1998-09-02 12:02 남자(戊寅 庚申 壬子 丙午). 절입일이 아니라 절입 시각이 없고 삼합도 없다.
			private final ManseryeokCalculationRequest request =
				solarRequest(LocalDate.of(1998, 9, 2), LocalTime.of(12, 2), "MALE");

			@Test
			@DisplayName("월운을 셀 기준 절입이 없으면 월운은 null 이 아닌 빈 목록이다")
			void monthlyFortunesAreEmptyListWhenCurrentSeasonIsMissing() {
				// when
				SajuInfo saju = serviceBeforeFirstSeason.calculate(request).getSaju();

				// then
				assertThat(saju.getMonthlyFortunes()).isNotNull().isEmpty();
			}

			@Test
			@DisplayName("응답 JSON 의 saju 에서 uncertainty_notes·monthly_fortunes 만 빠지고 null 이거나 빈 다른 필드는 남는다")
			void omitsOnlyTheTwoEmptyListsFromJson() throws Exception {
				// given
				ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
				ManseryeokCalculationResponse response = serviceBeforeFirstSeason.calculate(request);

				// when
				JsonNode saju = objectMapper.readTree(objectMapper.writeValueAsString(response)).path("saju");

				// then
				assertThat(saju.fieldNames()).toIterable().containsExactlyInAnyOrder(
					"big_fortune_number", "big_fortune_number_min", "big_fortune_number_max",
					"big_fortune_start_year", "big_fortune_start_year_min", "big_fortune_start_year_max",
					"daewoon_direction", "season_start_time",
					"year_sky", "year_ground", "month_sky", "month_ground",
					"day_sky", "day_ground", "time_sky", "time_ground",
					"sinsal_info", "has_goegang", "has_baekho", "gongmang",
					"ground_relations", "sky_relations", "samhap", "yongsin_info");
				assertThat(saju.get("season_start_time").isNull()).as("null 인 절입 시각은 남는다").isTrue();
				assertThat(saju.get("samhap").isEmpty()).as("빈 삼합 목록은 남는다").isTrue();
			}

			@Test
			@DisplayName("두 목록에 값이 있으면 응답 JSON 에 그대로 싣는다")
			void keepsNonEmptyListsInJson() throws Exception {
				// given: 출생시간을 모르면 불확정 안내가 생기고, 지금(2026-09-26)은 월운 12개월을 모두 센다
				ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
				ManseryeokCalculationResponse response = service.calculate(
					solarRequest(LocalDate.of(1990, 1, 1), null, "MALE"));

				// when
				JsonNode saju = objectMapper.readTree(objectMapper.writeValueAsString(response)).path("saju");

				// then
				assertThat(saju.path("uncertainty_notes")).extracting(JsonNode::asText).containsExactly(
					"출생시간 미입력: 시주는 계산하지 않았습니다.",
					"출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.",
					"대운 시작 나이는 8세로 추정됩니다.");
				assertThat(saju.path("monthly_fortunes")).hasSize(12);
			}
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@DisplayName("대운을 셀 절입이 표 범위 밖에 있으면 고칠 입력으로 보고 IllegalArgumentException 을 던진다")
		@CsvSource(delimiter = '|', textBlock = """
			# 사례                                                       | 날짜       | 성별 | 오류 메시지
			1900-01-01 남자(己 음간, 역행)는 표의 첫 절입(1900-01-06 소한) 전이다 | 1900-01-01 | MALE | 만세력 표에 출생 전의 절입이 없어 대운을 셀 수 없는 생년월일입니다.
			2100-12-30 남자(庚 양간, 순행)는 표의 마지막 절입(2100-12-07 대설) 뒤다 | 2100-12-30 | MALE | 만세력 표에 출생 뒤의 절입이 없어 대운을 셀 수 없는 생년월일입니다.
			""")
		void failsWhenSeasonIsOutsideTable(String description, LocalDate date, String gender, String message) {
			// when & then
			assertThatThrownBy(() -> service.calculate(solarRequest(date, LocalTime.of(12, 0), gender)))
				.isExactlyInstanceOf(IllegalArgumentException.class)
				.hasMessage(message);
		}

		/**
		 * 표에 없는 날짜는 사용자가 고쳐야 하는 입력이라 400 으로 나가는 IllegalArgumentException 이다. 생년월일은 개인정보이고 이
		 * 메시지는 경고 로그에 남으므로 메시지에 입력 날짜를 넣지 않는다.
		 */
		@Nested
		@DisplayName("입력 날짜를 만세력 표에서 찾을 수 없으면")
		class WhenInputDateIsNotInTable {

			@ParameterizedTest(name = "[{index}] {0}")
			@DisplayName("지원 범위를 알려 주는 IllegalArgumentException 을 던진다")
			@CsvSource(delimiter = '|', textBlock = """
				# 사례                                              | 음력  | 날짜       | 시각  | 오류 메시지
				양력 1899-12-31 은 표 첫날(1900-01-01) 전이다          | false | 1899-12-31 | 12:00 | 지원 범위(양력 1900-01-01~2100-12-31) 밖이거나 존재하지 않는 날짜입니다.
				음력 2019년 4월은 29일까지라 30일이 없다               | true  | 2019-04-30 | 12:00 | 지원 범위(양력 1900-01-01~2100-12-31) 밖이거나 존재하지 않는 음력 날짜입니다.
				2100-12-31 23:40 은 일주를 셀 다음 날이 표에 없다      | false | 2100-12-31 | 23:40 | 23:30 이후 출생은 다음 날로 일주를 세는데, 다음 날이 지원 범위(양력 1900-01-01~2100-12-31) 밖입니다.
				""")
			void throwsIllegalArgumentWithSupportedRange(String description, boolean lunar, LocalDate date,
				LocalTime time, String message) {
				// given
				ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
					.name("범위 밖").solarDate(date).solarTime(time).gender("MALE").isLunar(lunar)
					.build();

				// when & then
				assertThatThrownBy(() -> service.calculate(request))
					.isExactlyInstanceOf(IllegalArgumentException.class)
					.hasMessage(message);
			}
		}

		@Test
		@DisplayName("양력·음력 여부(isLunar)가 비어 있으면 양력으로 짐작하지 않고 IllegalArgumentException 을 던진다")
		void throwsIllegalArgumentWhenIsLunarIsMissing() {
			// given
			ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
				.name("달력 누락").solarDate(LocalDate.of(1990, 1, 27)).solarTime(LocalTime.of(12, 0)).gender("MALE")
				.isLunar(null)
				.build();

			// when & then
			assertThatThrownBy(() -> service.calculate(request))
				.isExactlyInstanceOf(IllegalArgumentException.class)
				.hasMessage("양력·음력 여부는 필수입니다.");
		}

		/**
		 * 시주는 23:30 부터 2시간씩 자·축·인… 이다. 1990-01-27 은 일간이 壬 이라 시주가 庚子 부터 시작하고, 다음 날(1990-01-28)은
		 * 일간이 癸 라 자시가 壬子 다. 경계마다 앞 시주의 마지막 분(hh:29), 그 분의 마지막 초(hh:29:59), 다음 시주의 첫 분(hh:30)을
		 * 넣는다.
		 */
		@ParameterizedTest(name = "[{index}] 1990-01-27 {0} → 일주 {1}, 시주 {2}")
		@DisplayName("시주 경계에서 hh:29:59 까지는 앞 시주, hh:30 부터는 다음 시주이고, 23:30 부터는 일주도 다음 날이다")
		@CsvSource(textBlock = """
			# 시각,     일주, 시주
			01:29,    壬辰, 庚子
			01:29:59, 壬辰, 庚子
			01:30,    壬辰, 辛丑
			03:29,    壬辰, 辛丑
			03:29:59, 壬辰, 辛丑
			03:30,    壬辰, 壬寅
			05:29,    壬辰, 壬寅
			05:29:59, 壬辰, 壬寅
			05:30,    壬辰, 癸卯
			07:29,    壬辰, 癸卯
			07:29:59, 壬辰, 癸卯
			07:30,    壬辰, 甲辰
			09:29,    壬辰, 甲辰
			09:29:59, 壬辰, 甲辰
			09:30,    壬辰, 乙巳
			11:29,    壬辰, 乙巳
			11:29:59, 壬辰, 乙巳
			11:30,    壬辰, 丙午
			13:29,    壬辰, 丙午
			13:29:59, 壬辰, 丙午
			13:30,    壬辰, 丁未
			15:29,    壬辰, 丁未
			15:29:59, 壬辰, 丁未
			15:30,    壬辰, 戊申
			17:29,    壬辰, 戊申
			17:29:59, 壬辰, 戊申
			17:30,    壬辰, 己酉
			19:29,    壬辰, 己酉
			19:29:59, 壬辰, 己酉
			19:30,    壬辰, 庚戌
			21:29,    壬辰, 庚戌
			21:29:59, 壬辰, 庚戌
			21:30,    壬辰, 辛亥
			23:29,    壬辰, 辛亥
			23:29:59, 壬辰, 辛亥
			23:30,    癸巳, 壬子
			""")
		void timePillarChangesExactlyAtHalfPast(LocalTime time, String dayPillar, String timePillar) {
			// when
			SajuInfo saju = service.calculate(solarRequest(LocalDate.of(1990, 1, 27), time, "MALE")).getSaju();

			// then
			assertThat(chineseOf(saju.getDaySky()) + chineseOf(saju.getDayGround())).as("일주").isEqualTo(dayPillar);
			assertThat(chineseOf(saju.getTimeSky()) + chineseOf(saju.getTimeGround())).as("시주").isEqualTo(timePillar);
		}

		@Test
		@DisplayName("하루를 30초 간격으로 모두 넣어도 시주가 빠지는 시각이 없다")
		void everyThirtySecondsOfDayHasTimePillar() {
			// given: 00:00:00 부터 23:59:30 까지 2,880 개 시각
			Stream<LocalTime> everyThirtySeconds = Stream.iterate(LocalTime.MIDNIGHT, time -> time.plusSeconds(30))
				.limit(2_880);

			// when
			List<LocalTime> timesWithoutTimePillar = everyThirtySeconds
				.filter(time -> service.calculate(solarRequest(LocalDate.of(1990, 1, 27), time, "MALE"))
					.getSaju().getTimeGround() == null)
				.toList();

			// then
			assertThat(timesWithoutTimePillar).as("시주가 빠진 출생시각").isEmpty();
		}

		/**
		 * 초가 붙은 출생시간은 두 경로로 들어온다. 단일 해석·유료 궁합·/calculate 요청 본문의 LocalTime 은 Jackson 이 "01:29:30" 을
		 * 받아 주고, 무료 궁합의 문자열 출생시간은 LocalTime.parse 가 받아 준다. 계산은 초를 버린 분 단위 시각을 쓰고 입력 정보에도
		 * 그 시각을 돌려준다.
		 */
		@Nested
		@DisplayName("출생시간에 초가 붙어 들어오면")
		class WhenBirthTimeHasSeconds {

			@Test
			@DisplayName("Jackson 으로 읽은 01:29:30 은 초를 버린 01:29 로 계산해 자시(庚子)가 된다")
			void jacksonTimeWithSecondsFallsInJasi() throws Exception {
				// given
				ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
				ManseryeokCalculationRequest request = objectMapper.readValue("""
					{"name":"초 입력","solarDate":"1990-01-27","solarTime":"01:29:30","gender":"MALE","isLunar":false}
					""", ManseryeokCalculationRequest.class);

				// when
				ManseryeokCalculationResponse response = service.calculate(request);

				// then
				assertThat(response.getInput().getSolarTime()).isEqualTo(LocalTime.of(1, 29));
				assertThat(pillarsOf(response.getSaju())).isEqualTo("己巳 丁丑 壬辰 庚子");
			}

			@Test
			@DisplayName("무료 궁합 입력의 \"03:29:45\" 는 LocalTime.parse 로 읽혀 초를 버린 03:29 로 계산해 축시(辛丑)가 된다")
			void parsedTimeWithSecondsFallsInChuksi() {
				// given
				ManseryeokCreateRequest freeRequest = new ManseryeokCreateRequest();
				freeRequest.setName("초 입력");
				freeRequest.setBirthday("1990/01/27");
				freeRequest.setBirthtime("03:29:45");
				freeRequest.setGender("MALE");
				freeRequest.setCalendar("S");

				// when
				ManseryeokCalculationResponse response = service.calculate(ManseryeokCalculationRequest.from(freeRequest));

				// then
				assertThat(response.getInput().getSolarTime()).isEqualTo(LocalTime.of(3, 29));
				assertThat(pillarsOf(response.getSaju())).isEqualTo("己巳 丁丑 壬辰 辛丑");
			}
		}

		@Test
		@DisplayName("월운은 지금(2026-09-26 12:00)이 든 백로부터 12개 절기를 다음 절입 1초 전까지로 나눠 돌려준다")
		void monthlyFortunesCoverTwelveSeasonsFromNow() {
			// when
			List<MonthlyFortune> monthlyFortunes = service
				.calculate(solarRequest(LocalDate.of(1998, 9, 2), LocalTime.of(12, 2), "MALE"))
				.getSaju().getMonthlyFortunes();

			// then
			assertThat(monthlyFortunes)
				.extracting(MonthlyFortune::getSeason, MonthlyFortune::getPeriodStart, MonthlyFortune::getPeriodEnd,
					fortune -> chineseOf(fortune.getMonthSky()) + chineseOf(fortune.getMonthGround()))
				.containsExactly(
					tuple("백로", LocalDateTime.of(2026, 9, 8, 0, 5), LocalDateTime.of(2026, 10, 8, 15, 40, 59), "丁酉"),
					tuple("한로", LocalDateTime.of(2026, 10, 8, 15, 41), LocalDateTime.of(2026, 11, 7, 18, 47, 59), "戊戌"),
					tuple("입동", LocalDateTime.of(2026, 11, 7, 18, 48), LocalDateTime.of(2026, 12, 7, 11, 37, 59), "己亥"),
					tuple("대설", LocalDateTime.of(2026, 12, 7, 11, 38), LocalDateTime.of(2027, 1, 5, 22, 50, 59), "庚子"),
					tuple("소한", LocalDateTime.of(2027, 1, 5, 22, 51), LocalDateTime.of(2027, 2, 4, 10, 26, 59), "辛丑"),
					tuple("입춘", LocalDateTime.of(2027, 2, 4, 10, 27), LocalDateTime.of(2027, 3, 6, 4, 29, 59), "壬寅"),
					tuple("경칩", LocalDateTime.of(2027, 3, 6, 4, 30), LocalDateTime.of(2027, 4, 5, 9, 21, 59), "癸卯"),
					tuple("청명", LocalDateTime.of(2027, 4, 5, 9, 22), LocalDateTime.of(2027, 5, 6, 2, 44, 59), "甲辰"),
					tuple("입하", LocalDateTime.of(2027, 5, 6, 2, 45), LocalDateTime.of(2027, 6, 6, 6, 57, 59), "乙巳"),
					tuple("망종", LocalDateTime.of(2027, 6, 6, 6, 58), LocalDateTime.of(2027, 7, 7, 17, 14, 59), "丙午"),
					tuple("소서", LocalDateTime.of(2027, 7, 7, 17, 15), LocalDateTime.of(2027, 8, 8, 3, 1, 59), "丁未"),
					tuple("입추", LocalDateTime.of(2027, 8, 8, 3, 2), LocalDateTime.of(2027, 9, 8, 5, 53, 59), "戊申"));
		}

		@ParameterizedTest(name = "[{index}] 지금 {0} → {1}개월, 마지막 달 {2} {3} ~ {4}")
		@DisplayName("표 끝(2100-12-07 대설)에 가까우면 남은 절입만큼만 월운을 세고, 다음 절입이 없는 마지막 달은 끝을 비운다")
		@CsvSource(textBlock = """
			# 지금(서울),        달 수, 마지막 달 절기, 마지막 달 시작,     마지막 달 끝(다음 절입이 없으면 비움)
			# 대설 2099 부터 대설 2100 까지 절입 13개가 남아 12개월을 모두 채운다
			2099-12-07T04:15, 12,    입동,          2100-11-07T17:14, 2100-12-07T10:03:59
			# 소한 2100 부터는 12개만 남아 12번째 달(대설 2100)의 끝이 없다
			2100-01-05T15:28, 12,    대설,          2100-12-07T10:04,
			2100-06-01T12:00, 8,     대설,          2100-12-07T10:04,
			2100-12-31T12:00, 1,     대설,          2100-12-07T10:04,
			""")
		void countsOnlyRemainingSeasonsNearEndOfTable(LocalDateTime nowInSeoul, int months, String lastSeason,
			LocalDateTime lastPeriodStart, LocalDateTime lastPeriodEnd) {
			// given
			ManseCalculationService serviceAtNow = serviceWith(realTable,
				Clock.fixed(nowInSeoul.atZone(SEOUL).toInstant(), SEOUL));

			// when
			List<MonthlyFortune> monthlyFortunes = serviceAtNow
				.calculate(solarRequest(LocalDate.of(1998, 9, 2), LocalTime.of(12, 2), "MALE"))
				.getSaju().getMonthlyFortunes();

			// then
			assertThat(monthlyFortunes).hasSize(months);
			assertThat(monthlyFortunes).last()
				.extracting(MonthlyFortune::getSeason, MonthlyFortune::getPeriodStart, MonthlyFortune::getPeriodEnd)
				.containsExactly(lastSeason, lastPeriodStart, lastPeriodEnd);
		}

		@ParameterizedTest(name = "[{index}] 시계 {0} ({1}) → 첫 달 {2}")
		@DisplayName("월운의 첫 달은 서버 시간대와 상관없이 서울 시각으로 절입 시각에 이른 절기다(한로 2026-10-08 15:41)")
		@CsvSource(textBlock = """
			# 시계가 가리키는 순간(UTC), 시계의 시간대, 첫 달 절기
			2026-10-08T06:40:59Z, Asia/Seoul, 백로
			2026-10-08T06:41:00Z, Asia/Seoul, 한로
			2026-10-08T06:41:00Z, UTC,        한로
			""")
		void firstMonthStartsAtSeasonStartTimeInSeoul(Instant now, ZoneId clockZone, String firstSeason) {
			// given
			ManseCalculationService serviceAtNow = serviceWith(realTable, Clock.fixed(now, clockZone));

			// when
			List<MonthlyFortune> monthlyFortunes = serviceAtNow
				.calculate(solarRequest(LocalDate.of(1998, 9, 2), LocalTime.of(12, 2), "MALE"))
				.getSaju().getMonthlyFortunes();

			// then
			assertThat(monthlyFortunes).first().extracting(MonthlyFortune::getSeason).isEqualTo(firstSeason);
		}
	}
}
