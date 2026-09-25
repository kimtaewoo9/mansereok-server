package com.mansereok.server.domain.interpret.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * manses 를 절입 시각과 음력 날짜로 찾는 조회가 실제 MySQL 에서 인덱스를 타는지 실행 계획(EXPLAIN FORMAT=TREE)으로 확인한다.
 *
 * <p>인덱스가 없으면 절입 조회 하나가 manses 약 7.3만 행을 모두 읽고 정렬한다(스크래치 MySQL 에서 건당 약 28ms). 운영은
 * ddl-auto: validate 라 인덱스가 없어도 뜨므로 이 차이가 배포 때 드러나지 않는다. 여기서는 로컬 테스트 DB 에 Manse 엔티티의 인덱스
 * 선언(ddl-auto: update 가 만든다)이 조회 모양과 맞는지 본다. 운영에 인덱스가 있는지는 PR 본문의 SHOW INDEX·EXPLAIN 으로 따로
 * 확인한다.
 *
 * <p>EXPLAIN 하는 SQL 은 Spring Data 가 ManseRepository 의 메서드 이름으로 만드는 모양(범위 조건, 같은 컬럼 정렬, LIMIT)을 옮겨
 * 적은 것이다. 행을 만들거나 지우지 않고 읽기만 한다. manses 기초 데이터가 없는 DB(1000행 미만)에서는 옵티마이저가 작은 표를 그냥
 * 훑을 수 있어 건너뛴다.
 */
class ManseIndexMySqlTest extends InterpretationMySqlTest {

	private static final int MIN_MANSE_ROWS = 1000;
	private static final String SEASON_INDEX_SCAN = "Index range scan on m using idx_manses_season_start_time";
	private static final String LOCAL_INDEX_FIX = "로컬 manses 에 인덱스가 없다. 테스트 컨텍스트가 뜰 때 ddl-auto: update 가 Manse 의 "
		+ "@Table 인덱스를 만든다. 만들지 못했다면 기동 로그의 DDL 경고를 보거나 PR 본문의 운영 DDL 을 로컬 테스트 DB 에 돌린다";

	@BeforeEach
	void requireManseData() {
		Integer rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM manses", Integer.class);
		assumeThat(rowCount).as("manses 기초 데이터가 없어 건너뛴다").isGreaterThanOrEqualTo(MIN_MANSE_ROWS);
	}

	@Test
	@DisplayName("manses 에 절입 시각 인덱스와 (음력 날짜, 윤달) 인덱스가 UNIQUE 가 아닌 인덱스로 그 컬럼 순서대로 걸려 있다")
	void indexesExistWithColumnOrder() {
		// when
		List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
			SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'manses'
			  AND INDEX_NAME IN ('idx_manses_season_start_time', 'idx_manses_lunar_date_leap_month')
			ORDER BY INDEX_NAME, SEQ_IN_INDEX
			""");

		// then
		assertThat(columns).as(LOCAL_INDEX_FIX)
			.extracting(row -> row.get("INDEX_NAME"), row -> row.get("COLUMN_NAME"),
				row -> ((Number) row.get("NON_UNIQUE")).intValue())
			.containsExactly(
				tuple("idx_manses_lunar_date_leap_month", "lunar_date", 1),
				tuple("idx_manses_lunar_date_leap_month", "leap_month", 1),
				tuple("idx_manses_season_start_time", "season_start_time", 1));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@DisplayName("절입 시각으로 찾는 세 조회는 표 전체를 읽거나 따로 정렬하지 않고 절입 시각 인덱스의 범위만 읽는다")
	@CsvSource(delimiter = '|', textBlock = """
		# 조회(ManseRepository 메서드)                                    | SQL
		월운 절입 13개(findTop13...GreaterThanEqual...Asc)                  | SELECT m.* FROM manses m WHERE m.season_start_time >= ? ORDER BY m.season_start_time ASC LIMIT 13
		대운 순행, 같은 시각 제외(findFirst...GreaterThan...Asc)             | SELECT m.* FROM manses m WHERE m.season_start_time > ? ORDER BY m.season_start_time ASC LIMIT 1
		대운 역행과 월운 현재 절입, 같은 시각 포함(findFirst...LessThanEqual...Desc) | SELECT m.* FROM manses m WHERE m.season_start_time <= ? ORDER BY m.season_start_time DESC LIMIT 1
		""")
	void seasonLookupsUseIndexRangeScan(String lookup, String sql) {
		// when
		String plan = explain(sql, LocalDateTime.of(2026, 9, 26, 12, 0));

		// then
		assertThat(plan).as(LOCAL_INDEX_FIX + "%n실행 계획: %s", plan)
			.contains(SEASON_INDEX_SCAN)
			.doesNotContain("Table scan")
			.doesNotContain("Sort");
	}

	@Test
	@DisplayName("지금이 든 절입을 찾는 내림차순 조회는 절입 시각 인덱스를 거꾸로(reverse) 읽는다")
	void descendingLookupReadsIndexInReverse() {
		// when
		String plan = explain(
			"SELECT m.* FROM manses m WHERE m.season_start_time <= ? ORDER BY m.season_start_time DESC LIMIT 1",
			LocalDateTime.of(2026, 9, 26, 12, 0));

		// then
		assertThat(plan).as("실행 계획: %s", plan).contains(SEASON_INDEX_SCAN).contains("(reverse)");
	}

	@Test
	@DisplayName("음력 날짜로 평달·윤달 후보를 찾는 조회는 표 전체를 읽지 않고 (음력 날짜, 윤달) 인덱스로 찾는다")
	void lunarLookupUsesLunarDateIndex() {
		// when
		String plan = explain("SELECT m.* FROM manses m WHERE m.lunar_date = ? ORDER BY m.solar_date ASC",
			LocalDate.of(2020, 4, 15));

		// then
		assertThat(plan).as(LOCAL_INDEX_FIX + "%n실행 계획: %s", plan)
			.contains("Index lookup on m using idx_manses_lunar_date_leap_month")
			.doesNotContain("Table scan");
	}

	private String explain(String sql, Object parameter) {
		return jdbcTemplate.queryForObject("EXPLAIN FORMAT=TREE " + sql, String.class, parameter);
	}
}
