package com.mansereok.server.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import com.mansereok.server.support.SchemaSqlFile;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 인증 표 세 개(users, refresh_tokens, password_reset_tokens)의 schema.sql 블록이 엔티티 선언과 맞는지 DB 없이 확인한다.
 *
 * <p>운영은 ddl-auto: validate 인데, validate 는 컬럼이 있는지와 타입만 보고 UNIQUE·인덱스 이름과 NOT NULL 은 보지 않는다. 그래서
 * 엔티티에 고정한 제약·인덱스 이름이 schema.sql 과 어긋나도 배포 때 드러나지 않는다. 그 어긋남을 기본 테스트에서 잡는다.
 *
 * <p>실제 MySQL 에 그 이름과 컬럼 순서로 걸리는지는 UserUniqueKeysMySqlTest 가 본다.
 */
class AuthSchemaSqlTest {

	private final SchemaSqlFile schema = SchemaSqlFile.load();

	static Stream<Arguments> entityAndTable() {
		return Stream.of(
			Arguments.of(User.class, "users"),
			Arguments.of(RefreshToken.class, "refresh_tokens"),
			Arguments.of(PasswordResetToken.class, "password_reset_tokens"));
	}

	static Stream<Arguments> namedUniqueConstraints() {
		return Stream.of(
			Arguments.of(User.class, "users", "uk_users_email", List.of("email")),
			Arguments.of(User.class, "users", "uk_users_username", List.of("username")),
			Arguments.of(User.class, "users", "uk_users_social_id_type", List.of("social_id", "social_type")),
			Arguments.of(PasswordResetToken.class, "password_reset_tokens", "uk_password_reset_tokens_token",
				List.of("token")),
			Arguments.of(PasswordResetToken.class, "password_reset_tokens", "uk_password_reset_tokens_user_id",
				List.of("user_id")));
	}

	static Stream<Arguments> namedIndexes() {
		return Stream.of(
			Arguments.of(RefreshToken.class, "refresh_tokens", "idx_refresh_tokens_expires_at", List.of("expires_at")),
			Arguments.of(RefreshToken.class, "refresh_tokens", "idx_refresh_tokens_used_at", List.of("used_at")));
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
		assertThat(keyColumnsInSchema(table, "UNIQUE\\s+KEY", uniqueName)).as("schema.sql 의 UNIQUE KEY %s 컬럼", uniqueName)
			.containsExactlyElementsOf(columns);
	}

	@ParameterizedTest(name = "[{index}] {1}.{2}")
	@MethodSource("namedIndexes")
	@DisplayName("UNIQUE 가 아닌 일반 인덱스를 엔티티와 schema.sql 이 같은 이름과 컬럼 순서로 선언한다")
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
		// 정의 첫머리가 INDEX 여야 찾으므로, schema.sql 에서 UNIQUE INDEX 로 바뀌면 여기서 실패한다.
		assertThat(keyColumnsInSchema(table, "INDEX", indexName)).as("schema.sql 의 INDEX %s 컬럼", indexName)
			.containsExactlyElementsOf(columns);
	}

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@CsvSource(textBlock = """
		# 엔티티 필드, password_reset_tokens 컬럼
		token,      token
		user,       user_id
		expiryDate, expiry_date
		""")
	@DisplayName("재설정 토큰의 token·user_id·expiry_date 는 엔티티와 schema.sql 모두 NOT NULL 이다")
	void passwordResetTokenColumnsAreNotNull(String fieldName, String column) throws NoSuchFieldException {
		// when
		boolean nullableInEntity = nullableInEntity(PasswordResetToken.class, fieldName);

		// then
		assertThat(nullableInEntity).as("엔티티 %s 의 nullable", fieldName).isFalse();
		assertThat(columnDefinitionInSchema("password_reset_tokens", column)).as("schema.sql 의 %s 정의", column)
			.containsIgnoringCase("NOT NULL");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(Role.class)
	@DisplayName("schema.sql 의 users.role ENUM 이 Role 의 모든 값을 담는다")
	void roleEnumHasEveryRole(Role role) {
		assertThat(enumValuesInSchema("users", "role")).contains(role.name());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(Gender.class)
	@DisplayName("schema.sql 의 users.gender ENUM 이 Gender 의 모든 값을 담는다")
	void genderEnumHasEveryGender(Gender gender) {
		assertThat(enumValuesInSchema("users", "gender")).contains(gender.name());
	}

	private static List<String> columnsOf(String columnList) {
		return Arrays.stream(columnList.split(",")).map(String::trim).toList();
	}

	/**
	 * CREATE TABLE 블록에서 "종류 이름 (컬럼, ...)" 정의를 찾아 컬럼을 적힌 순서대로 꺼낸다. 종류는 정규식(UNIQUE KEY, INDEX)이다.
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

	/** CREATE TABLE 블록에서 컬럼 정의 한 줄(이름 뒤부터 줄 끝까지)을 꺼낸다. */
	private String columnDefinitionInSchema(String table, String column) {
		Matcher matcher = Pattern.compile("(?m)^\\s*`?" + Pattern.quote(column) + "`?\\s+(.+)$")
			.matcher(schema.createTableBlock(table));
		assertThat(matcher.find()).as("schema.sql 의 %s 에 %s 컬럼이 있다", table, column).isTrue();
		return matcher.group(1);
	}

	/** 컬럼 정의의 ENUM('A', 'B') 안 값을 꺼낸다. */
	private List<String> enumValuesInSchema(String table, String column) {
		Matcher matcher = Pattern.compile("(?i)^ENUM\\s*\\(([^)]*)\\)")
			.matcher(columnDefinitionInSchema(table, column));
		assertThat(matcher.find()).as("schema.sql 의 %s.%s 는 ENUM 이다", table, column).isTrue();
		return Arrays.stream(matcher.group(1).split(","))
			.map(value -> value.trim().replace("'", ""))
			.toList();
	}

	private static boolean nullableInEntity(Class<?> entity, String fieldName) throws NoSuchFieldException {
		Field field = entity.getDeclaredField(fieldName);
		JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
		if (joinColumn != null) {
			return joinColumn.nullable();
		}
		Column column = field.getAnnotation(Column.class);
		return column == null || column.nullable();
	}
}
