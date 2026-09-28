package com.mansereok.server.domain.interpret.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.mansereok.server.support.HibernateSqlRecorder;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

/**
 * ManseRepository 의 조회가 실제 MySQL 에서 인덱스를 타는지 실행 계획(EXPLAIN FORMAT=TREE)으로 확인한다.
 *
 * <p>인덱스가 없으면 절입 조회 하나가 manses 약 7.3만 행을 모두 읽고 정렬한다(스크래치 MySQL 에서 건당 약 28ms). 운영은
 * ddl-auto: validate 라 인덱스가 없어도 뜨므로 이 차이가 배포 때 드러나지 않는다. 여기서는 로컬 테스트 DB 에 Manse 엔티티의 인덱스와
 * UNIQUE 선언(ddl-auto: update 가 만든다)이 조회 모양과 맞는지 본다. 운영에 인덱스가 있는지는 PR 본문의 SHOW INDEX·EXPLAIN 으로
 * 따로 확인한다.
 *
 * <p>EXPLAIN 하는 SQL 은 저장소 메서드를 실제로 불러 Hibernate 가 보낸 문장을 {@link HibernateSqlRecorder} 로 잡은 것이다. 그래서
 * 저장소가 보내는 SQL 이 바뀌면(정렬 컬럼을 바꾸거나 컬럼에 함수를 씌우는 경우 등) 여기서 드러난다. 잡은 문장의 값 자리에는 부를 때
 * 넘긴 값과 LIMIT 개수를 문장에 나오는 순서대로 다시 넣는다. 행을 만들거나 지우지 않고 읽기만 한다. manses 기초 데이터가 없는
 * DB(1000행 미만)에서는 옵티마이저가 작은 표를 그냥 훑을 수 있어 건너뛴다.
 */
class ManseIndexMySqlTest extends InterpretationMySqlTest {

	private static final int MIN_MANSE_ROWS = 1000;
	private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 26, 12, 0);
	private static final String SEASON_INDEX_RANGE_SCAN = "Index range scan on \\w+ using idx_manses_season_start_time";
	private static final String LOCAL_INDEX_FIX = "로컬 manses 에 인덱스가 없다. 테스트 컨텍스트가 뜰 때 ddl-auto: update 가 Manse 의 "
		+ "@Table 인덱스와 UNIQUE 를 만든다. 만들지 못했다면 기동 로그의 DDL 경고를 보거나 PR 본문의 운영 DDL 을 로컬 테스트 DB 에 돌린다";
	private static final String PLAN_FIX = "기대한 인덱스를 타지 않는다. 로컬 manses 에 인덱스가 있는지(이 클래스의 인덱스 확인 테스트) "
		+ "보고, 있다면 저장소가 보내는 SQL(정렬 컬럼, 조건)이 바뀌었는지 본다";

	@Autowired
	private ManseRepository manseRepository;

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

	@Test
	@DisplayName("manses 에 양력 날짜 한 컬럼으로 된 UNIQUE 인덱스(NON_UNIQUE=0)가 하나 있다")
	void solarDateHasOneUniqueIndex() {
		// when: 이름은 만든 방법(schema.sql, ddl-auto)에 따라 다를 수 있어 컬럼으로 찾는다
		List<String> uniqueSolarDateIndexes = jdbcTemplate.queryForList("""
			SELECT INDEX_NAME
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'manses' AND NON_UNIQUE = 0
			GROUP BY INDEX_NAME
			HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'solar_date'
			""", String.class);

		// then
		assertThat(uniqueSolarDateIndexes).as(LOCAL_INDEX_FIX).hasSize(1);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@DisplayName("절입 시각으로 찾는 세 조회는 표 전체를 읽거나 따로 정렬하지 않고 절입 시각 인덱스의 범위만 읽는다")
	@MethodSource("seasonLookups")
	void seasonLookupsUseIndexRangeScan(Consumer<ManseRepository> lookup, int limit) {
		// when
		String plan = planOf(lookup, NOON, limit);

		// then
		assertThat(plan).as(PLAN_FIX + "%n실행 계획: %s", plan)
			.containsPattern(SEASON_INDEX_RANGE_SCAN)
			.doesNotContain("Table scan")
			.doesNotContain("Sort");
	}

	static Stream<Arguments> seasonLookups() {
		return Stream.of(
			arguments(lookup("월운 절입 13개(findBy...GreaterThanEqual...Asc, Limit 13)",
				repository -> repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(NOON,
					Limit.of(13))), 13),
			arguments(lookup("대운 순행, 같은 시각 제외(findFirst...GreaterThan...Asc)",
				repository -> repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(NOON)), 1),
			arguments(lookup("대운 역행과 월운 현재 절입, 같은 시각 포함(findFirst...LessThanEqual...Desc)",
				repository -> repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(NOON)), 1));
	}

	@Test
	@DisplayName("지금이 든 절입을 찾는 내림차순 조회는 절입 시각 인덱스를 거꾸로(reverse) 읽는다")
	void descendingLookupReadsIndexInReverse() {
		// when
		String plan = planOf(
			repository -> repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(NOON), NOON, 1);

		// then
		assertThat(plan).as(PLAN_FIX + "%n실행 계획: %s", plan)
			.containsPattern(SEASON_INDEX_RANGE_SCAN)
			.contains("(reverse)");
	}

	@Test
	@DisplayName("음력 날짜로 평달·윤달 후보를 찾는 조회는 표 전체를 읽지 않고 (음력 날짜, 윤달) 인덱스로 찾는다")
	void lunarLookupUsesLunarDateIndex() {
		// given
		LocalDate lunarDate = LocalDate.of(2020, 4, 15);

		// when
		String plan = planOf(repository -> repository.findAllByLunarDateOrderBySolarDateAsc(lunarDate), lunarDate);

		// then
		assertThat(plan).as(PLAN_FIX + "%n실행 계획: %s", plan)
			.containsPattern("Index lookup on \\w+ using idx_manses_lunar_date_leap_month")
			.doesNotContain("Table scan");
	}

	@Test
	@DisplayName("양력 날짜로 찾는 조회는 표 전체를 읽지 않고 solar_date UNIQUE 로 한 행만 찾는다")
	void solarLookupUsesUniqueIndex() {
		// given
		LocalDate solarDate = LocalDate.of(1998, 9, 2);

		// when
		String plan = planOf(repository -> repository.findBySolarDate(solarDate), solarDate);

		// then: UNIQUE 인덱스로 값 하나를 찾으면 MySQL 은 실행 전에 그 한 행을 읽어 두고 계획에 이렇게만 적는다
		assertThat(plan).as(PLAN_FIX + "%n실행 계획: %s", plan)
			.contains("Rows fetched before execution")
			.doesNotContain("Table scan");
	}

	private static Named<Consumer<ManseRepository>> lookup(String name, Consumer<ManseRepository> call) {
		return Named.of(name, call);
	}

	/**
	 * lookup 이 manses 로 보낸 SQL 한 문장을 잡아, 값 자리에 values 를 넣고 EXPLAIN FORMAT=TREE 를 돌린 결과를 돌려준다.
	 */
	private String planOf(Consumer<ManseRepository> lookup, Object... values) {
		List<String> statements = HibernateSqlRecorder.statementsOnTable("manses",
			() -> lookup.accept(manseRepository));
		assertThat(statements).as("조회 한 번이 manses 로 보낸 SQL").hasSize(1);
		return jdbcTemplate.queryForObject("EXPLAIN FORMAT=TREE " + statements.get(0), String.class, values);
	}
}
