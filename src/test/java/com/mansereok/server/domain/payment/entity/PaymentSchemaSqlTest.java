package com.mansereok.server.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.annotations.ColumnDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 결제 영역 표의 schema.sql 블록이 엔티티 선언과 맞는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 인데, validate 는 컬럼이 있는지와 타입만 보고 UNIQUE 이름과 NOT NULL·DEFAULT 는 보지 않는다. 그래서
 * 엔티티에 고정한 제약 이름이나 NOT NULL 이 schema.sql 과 어긋나도 배포 때 드러나지 않는다. 그 어긋남을 기본 테스트에서 잡는다.
 *
 * <p>실제 MySQL 에 그 이름과 컬럼 순서로 걸리는지는 CouponUniqueMySqlTest 가, 발급 수 칸을 비워 넣으면 실제로 0 이 들어가는지는
 * CouponTemplateIssueCountMySqlTest 가 본다.
 */
class PaymentSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	static Stream<Arguments> entityAndTable() {
		return Stream.of(
			Arguments.of(CouponTemplate.class, "coupon_templates"),
			Arguments.of(Coupon.class, "coupons"),
			Arguments.of(DiscountCode.class, "discount_codes"));
	}

	static Stream<Arguments> namedUniqueConstraints() {
		return Stream.of(
			Arguments.of(Coupon.class, "coupons", "uk_coupons_user_template", List.of("user_id", "template_id")));
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
	@MethodSource("namedUniqueConstraints")
	@DisplayName("UNIQUE 를 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언한다")
	void uniqueConstraintHasSameNameAndOrder(Class<?> entity, String table, String uniqueName, List<String> columns) {
		// when
		UniqueConstraint[] declared = entity.getAnnotation(Table.class).uniqueConstraints();

		// then
		assertThat(declared).as("엔티티 @Table 의 UNIQUE %s", uniqueName)
			.filteredOn(constraint -> constraint.name().equals(uniqueName))
			.singleElement()
			.satisfies(constraint -> assertThat(constraint.columnNames()).containsExactlyElementsOf(columns));
		assertThat(schema.mentions(table, uniqueName)).as("schema.sql 에 %s 가 있다", uniqueName).isTrue();
		assertThat(uniqueKeyColumnsInSchema(table, uniqueName)).as("schema.sql 의 UNIQUE KEY %s 컬럼", uniqueName)
			.containsExactlyElementsOf(columns);
	}

	@Test
	@DisplayName("할인 코드의 code 는 엔티티와 schema.sql 모두 UNIQUE 다")
	void discountCodeIsUnique() throws NoSuchFieldException {
		// when
		Column declared = DiscountCode.class.getDeclaredField("code").getAnnotation(Column.class);

		// then
		assertThat(declared.unique()).as("엔티티 DiscountCode.code 의 unique").isTrue();
		assertThat(schema.isUniqueColumn("discount_codes", "code")).as("schema.sql 의 discount_codes.code").isTrue();
	}

	@Test
	@DisplayName("쿠폰 템플릿의 발급 수는 엔티티와 schema.sql 모두 NOT NULL DEFAULT 0 으로 선언한다")
	void issueCountIsNotNullWithZeroDefault() throws NoSuchFieldException {
		// when
		Field field = CouponTemplate.class.getDeclaredField("currentIssueCount");

		// then
		assertThat(field.getType()).as("엔티티 발급 수의 타입(null 이 들어갈 수 없는 int)").isEqualTo(int.class);
		assertThat(field.getAnnotation(Column.class).nullable()).as("엔티티 발급 수의 nullable").isFalse();
		assertThat(field.getAnnotation(ColumnDefault.class)).as("엔티티 발급 수의 @ColumnDefault")
			.isNotNull()
			.extracting(ColumnDefault::value).isEqualTo("0");
		assertThat(columnDefinitionInSchema("coupon_templates", "current_issue_count"))
			.as("schema.sql 의 current_issue_count 정의")
			.containsIgnoringCase("NOT NULL")
			.containsIgnoringCase("DEFAULT 0");
	}

	/** CREATE TABLE 블록에서 "UNIQUE KEY 이름 (컬럼, ...)" 정의를 찾아 컬럼을 적힌 순서대로 꺼낸다. */
	private List<String> uniqueKeyColumnsInSchema(String table, String name) {
		Matcher matcher = Pattern.compile(
				"(?i)[(,]\\s*UNIQUE\\s+KEY\\s+`?" + Pattern.quote(name) + "`?\\s*\\(([^)]*)\\)")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 정의가 있다", table, name).isTrue();
		return Arrays.stream(matcher.group(1).replace("`", "").split(",")).map(String::trim).toList();
	}

	/** CREATE TABLE 블록에서 컬럼 정의 한 줄(이름 뒤부터 줄 끝까지)을 꺼낸다. */
	private String columnDefinitionInSchema(String table, String column) {
		Matcher matcher = Pattern.compile("(?m)^\\s*`?" + Pattern.quote(column) + "`?\\s+(.+)$")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 컬럼이 있다", table, column).isTrue();
		return matcher.group(1);
	}
}
