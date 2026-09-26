package com.mansereok.server.domain.review.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 리뷰(reviews)와 상품(subcategories) 표의 schema.sql 블록이 엔티티 선언과 맞는지, 그리고 schema.sql 이 표를 지우는 문장 없이
 * 참고용 DDL 로만 쓰이는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 라 엔티티의 UNIQUE·인덱스 선언이 운영에 아무것도 만들지 않는다. 운영에는 schema.sql 과 같은 이름으로
 * 손으로 적용하므로, 엔티티와 schema.sql 이 같은 이름·컬럼 순서·정렬 방향을 쓰는지를 여기서 본다. 실제 MySQL 에서 쿼리가 그
 * 인덱스를 타는지는 ReviewIndexUsageMySqlTest 가 본다.
 */
class ReviewProductSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	static Stream<Arguments> entityTableAndColumnsOnlyInSchema() {
		return Stream.of(
			Arguments.of(Review.class, "reviews", Set.of()),
			// 엔티티가 매핑하지 않고 INSERT 때 DB 기본값으로 채워지는 컬럼
			Arguments.of(SubCategory.class, "subcategories", Set.of("created_at", "updated_at")));
	}

	static Stream<Arguments> namedIndexes() {
		return Stream.of(
			Arguments.of(Review.class, "reviews", "idx_subcat_del_created",
				List.of("sub_category_id", "is_deleted", "created_at DESC")),
			Arguments.of(Review.class, "reviews", "idx_del_created", List.of("is_deleted", "created_at DESC")),
			Arguments.of(Review.class, "reviews", "idx_user_del_created",
				List.of("user_id", "is_deleted", "created_at DESC")),
			Arguments.of(SubCategory.class, "subcategories", "idx_subcategories_category_id", List.of("category_id")));
	}

	@ParameterizedTest(name = "[{index}] 문장 첫머리의 {0}")
	@ValueSource(strings = {"DROP", "TRUNCATE"})
	@DisplayName("schema.sql 에는 표나 데이터를 지우는 문장이 없다")
	void schemaSqlHasNoDestructiveStatement(String keyword) {
		// when
		Matcher statementStart = Pattern.compile("(?im)^\\s*" + keyword + "\\b").matcher(schema.text());

		// then
		assertThat(statementStart.find()).as("통째로 실행하면 운영 표를 지우는 %s 문장", keyword).isFalse();
	}

	@ParameterizedTest(name = "[{index}] {1}")
	@MethodSource("entityTableAndColumnsOnlyInSchema")
	@DisplayName("schema.sql 의 컬럼은 엔티티가 매핑하는 컬럼과 DB 가 기본값으로 채우는 컬럼을 합친 것과 같다")
	void columnsMatchEntity(Class<?> entity, String table, Set<String> columnsOnlyInSchema) {
		// given
		Set<String> mappedByEntity = SchemaSqlFile.mappedColumnNames(entity);

		// when
		Set<String> columnsInSchema = schema.columnNames(table);

		// then
		assertThat(columnsInSchema).as("엔티티가 매핑하는데 schema.sql 에 없는 컬럼")
			.containsAll(mappedByEntity);
		assertThat(columnsInSchema).as("엔티티가 매핑하지 않는데 schema.sql 에 있는 컬럼")
			.filteredOn(column -> !mappedByEntity.contains(column))
			.containsExactlyInAnyOrderElementsOf(columnsOnlyInSchema);
	}

	@Test
	@DisplayName("주문 하나에 리뷰 하나를 지키는 UNIQUE 를 엔티티와 schema.sql 이 같은 이름 uk_reviews_order_id 로 order_id 에 선언한다")
	void reviewOrderIdUniqueHasSameName() {
		// when
		UniqueConstraint[] declared = Review.class.getAnnotation(Table.class).uniqueConstraints();

		// then
		assertThat(declared).as("엔티티 @Table 의 UNIQUE")
			.singleElement()
			.satisfies(constraint -> {
				assertThat(constraint.name()).isEqualTo("uk_reviews_order_id");
				assertThat(constraint.columnNames()).containsExactly("order_id");
			});
		assertThat(keyColumnsInSchema("reviews", "UNIQUE\\s+KEY", "uk_reviews_order_id"))
			.as("schema.sql 의 UNIQUE KEY uk_reviews_order_id 컬럼")
			.containsExactly("order_id");
	}

	@ParameterizedTest(name = "[{index}] {1}.{2}")
	@MethodSource("namedIndexes")
	@DisplayName("일반 인덱스를 엔티티와 schema.sql 이 같은 이름, 컬럼 순서, 정렬 방향으로 선언한다")
	void indexHasSameNameAndOrder(Class<?> entity, String table, String indexName, List<String> columns) {
		// when
		Index[] declared = entity.getAnnotation(Table.class).indexes();

		// then
		assertThat(declared).as("엔티티 @Table 의 인덱스 %s", indexName)
			.filteredOn(index -> index.name().equals(indexName))
			.singleElement()
			.satisfies(index -> {
				assertThat(columnsOf(index.columnList())).containsExactlyElementsOf(columns);
				assertThat(index.unique()).as("엔티티 인덱스 %s 의 unique", indexName).isFalse();
			});
		assertThat(keyColumnsInSchema(table, "INDEX", indexName)).as("schema.sql 의 INDEX %s 컬럼", indexName)
			.containsExactlyElementsOf(columns);
	}

	/** "a, b DESC" 를 [a, b DESC] 로 나눈다. 컬럼과 정렬 방향 사이 공백은 하나로 맞춘다. */
	private static List<String> columnsOf(String columnList) {
		return Arrays.stream(columnList.split(","))
			.map(column -> column.trim().replaceAll("\\s+", " "))
			.toList();
	}

	/**
	 * CREATE TABLE 블록에서 "종류 이름 (컬럼, ...)" 정의를 찾아 컬럼을 적힌 순서대로, 정렬 방향(DESC)을 붙인 채 꺼낸다. 종류는
	 * 정규식(UNIQUE KEY, INDEX)이다.
	 *
	 * <p>종류는 정의의 첫머리(여는 괄호나 쉼표 바로 뒤)에 있어야 찾는다. 그래서 INDEX 로 찾을 때 UNIQUE INDEX 정의는 걸리지 않는다.
	 */
	private List<String> keyColumnsInSchema(String table, String kindPattern, String name) {
		Matcher matcher = Pattern.compile(
				"(?i)[(,]\\s*" + kindPattern + "\\s+`?" + Pattern.quote(name) + "`?\\s*\\(([^)]*)\\)")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 정의가 있다", table, name).isTrue();
		return columnsOf(matcher.group(1).replace("`", ""));
	}
}
