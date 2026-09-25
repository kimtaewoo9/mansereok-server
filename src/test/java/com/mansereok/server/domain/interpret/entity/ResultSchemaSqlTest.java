package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 결과 표 두 개(results, compatibility_results)의 schema.sql 블록이 엔티티 선언과 맞는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 인데, validate 는 컬럼이 있는지와 타입만 보고 UNIQUE·인덱스 이름은 보지 않는다. TEXT 와
 * MEDIUMTEXT 도 가리지 않는다(로컬 MySQL 에서 본문이 TEXT 인 표로 validate 기동이 통과했다). 그래서 엔티티에 고정한 제약·인덱스
 * 이름이나 본문 타입이 schema.sql 과 어긋나도 배포 때 드러나지 않는다. 그 어긋남을 기본 테스트에서 잡는다.
 *
 * <p>실제 MySQL 에 그 이름과 컬럼 순서로 걸리는지는 ResultTableConstraintMySqlTest 가 본다.
 */
class ResultSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	static Stream<Arguments> entityAndTable() {
		return Stream.of(
			Arguments.of(Result.class, "results"),
			Arguments.of(CompatibilityResult.class, "compatibility_results"));
	}

	static Stream<Arguments> paymentIdUniqueNames() {
		return Stream.of(
			Arguments.of(Result.class, "results", "uk_results_payment_id"),
			Arguments.of(CompatibilityResult.class, "compatibility_results", "uk_compatibility_results_payment_id"));
	}

	static Stream<Arguments> namedIndexes() {
		return Stream.of(
			Arguments.of(Result.class, "results", "idx_results_status_updated_at", List.of("status", "updated_at")),
			Arguments.of(CompatibilityResult.class, "compatibility_results",
				"idx_compatibility_results_status_updated_at", List.of("status", "updated_at")),
			Arguments.of(Result.class, "results", "idx_results_user_id", List.of("user_id")),
			Arguments.of(CompatibilityResult.class, "compatibility_results", "idx_compatibility_results_user_id",
				List.of("user_id")));
	}

	@ParameterizedTest(name = "[{index}] {1}")
	@MethodSource("entityAndTable")
	@DisplayName("schema.sql 의 컬럼이 엔티티가 매핑하는 컬럼과 같다")
	void columnsMatchEntity(Class<?> entity, String table) {
		// when
		List<String> columnsInSchema = List.copyOf(schema.columnNames(table));

		// then
		assertThat(columnsInSchema).containsExactlyInAnyOrderElementsOf(SchemaSqlFile.mappedColumnNames(entity));
	}

	@ParameterizedTest(name = "[{index}] {1}.{2}")
	@MethodSource("paymentIdUniqueNames")
	@DisplayName("payment_id 하나만 묶은 UNIQUE 를 엔티티와 schema.sql 이 같은 이름으로 선언한다")
	void paymentIdUniqueHasSameName(Class<?> entity, String table, String uniqueName) {
		// when
		UniqueConstraint[] declared = entity.getAnnotation(Table.class).uniqueConstraints();

		// then
		assertThat(declared).as("엔티티 @Table 의 UNIQUE %s", uniqueName)
			.filteredOn(constraint -> constraint.name().equals(uniqueName))
			.singleElement()
			.satisfies(constraint -> assertThat(constraint.columnNames()).containsExactly("payment_id"));
		assertThat(schema.mentions(table, uniqueName)).as("schema.sql 에 %s 가 있다", uniqueName).isTrue();
		assertThat(schema.isUniqueColumn(table, "payment_id")).as("schema.sql 에서 payment_id 가 UNIQUE 다").isTrue();
	}

	@ParameterizedTest(name = "[{index}] {1}.{2}")
	@MethodSource("namedIndexes")
	@DisplayName("(status, updated_at) 인덱스와 user_id 인덱스를 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언한다")
	void namedIndexHasSameNameAndOrder(Class<?> entity, String table, String indexName, List<String> columns) {
		// when
		Index[] declared = entity.getAnnotation(Table.class).indexes();

		// then
		assertThat(declared).as("엔티티 @Table 의 인덱스 %s", indexName)
			.filteredOn(index -> index.name().equals(indexName))
			.singleElement()
			.satisfies(index -> assertThat(columnsOf(index.columnList())).containsExactlyElementsOf(columns));
		assertThat(indexColumnsInSchema(table, indexName)).as("schema.sql 의 %s 컬럼", indexName)
			.containsExactlyElementsOf(columns);
	}

	@ParameterizedTest(name = "[{index}] {1}")
	@MethodSource("entityAndTable")
	@DisplayName("본문(interpretation) 은 엔티티와 schema.sql 모두 MEDIUMTEXT 다")
	void interpretationIsMediumText(Class<?> entity, String table) throws NoSuchFieldException {
		// when
		Column column = entity.getDeclaredField("interpretation").getAnnotation(Column.class);

		// then
		assertThat(column.columnDefinition()).as("엔티티 @Column").isEqualToIgnoringCase("MEDIUMTEXT");
		assertThat(columnTypeInSchema(table, "interpretation")).as("schema.sql").isEqualToIgnoringCase("MEDIUMTEXT");
	}

	@Test
	@DisplayName("쓰는 조회가 없는 idx_results_saju(이름·생년월일시) 는 schema.sql 에 없다")
	void unusedSajuIndexIsNotDeclared() {
		assertThat(schema.mentions("results", "idx_results_saju")).isFalse();
	}

	private static List<String> columnsOf(String columnList) {
		return Arrays.stream(columnList.split(",")).map(String::trim).toList();
	}

	/** CREATE TABLE 블록에서 이름이 indexName 인 인덱스의 컬럼을 적힌 순서대로 꺼낸다. */
	private List<String> indexColumnsInSchema(String table, String indexName) {
		Matcher matcher = Pattern.compile("`?" + Pattern.quote(indexName) + "`?\\s*\\(([^)]*)\\)")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 정의가 있다", table, indexName).isTrue();
		return columnsOf(matcher.group(1).replace("`", ""));
	}

	/** CREATE TABLE 블록에서 컬럼 정의의 첫 낱말(타입)을 꺼낸다. */
	private String columnTypeInSchema(String table, String column) {
		Matcher matcher = Pattern.compile("(?m)^\\s*`?" + Pattern.quote(column) + "`?\\s+(\\w+)")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 컬럼이 있다", table, column).isTrue();
		return matcher.group(1);
	}
}
