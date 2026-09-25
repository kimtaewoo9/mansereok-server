package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import com.mansereok.server.support.InterpretationMySqlTest;
import com.mansereok.server.support.fixture.ManseTableFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 만세력 계산의 대표 사주 표(ManseCalculationServiceTest)가 믿는 ManseTableFixture 가 실제 MySQL 의 manses 표와 같은 답을
 * 내는지, 같은 요청을 두 저장소로 계산해 비교한다.
 *
 * <p>대표 사주 표는 목 저장소로 빠르게 돌지만, 그 목이 Spring Data 의 조회 메서드(같은 시각 포함·제외, 윤달, 0000-00-00 날짜)를
 * DB 와 다르게 흉내 내면 틀린 값을 고정하게 된다. 그래서 몇 건은 실제 ManseRepository 로도 계산해 결과 전체가 같은지 본다. 두
 * 계산은 같은 고정 시계를 써서 월운까지 비교한다. 행을 만들거나 지우지 않고 manses 를 읽기만 한다.
 *
 * <p>manses 기초 데이터가 없는 DB(1000행 미만)에서는 건너뛴다. 로컬 테스트 DB 에 데이터를 넣을 때는 컬럼 목록을 붙여 넣는다.
 * ddl-auto 가 만든 manses 는 컬럼이 알파벳 순서라, 컬럼 목록 없는 덤프(INSERT INTO `manses` VALUES ...)를 그대로 넣으면 값이
 * 엉뚱한 컬럼에 들어간다.
 *
 * <p>다시 넣는 방법은 아래 두 단계다. 덤프에는 TRUNCATE·DROP·CREATE 문이 없어서, 행이 이미 있는 표에 바로 넣으면 id 1 에서
 * PRIMARY KEY 중복 오류(1062)가 난다. 그래서 먼저 비운다. 비우는 일은 로컬 테스트 스키마(127.0.0.1:3307 의
 * mansereok_test_* 같은 곳)에서만 한다. 운영 DB 에서는 하지 않는다.
 * <pre>
 * mysql -h127.0.0.1 -P3307 -uroot -proot 스키마이름 -e 'TRUNCATE TABLE manses'
 * sed 's/INSERT INTO `manses` VALUES /INSERT INTO `manses` (`id`,`solar_date`,`lunar_date`,`season`,`season_start_time`,
 * `leap_month`,`year_sky`,`year_ground`,`month_sky`,`month_ground`,`day_sky`,`day_ground`,`created_at`,`updated_at`) VALUES /'
 * src/main/resources/data/manses.sql | mysql -h127.0.0.1 -P3307 -uroot -proot 스키마이름
 * </pre>
 * (sed 인자는 한 줄로 붙여 쓴다)
 */
class ManseTableFixtureMatchesMySqlTest extends InterpretationMySqlTest {

	private static final int MIN_MANSE_ROWS = 1000;
	// 2026-09-26 12:00 (서울). 대표 사주 표와 같은 시각이다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final String RELOAD_HINT = "manses 의 1993-06-06 행이 덤프와 다르다(값이 다른 컬럼에 들어 있을 수 있다). "
		+ "컬럼 목록 없이 덤프를 넣었는지 확인한다. 로컬 테스트 스키마에서만 TRUNCATE TABLE manses 로 먼저 비운 뒤, "
		+ "ManseTableFixtureMatchesMySqlTest 클래스 설명의 sed 명령으로 다시 넣는다";

	@Autowired
	private ManseRepository manseRepository;

	@BeforeEach
	void requireManseData() {
		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM manses", Integer.class);
		assumeThat(rowCount).as("manses 기초 데이터가 없어 건너뛴다").isGreaterThanOrEqualTo(MIN_MANSE_ROWS);
		// AssertJ 의 Optional 단언에서 map 은 설명(as)을 버린 새 단언을 만든다. 그래서 map 은 단언 밖에서 한다.
		assertThat(manseRepository.findBySolarDate(LocalDate.of(1993, 6, 6)).map(Manse::getSeasonStartTime))
			.as(RELOAD_HINT)
			.hasValue(LocalDateTime.of(1993, 6, 6, 1, 12));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@DisplayName("실제 manses 표로 계산한 결과가 ManseTableFixture 로 계산한 결과와 월운까지 모두 같다")
	@CsvSource(delimiter = '|', textBlock = """
		# 사례                          | 음력  | 날짜       | 시각  | 윤달 | 성별
		야자시 23:30                     | false | 1990-01-27 | 23:30 |      | MALE
		입춘 정각(같은 시각 제외 순행)      | false | 2000-02-04 | 21:24 |      | MALE
		음력 2020년 윤4월 15일            | true  | 2020-04-15 | 12:00 | true | MALE
		戊 양간 해 여자 역행               | false | 1998-09-02 | 12:02 |      | FEMALE
		시간 모름, 대운수 범위 1~2          | false | 2000-02-29 |       |      | MALE
		시간 모름, 망종 절입일              | false | 1993-06-06 |       |      | FEMALE
		""")
	void sameResultAsFixture(String description, boolean lunar, LocalDate date, LocalTime time, Boolean leapMonth,
		String gender) {
		// given
		ManseryeokCalculationRequest request = new ManseryeokCalculationRequest("대표 사주", date, time, gender,
			lunar, leapMonth);
		ManseryeokCalculationResponse fromFixture = serviceWith(ManseTableFixture.realTable().newRepository())
			.calculate(request);

		// when
		ManseryeokCalculationResponse fromMySql = serviceWith(manseRepository).calculate(request);

		// then
		assertThat(fromMySql.getSaju().getMonthlyFortunes()).as("월운까지 비교하는지").hasSize(12);
		assertThat(fromMySql).usingRecursiveComparison().isEqualTo(fromFixture);
	}

	private static ManseCalculationService serviceWith(ManseRepository repository) {
		return new ManseCalculationService(repository, new SajuDataService(), new UnseongCalculator(),
			new SinsalCalculator(), new RelationCalculator(), new YongsinCalculator(), FIXED_CLOCK);
	}
}
