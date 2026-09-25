package com.mansereok.server.domain.interpret.service;

import static com.mansereok.server.support.fixture.ManseTableFixture.manse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.MonthlyFortune;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import com.mansereok.server.support.fixture.ManseTableFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 만세력 계산(네 기둥, 대운수, 대운 시작 연도, 불확정 안내, 월운)을 검증한다.
 *
 * <p>두 묶음으로 나눈다.
 * <ul>
 *   <li>손으로 만든 행: 조회 인자를 정확한 값으로 스텁한다. 코드가 다른 조회 메서드나 다른 시각으로 조회하면 MockitoExtension 의
 *   strict stubs 가 테스트를 실패시킨다. 대운 순행은 절입 시각과 같은 시각을 빼는 GreaterThan 조회를 쓴다.</li>
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
		return new ManseryeokCalculationRequest("테스트", solarDate, solarTime, gender, false, null);
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
			Manse nextSeason = manse(LocalDate.of(1990, 1, 4), "庚", "午", "戊", "子", "丁", "卯", "소한",
				LocalDateTime.of(1990, 1, 4, 0, 0));
			given(manseRepository.findBySolarDate(birthDate))
				.willReturn(Optional.of(manse(birthDate, "庚", "午", "戊", "子", "甲", "子", null, null)));
			// 시간을 모르면 그날 00:00:00 과 23:59:59 두 시각으로 다음 절입을 찾는다(庚 양간 남자라 순행)
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
				manse(civilSolarDate, "庚", "午", "己", "丑", "甲", "子", null, null)));
			given(manseRepository.findBySolarDate(civilSolarDate.plusDays(1))).willReturn(Optional.of(
				manse(civilSolarDate.plusDays(1), "庚", "午", "己", "丑", "乙", "丑", null, null)));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(civilSolarDate, LocalTime.of(23, 40)))).willReturn(Optional.of(
				manse(LocalDate.of(1990, 1, 31), "庚", "午", "庚", "寅", "戊", "午", "입춘",
					LocalDateTime.of(1990, 1, 31, 0, 0))));
			ManseryeokCalculationRequest request = new ManseryeokCalculationRequest("테스트", lunarDate,
				LocalTime.of(23, 40), "MALE", true, false);

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
			given(manseRepository.findBySolarDate(birthDate))
				.willReturn(Optional.of(manse(birthDate, "戊", "寅", "庚", "申", "壬", "子", null, null)));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1998, 9, 2, 12, 2))).willReturn(Optional.of(
				manse(LocalDate.of(1998, 9, 7), "戊", "寅", "辛", "酉", "丁", "巳", "백로",
					LocalDateTime.of(1998, 9, 7, 0, 0))));

			// when
			SajuInfo saju = service.calculate(solarRequest(birthDate, LocalTime.of(12, 2), "MALE")).getSaju();

			// then
			assertThat(saju.getSinsalInfo().get("년주")).contains("문창귀인");
			assertThat(saju.getSinsalInfo().get("일주")).contains("월덕귀인");
		}

		@Test
		@DisplayName("1993-06-03 10:30 여자(癸 음간, 순행)는 망종(06-06 01:12)까지 4일 미만(2일 14시간)이라 대운수 1, 대운 시작 1994년이다")
		void shouldCalculateExpectedBigFortuneForFemale19930603Case() {
			// given
			LocalDate birthDate = LocalDate.of(1993, 6, 3);
			given(manseRepository.findBySolarDate(birthDate))
				.willReturn(Optional.of(manse(birthDate, "癸", "酉", "丁", "巳", "乙", "卯", null, null)));
			given(manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1993, 6, 3, 10, 30))).willReturn(Optional.of(
				manse(LocalDate.of(1993, 6, 6), "癸", "酉", "戊", "午", "戊", "午", "망종",
					LocalDateTime.of(1993, 6, 6, 1, 12))));

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
				manse(birthDate, "甲", "子", "乙", "丑", "甲", "子", null, null),
				manse(LocalDate.of(2026, 9, 25), "甲", "子", "丙", "寅", "乙", "丑", "입춘",
					LocalDateTime.of(2026, 9, 25, 12, 0)),
				manse(LocalDate.of(2026, 10, 25), "甲", "子", "丁", "卯", "丙", "寅", "경칩",
					LocalDateTime.of(2026, 10, 25, 12, 0)),
				manse(LocalDate.of(2026, 11, 24), "甲", "子", "戊", "辰", "丁", "卯", "청명",
					LocalDateTime.of(2026, 11, 24, 12, 0))
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
			癸 음간 해 여자는 순행(망종까지 2일, 제보 사례)            | false | 1993-06-03 | 10:30 |       | FEMALE | 癸酉 丁巳 乙卯 辛巳 | 1      | 1994
			癸 음간 해 남자는 역행(입하 1993-05-05 20:59 부터 28일)    | false | 1993-06-03 | 10:30 |       | MALE   | 癸酉 丁巳 乙卯 辛巳 | 9      | 2002
			표 첫날 1900-01-01 여자(순행, 소한 1900-01-06 까지 4일)   | false | 1900-01-01 | 12:00 |       | FEMALE | 己亥 丙子 甲戌 庚午 | 1      | 1901
			표 끝 무렵 2100-12-30 여자(역행, 대설 2100-12-07 부터 23일) | false | 2100-12-30 | 12:00 |       | FEMALE | 庚申 戊子 丙午 甲午 | 8      | 2108
			""")
		void representativeSaju(String description, boolean lunar, LocalDate date, LocalTime time,
			Boolean leapMonth, String gender, String pillars, int bigFortuneNumber, int bigFortuneStartYear) {
			// given
			ManseryeokCalculationRequest request = new ManseryeokCalculationRequest("대표 사주", date, time,
				gender, lunar, leapMonth);

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
			assertThat(saju.getUncertaintyNotes()).as("출생시간을 알면 불확정 안내가 없다").isNull();
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

		@ParameterizedTest(name = "[{index}] {0}")
		@DisplayName("대운을 셀 절입이 표 범위 밖에 있으면 계산이 실패한다")
		@CsvSource(delimiter = '|', textBlock = """
			# 사례                                                       | 날짜       | 성별 | 오류 메시지
			1900-01-01 남자(己 음간, 역행)는 표의 첫 절입(1900-01-06 소한) 전이다 | 1900-01-01 | MALE | 만세력 계산 중 오류가 발생했습니다: 역행 절입 시간을 찾을 수 없습니다
			2100-12-30 남자(庚 양간, 순행)는 표의 마지막 절입(2100-12-07 대설) 뒤다 | 2100-12-30 | MALE | 만세력 계산 중 오류가 발생했습니다: 순행 절입 시간을 찾을 수 없습니다
			""")
		void failsWhenSeasonIsOutsideTable(String description, LocalDate date, String gender, String message) {
			// when & then
			assertThatThrownBy(() -> service.calculate(solarRequest(date, LocalTime.of(12, 0), gender)))
				.isExactlyInstanceOf(RuntimeException.class)
				.hasMessage(message);
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
