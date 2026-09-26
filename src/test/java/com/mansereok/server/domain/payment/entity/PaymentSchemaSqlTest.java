package com.mansereok.server.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 결제 영역 표의 schema.sql 블록이 엔티티 선언과 맞는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 인데, validate 는 컬럼이 있는지와 타입만 보고 UNIQUE 이름과 NOT NULL·DEFAULT 는 보지 않는다. 그래서
 * 엔티티에 고정한 제약 이름이나 NOT NULL 이 schema.sql 과 어긋나도 배포 때 드러나지 않는다. 그 어긋남을 기본 테스트에서 잡는다.
 *
 * <p>실제 MySQL 에 그 이름과 컬럼 순서로 걸리는지는 CouponUniqueMySqlTest·OrderPaymentIndexMySqlTest 가, 발급 수 칸을 비워
 * 넣으면 실제로 0 이 들어가는지는 CouponTemplateIssueCountMySqlTest 가 본다.
 */
class PaymentSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	static Stream<Arguments> entityAndTable() {
		return Stream.of(
			Arguments.of(CouponTemplate.class, "coupon_templates"),
			Arguments.of(Coupon.class, "coupons"),
			Arguments.of(DiscountCode.class, "discount_codes"),
			Arguments.of(Order.class, "orders"),
			Arguments.of(Payment.class, "payments"));
	}

	static Stream<Arguments> namedUniqueConstraints() {
		return Stream.of(
			Arguments.of(Coupon.class, "coupons", "uk_coupons_user_template", List.of("user_id", "template_id")),
			Arguments.of(Order.class, "orders", "uk_orders_merchant_uid", List.of("merchant_uid")),
			Arguments.of(Order.class, "orders", "uk_orders_payment_pk_id", List.of("payment_pk_id")));
	}

	static Stream<Arguments> namedIndexes() {
		return Stream.of(
			Arguments.of(Order.class, "orders", "idx_orders_status_created_at", List.of("status", "created_at")),
			Arguments.of(Order.class, "orders", "idx_orders_coupon_id", List.of("coupon_id")),
			Arguments.of(Order.class, "orders", "idx_orders_user_id", List.of("user_id")),
			Arguments.of(Payment.class, "payments", "idx_payments_created_at", List.of("created_at")),
			Arguments.of(Payment.class, "payments", "idx_payments_status", List.of("status")),
			Arguments.of(Payment.class, "payments", "idx_payments_user_id_created_at", List.of("user_id", "created_at")));
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

	@ParameterizedTest(name = "[{index}] {1}.{2}")
	@MethodSource("namedIndexes")
	@DisplayName("일반 인덱스를 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언한다")
	void indexHasSameNameAndOrder(Class<?> entity, String table, String indexName, List<String> columns) {
		// when
		Index[] declared = entity.getAnnotation(Table.class).indexes();

		// then
		assertThat(declared).as("엔티티 @Table 의 인덱스 %s", indexName)
			.filteredOn(index -> index.name().equals(indexName))
			.singleElement()
			.satisfies(index -> {
				assertThat(index.unique()).as("엔티티 인덱스 %s 의 unique", indexName).isFalse();
				assertThat(columnListOf(index)).as("엔티티 인덱스 %s 의 컬럼", indexName).containsExactlyElementsOf(columns);
			});
		assertThat(indexColumnsInSchema(table, indexName)).as("schema.sql 의 INDEX %s 컬럼", indexName)
			.containsExactlyElementsOf(columns);
	}

	@ParameterizedTest(name = "[{index}] {0}.{1}")
	@CsvSource({
		"orders,   idx_orders_merchant_uid",
		"payments, idx_user_id"
	})
	@DisplayName("UNIQUE·복합 인덱스와 앞 컬럼이 겹치던 옛 인덱스를 schema.sql 에 두지 않는다")
	void overlappingOldIndexIsGone(String table, String oldIndexName) {
		// when
		boolean stillDeclared = schema.mentions(table, oldIndexName);

		// then
		assertThat(stillDeclared).as("schema.sql 의 %s 에 %s 가 남아 있다", table, oldIndexName).isFalse();
	}

	@Test
	@DisplayName("주문번호(merchant_uid)는 엔티티와 schema.sql 모두 NOT NULL 이다")
	void merchantUidIsNotNull() throws NoSuchFieldException {
		// when
		Column declared = Order.class.getDeclaredField("merchantUid").getAnnotation(Column.class);

		// then
		assertThat(declared).as("엔티티 Order.merchantUid 의 @Column").isNotNull();
		assertThat(declared.nullable()).as("엔티티 Order.merchantUid 의 nullable").isFalse();
		assertThat(columnDefinitionInSchema("orders", "merchant_uid")).as("schema.sql 의 merchant_uid 정의")
			.containsIgnoringCase("NOT NULL");
	}

	@Test
	@DisplayName("결제의 사용자 id 는 탈퇴 때 NULL 로 바뀌므로 엔티티와 schema.sql 모두 NULL 을 허용한다")
	void paymentUserIdIsNullable() throws NoSuchFieldException {
		// when
		Column declared = Payment.class.getDeclaredField("userId").getAnnotation(Column.class);

		// then
		assertThat(declared).as("엔티티 Payment.userId 의 @Column").isNotNull();
		assertThat(declared.nullable()).as("엔티티 Payment.userId 의 nullable").isTrue();
		assertThat(columnDefinitionInSchema("payments", "user_id")).as("schema.sql 의 payments.user_id 정의")
			.doesNotContainIgnoringCase("NOT NULL");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(OrderStatus.class)
	@DisplayName("주문 상태마다 schema.sql 의 orders.status ENUM 에 그 값이 있다")
	void orderStatusIsInSchemaEnum(OrderStatus status) {
		// when
		String definition = columnDefinitionInSchema("orders", "status");

		// then
		assertThat(definition).as("schema.sql 의 orders.status 정의").contains("'" + status.name() + "'");
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

	/** CREATE TABLE 블록에서 "INDEX 이름 (컬럼, ...)" 또는 "KEY 이름 (컬럼, ...)" 정의를 찾아 컬럼을 적힌 순서대로 꺼낸다. */
	private List<String> indexColumnsInSchema(String table, String name) {
		Matcher matcher = Pattern.compile(
				"(?i)[(,]\\s*(?:INDEX|KEY)\\s+`?" + Pattern.quote(name) + "`?\\s*\\(([^)]*)\\)")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 정의가 있다", table, name).isTrue();
		return Arrays.stream(matcher.group(1).replace("`", "").split(",")).map(String::trim).toList();
	}

	/** @Index 의 columnList("status, created_at")를 컬럼 이름 목록으로 나눈다. */
	private static List<String> columnListOf(Index index) {
		return Arrays.stream(index.columnList().split(",")).map(String::trim).toList();
	}

	/** CREATE TABLE 블록에서 컬럼 정의 한 줄(이름 뒤부터 줄 끝까지)을 꺼낸다. */
	private String columnDefinitionInSchema(String table, String column) {
		Matcher matcher = Pattern.compile("(?m)^\\s*`?" + Pattern.quote(column) + "`?\\s+(.+)$")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 컬럼이 있다", table, column).isTrue();
		return matcher.group(1);
	}
}
