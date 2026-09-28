package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * manses 의 인덱스와 solar_date UNIQUE 를 Manse 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언하는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 라 인덱스를 검사하지 않는다. 엔티티 선언과 schema.sql 이 어긋나도 배포 때 드러나지 않으므로 기본
 * 테스트에서 잡는다. 실제 MySQL 에서 조회가 이 인덱스를 타는지는 ManseIndexMySqlTest 가 본다.
 */
class ManseSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	@ParameterizedTest(name = "[{index}] {0}({1})")
	@DisplayName("절입 시각 인덱스와 음력 날짜 인덱스를 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언한다")
	@CsvSource(delimiter = '|', textBlock = """
		# 인덱스 이름                      | 컬럼(순서대로)
		idx_manses_season_start_time     | season_start_time
		idx_manses_lunar_date_leap_month | lunar_date, leap_month
		""")
	void indexHasSameNameAndColumnOrder(String indexName, String columnList) {
		// given
		List<String> columns = columnsOf(columnList);

		// when
		Index[] declared = Manse.class.getAnnotation(Table.class).indexes();

		// then
		assertThat(declared).as("엔티티 @Table 의 인덱스 %s", indexName)
			.filteredOn(index -> index.name().equals(indexName))
			.singleElement()
			.satisfies(index -> assertThat(columnsOf(index.columnList())).containsExactlyElementsOf(columns));
		assertThat(schema.mentions("manses", indexName)).as("schema.sql 에 %s 가 있다", indexName).isTrue();
		assertThat(createIndexColumnsInSchema(indexName)).as("schema.sql 의 %s 컬럼", indexName)
			.containsExactlyElementsOf(columns);
	}

	@Test
	@DisplayName("엔티티는 manses 인덱스를 절입 시각과 음력 날짜 두 개만 선언한다")
	void entityDeclaresOnlyTwoIndexes() {
		// when
		Index[] declared = Manse.class.getAnnotation(Table.class).indexes();

		// then
		assertThat(declared).extracting(Index::name)
			.containsExactlyInAnyOrder("idx_manses_season_start_time", "idx_manses_lunar_date_leap_month");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@DisplayName("쓰는 조회가 없거나 다른 인덱스와 겹치는 예전 manses 인덱스는 schema.sql 에 없다")
	@ValueSource(strings = {
		// solar_date 의 UNIQUE 와 같은 컬럼이다. 운영에서는 solar_date 에 Non_unique=0 인 인덱스가 있을 때만 지운다.
		// 없으면 UNIQUE 를 먼저 더한다.
		"idx_manses_solar_date",
		// idx_manses_lunar_date_leap_month 가 lunar_date 로 시작해 대신한다.
		"idx_manses_lunar_date",
		// (solar_date, leap_month) 로 찾는 조회가 없다. 예전 schema.sql 은 "사용자의 요청대로 유지" 라고 적어 두었다. 운영에서
		// 지우는 데 동의를 받지 못하면 schema.sql 에 CREATE INDEX idx_solar_leap ON manses(solar_date, leap_month); 한 줄을
		// 되살리고 이 항목을 뺀다.
		"idx_solar_leap"
	})
	void removedIndexIsNotDeclared(String indexName) {
		assertThat(schema.mentions("manses", indexName)).isFalse();
	}

	@Test
	@DisplayName("양력 날짜 조회가 쓰는 solar_date UNIQUE 를 schema.sql 과 엔티티가 함께 선언한다")
	void solarDateIsUniqueInSchemaAndEntity() {
		// when
		UniqueConstraint[] declared = Manse.class.getAnnotation(Table.class).uniqueConstraints();

		// then
		assertThat(schema.isUniqueColumn("manses", "solar_date")).as("schema.sql 의 CREATE TABLE").isTrue();
		// 이름이 MySQL 이 schema.sql 의 컬럼 UNIQUE 에 붙이는 이름(컬럼 이름)과 달라지면, schema.sql 로 만든 표에 ddl-auto: update
		// 가 같은 컬럼의 UNIQUE 를 하나 더 만든다.
		assertThat(declared).as("엔티티 @Table 의 uniqueConstraints")
			.extracting(UniqueConstraint::name, constraint -> List.of(constraint.columnNames()))
			.containsExactly(tuple("solar_date", List.of("solar_date")));
	}

	private static List<String> columnsOf(String columnList) {
		return Arrays.stream(columnList.split(",")).map(String::trim).toList();
	}

	/** schema.sql 에서 {@code CREATE INDEX 이름 ON manses(...)} 문장의 컬럼을 적힌 순서대로 꺼낸다. 주석 줄은 줄 첫머리가 달라 걸리지 않는다. */
	private List<String> createIndexColumnsInSchema(String indexName) {
		Matcher matcher = Pattern.compile("(?im)^\\s*CREATE\\s+INDEX\\s+`?" + Pattern.quote(indexName)
				+ "`?\\s+ON\\s+`?manses`?\\s*\\(([^)]*)\\)")
			.matcher(schema.text());
		assertThat(matcher.find()).as("schema.sql 에 CREATE INDEX %s ON manses(...) 가 있다", indexName).isTrue();
		return columnsOf(matcher.group(1).replace("`", ""));
	}
}
