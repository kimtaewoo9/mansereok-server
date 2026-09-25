package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.controller.ManseryeokController;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import com.mansereok.server.domain.payment.service.PaymentService;
import com.mansereok.server.support.fixture.ManseTableFixture;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 만세력 계산 한 번이 manses 를 몇 번 조회하는지 고정한다.
 *
 * <p>계산 결과는 ManseCalculationServiceTest 의 대표 사주 표가 본다. 이 클래스는 결과가 같은 채로 조회만 다시 늘어나는 회귀를
 * 잡는다. 월운은 예전에 현재 절입 1번에 12개월마다 2번씩 모두 25번 조회했고, 절반은 이미 읽은 행을 다시 읽었다. 지금은 현재 절입
 * 1번과 절입 13개 1번이다. /api/v1/manseryeok/calculate 는 로그인 없이 부를 수 있고 궁합은 계산을 두 번 하므로, 조회 수가 곧 요청
 * 하나가 DB 에 주는 부하다. 그래서 조회는 스텁만 하고 확인하지 않는다는 원칙과 달리 여기서는 조회 횟수 자체를 확인한다.
 *
 * <p>조회 기록은 ManseTableFixture 가 실데이터로 답하는 목 저장소에서 센다. 이 목은 흉내 내지 않는 조회를 부르면 예외를 던지므로,
 * 표에 없는 조회가 끼어들면 계산이 실패한다.
 *
 * <p>월운은 "지금" 이 든 절기부터 세므로 시계를 2026-09-26 12:00(서울)로 고정한다. 이때 현재 절입은 백로(2026-09-08 00:05)다.
 */
@ExtendWith(MockitoExtension.class)
class ManseCalculationQueryCountTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 12, 0);
	private static final LocalDateTime CURRENT_SEASON_START = LocalDateTime.of(2026, 9, 8, 0, 5);
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);

	private final ManseRepository manseRepository = ManseTableFixture.realTable().newRepository();
	private final ManseCalculationService service = new ManseCalculationService(manseRepository,
		new SajuDataService(), new UnseongCalculator(), new SinsalCalculator(), new RelationCalculator(),
		new YongsinCalculator(), FIXED_CLOCK);

	/** 목 저장소가 받은 조회 수. toString 같은 Object 메서드 호출은 세지 않는다. */
	private long manseQueries() {
		return mockingDetails(manseRepository).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getDeclaringClass() != Object.class)
			.count();
	}

	@Nested
	@DisplayName("한 사람을 계산하면")
	class OnePerson {

		@ParameterizedTest(name = "[{index}] {0} → 조회 {6}번")
		@DisplayName("월운은 현재 절입 1번과 절입 13개 1번으로 2번만 조회하고, 계산 전체 조회는 출생 정보에 따라 3~5번이다")
		@CsvSource(delimiter = '|', textBlock = """
			# 사례(조회 내역)                                        | 음력  | 날짜       | 시각  | 윤달  | 성별   | 전체 조회
			양력 날짜 1 + 대운 순행 1 + 월운 2                          | false | 1998-09-02 | 12:02 |       | MALE   | 4
			양력 날짜 1 + 대운 역행 1 + 월운 2                          | false | 1998-09-02 | 12:02 |       | FEMALE | 4
			음력 윤달: 평달·윤달 후보 목록 1(다시 조회하지 않음) + 대운 1 + 월운 2 | true  | 2020-04-15 | 12:00 | true  | MALE   | 4
			음력 평달: 평달·윤달 후보 목록 1(다시 조회하지 않음) + 대운 1 + 월운 2 | true  | 2020-04-15 | 12:00 | false | MALE   | 4
			야자시 23:30 은 다음 날 일주 조회 1 을 더한다                  | false | 1990-01-27 | 23:30 |       | MALE   | 5
			입춘 1분 전은 전날 연주·월주 조회 1 을 더한다                   | false | 2000-02-04 | 21:23 |       | MALE   | 5
			시간 모름은 대운을 하루의 처음과 끝으로 2번 조회한다              | false | 2000-02-29 |       |       | MALE   | 5
			시간 모름 절입일은 대운 조회가 없다                            | false | 1993-06-06 |       |       | FEMALE | 3
			""")
		void countsQueriesPerCalculation(String description, boolean lunar, LocalDate date, LocalTime time,
			Boolean leapMonth, String gender, long totalQueries) {
			// given
			ManseryeokCalculationRequest request = new ManseryeokCalculationRequest("조회 수", date, time, gender,
				lunar, leapMonth);

			// when
			service.calculate(request);

			// then
			assertThat(manseQueries()).as("계산 한 번의 manses 조회 수").isEqualTo(totalQueries);
			then(manseRepository).should(times(1))
				.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(NOW);
			then(manseRepository).should(times(1))
				.findTop13BySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(CURRENT_SEASON_START);
		}
	}

	@Nested
	@DisplayName("궁합 요청은")
	class Compatibility {

		@Mock
		private ManseInterpretationService manseInterpretationService;
		@Mock
		private PaymentService paymentService;
		@Mock
		private ResultService resultService;

		@Test
		@DisplayName("두 사람을 한 번씩 계산하므로 한 사람 계산(4번)의 두 배인 8번 조회하고 월운 조회도 두 번씩이다")
		void queriesTwiceAsMuchAsOnePerson() {
			// given: 두 사람 모두 양력·출생시간 앎이라 한 사람 계산은 4번 조회한다(OnePerson 표의 첫 두 줄)
			ManseryeokController controller = new ManseryeokController(service, manseInterpretationService,
				paymentService, resultService);
			ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
			request.setPerson1(person("남자", "MALE"));
			request.setPerson2(person("여자", "FEMALE"));
			request.setPaymentId(1L);

			// when
			controller.analyzeCompatibility(19L, request, "user");

			// then
			assertThat(manseQueries()).as("궁합 요청 하나의 manses 조회 수").isEqualTo(8);
			then(manseRepository).should(times(2))
				.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(NOW);
			then(manseRepository).should(times(2))
				.findTop13BySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(CURRENT_SEASON_START);
		}

		private ManseCompatibilityAnalysisRequest.PersonInfo person(String name, String gender) {
			ManseCompatibilityAnalysisRequest.PersonInfo person = new ManseCompatibilityAnalysisRequest.PersonInfo();
			person.setName(name);
			person.setSolarDate(LocalDate.of(1998, 9, 2));
			person.setSolarTime(LocalTime.of(12, 2));
			person.setGender(gender);
			person.setIsLunar(false);
			return person;
		}
	}
}
