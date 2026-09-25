package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 서로 다른 생일 10개를 실제 manses 표로 한꺼번에 계산해도 한 건씩 계산한 결과와 같고, 제시간에 끝나며, DB 로 보내는 SQL 이 계산
 * 수만큼만 늘어나는지 확인한다.
 *
 * <p>/api/v1/manseryeok/calculate 는 로그인 없이 부를 수 있어 요청이 한꺼번에 몰릴 수 있다. 계산은 요청 스레드에서 manses 를
 * 여러 번 조회하므로, 조회가 인덱스를 타지 않거나 조회 수가 다시 늘면 커넥션을 오래 쥐게 된다. 요청 수는 HikariCP 기본 풀 크기(10)와
 * 같게 두어 커넥션을 기다리는 시간을 재지 않게 한다.
 *
 * <p>계산 서비스는 실제 ManseRepository 에 고정 시계(2026-09-26 12:00 서울)를 붙여 직접 만든다. 스프링의 시계를 쓰면 월운이
 * 실행하는 순간에 달려 두 계산 사이에 절입이 지나갈 수 있다. 행을 만들거나 지우지 않고 manses 를 읽기만 한다.
 */
class ManseCalculationLoadMySqlTest extends InterpretationMySqlTest {

	private static final int MIN_MANSE_ROWS = 1000;
	private static final Duration TIME_LIMIT = Duration.ofSeconds(5);
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"),
		ZoneId.of("Asia/Seoul"));

	// 모두 양력, 출생시간 앎, 절입일도 야자시도 아니라 계산 한 번이 manses 를 4번 조회한다(양력 날짜 1, 대운 1, 월운 2).
	private static final List<ManseryeokCalculationRequest> TEN_BIRTHDAYS = List.of(
		request(LocalDate.of(1955, 8, 30), LocalTime.of(9, 5), "FEMALE"),
		request(LocalDate.of(1966, 10, 1), LocalTime.of(3, 15), "FEMALE"),
		request(LocalDate.of(1978, 5, 25), LocalTime.of(20, 0), "MALE"),
		request(LocalDate.of(1985, 3, 15), LocalTime.of(8, 10), "MALE"),
		request(LocalDate.of(1990, 7, 20), LocalTime.of(14, 30), "FEMALE"),
		request(LocalDate.of(1995, 11, 11), LocalTime.of(11, 11), "MALE"),
		request(LocalDate.of(1999, 9, 19), LocalTime.of(13, 20), "FEMALE"),
		request(LocalDate.of(2001, 1, 15), LocalTime.of(6, 45), "FEMALE"),
		request(LocalDate.of(2010, 12, 25), LocalTime.of(17, 40), "MALE"),
		request(LocalDate.of(2020, 2, 20), LocalTime.of(22, 10), "MALE"));

	@Autowired
	private ManseRepository manseRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private ManseCalculationService service;

	@BeforeEach
	void setUp() {
		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM manses", Integer.class);
		assumeThat(rowCount).as("manses 기초 데이터가 없어 건너뛴다").isGreaterThanOrEqualTo(MIN_MANSE_ROWS);
		service = new ManseCalculationService(manseRepository, new SajuDataService(), new UnseongCalculator(),
			new SinsalCalculator(), new RelationCalculator(), new YongsinCalculator(), FIXED_CLOCK);
	}

	@AfterEach
	void stopCountingStatements() {
		statistics().setStatisticsEnabled(false);
	}

	@Test
	@DisplayName("서로 다른 생일 10개를 동시에 계산해도 한 건씩 계산한 결과와 같고, 5초 안에 끝나며, SQL 은 계산 10번 × 4번인 40번이다")
	void concurrentCalculationsMatchOneByOne() {
		// given
		List<ManseryeokCalculationResponse> oneByOne = TEN_BIRTHDAYS.stream().map(service::calculate).toList();
		Statistics statistics = startCountingStatements();
		long startedAt = System.nanoTime();

		// when
		List<CallResult<ManseryeokCalculationResponse>> results = ConcurrentCalls.runAtTheSameTime(
			TEN_BIRTHDAYS.size(), index -> () -> service.calculate(TEN_BIRTHDAYS.get(index)));

		// then
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		assertThat(results).as("모든 계산이 예외 없이 끝난다")
			.allSatisfy(result -> assertThat(result.error()).isNull());
		assertThat(results).extracting(CallResult::value)
			.usingRecursiveFieldByFieldElementComparator()
			.containsExactlyElementsOf(oneByOne);
		assertThat(elapsed).as("동시 계산 10개가 끝난 시간").isLessThan(TIME_LIMIT);
		assertThat(statistics.getPrepareStatementCount()).as("DB 로 보낸 SQL 문 수").isEqualTo(40);
	}

	private static ManseryeokCalculationRequest request(LocalDate solarDate, LocalTime solarTime, String gender) {
		return new ManseryeokCalculationRequest("동시 계산", solarDate, solarTime, gender, false, null);
	}

	private Statistics startCountingStatements() {
		Statistics statistics = statistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		return statistics;
	}

	private Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}
}
