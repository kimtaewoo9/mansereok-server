package com.mansereok.server.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * users·토큰 표의 UNIQUE 와 인덱스가 실제 MySQL 에서 기대대로 걸리고 동작하는지 확인한다.
 *
 * <p>테스트 DB 는 ddl-auto: update 라 엔티티 선언(@Table)대로 UNIQUE·인덱스가 생긴다. 운영은 validate 라 같은 이름의 DDL 을 손으로
 * 적용한다. 그래서 여기서 보는 이름과 컬럼 순서가 운영에 적용할 DDL 과 schema.sql 의 기준이 된다. 기대하는 이름과 컬럼 순서는 이
 * 테스트에 값 그대로 적고, 엔티티와 schema.sql 이 그 이름을 쓰는지는 AuthSchemaSqlTest 가 DB 없이 본다.
 *
 * <p>update 는 없는 이름만 만든다. 엔티티에서 지운 이름은 테스트 DB 에 그대로 남고, 같은 이름의 컬럼 구성을 바꿔도 다시 만들지
 * 않는다. 이미 있는 컬럼의 NOT NULL 도 바꾸지 않는다(예전에 만든 테스트 DB 의 password_reset_tokens 세 컬럼은 엔티티가
 * nullable = false 여도 NULL 을 허용한 채로 남는다). 그래서 이 테스트는 "기대한 이름이 그 컬럼 순서로 있다" 만 보고, 이름을 바꾸거나
 * 지웠을 때 테스트 DB 를 고치는 것은 사람이 한다.
 *
 * <p>모든 행은 이번 실행의 runId 를 username 끝에 넣어 만들고, 뒤 정리에서 그 행만 지운다.
 */
class UserUniqueKeysMySqlTest extends LocalMySqlTest {

	// information_schema.STATISTICS.NON_UNIQUE 값. 0 이면 UNIQUE, 1 이면 일반 인덱스다.
	private static final int UNIQUE = 0;
	private static final int NOT_UNIQUE = 1;

	@Autowired
	private UserRepository userRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);

	static Stream<Arguments> expectedUniqueKeys() {
		return Stream.of(
			Arguments.of("users", "uk_users_email", List.of("email")),
			Arguments.of("users", "uk_users_username", List.of("username")),
			Arguments.of("users", "uk_users_social_id_type", List.of("social_id", "social_type")),
			Arguments.of("password_reset_tokens", "uk_password_reset_tokens_token", List.of("token")),
			Arguments.of("password_reset_tokens", "uk_password_reset_tokens_user_id", List.of("user_id")));
	}

	static Stream<Arguments> expectedIndexes() {
		return Stream.of(
			Arguments.of("refresh_tokens", "idx_refresh_tokens_expires_at", List.of("expires_at")),
			Arguments.of("refresh_tokens", "idx_refresh_tokens_used_at", List.of("used_at")));
	}

	@AfterEach
	void deleteUsersOfThisRun() {
		jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", "unique-keys-%-" + runId);
	}

	@ParameterizedTest(name = "[{index}] {0}.{1}")
	@MethodSource("expectedUniqueKeys")
	@DisplayName("기대한 UNIQUE 가 그 이름과 컬럼 순서로 테스트 DB 에 있다")
	void uniqueKeyExistsWithColumnsInOrder(String table, String keyName, List<String> columns) {
		// when
		List<Map<String, Object>> rows = keyColumnsInTestDatabase(table, keyName);

		// then
		assertThat(rows)
			.as("%s 에 %s 가 없다. 엔티티 @Table 선언이 있는데도 없다면, 표에 중복 행이 있어 ddl-auto: update 가 UNIQUE 를 만들지"
				+ " 못한 것이다(기동은 멈추지 않는다). 테스트 DB 의 %s 에서 중복 행을 지우고 다시 돌린다.", table, keyName, table)
			.isNotEmpty();
		assertThat(rows)
			.as("%s 의 컬럼 순서. 같은 이름이 이미 있으면 ddl-auto: update 는 다시 만들지 않으므로, 선언을 바꿨다면 테스트 DB 에서"
				+ " 그 이름을 지우고 다시 돌린다.", keyName)
			.extracting(row -> row.get("COLUMN_NAME"))
			.containsExactlyElementsOf(columns);
		assertThat(rows).as("%s 의 NON_UNIQUE", keyName)
			.extracting(row -> ((Number) row.get("NON_UNIQUE")).intValue())
			.containsOnly(UNIQUE);
	}

	@ParameterizedTest(name = "[{index}] {0}.{1}")
	@MethodSource("expectedIndexes")
	@DisplayName("기대한 일반 인덱스가 UNIQUE 가 아닌 채로 그 이름과 컬럼 순서로 테스트 DB 에 있다")
	void indexExistsWithColumnsInOrder(String table, String keyName, List<String> columns) {
		// when
		List<Map<String, Object>> rows = keyColumnsInTestDatabase(table, keyName);

		// then
		assertThat(rows)
			.as("%s 에 %s 가 없다. 엔티티 @Table 의 indexes 에 같은 이름이 있는지 본다. ddl-auto: update 는 없는 인덱스를"
				+ " 기동할 때 만든다.", table, keyName)
			.isNotEmpty();
		assertThat(rows)
			.as("%s 의 컬럼 순서. 같은 이름이 이미 있으면 ddl-auto: update 는 다시 만들지 않으므로, 선언을 바꿨다면 테스트 DB 에서"
				+ " 그 이름을 지우고 다시 돌린다.", keyName)
			.extracting(row -> row.get("COLUMN_NAME"))
			.containsExactlyElementsOf(columns);
		assertThat(rows).as("%s 의 NON_UNIQUE", keyName)
			.extracting(row -> ((Number) row.get("NON_UNIQUE")).intValue())
			.containsOnly(NOT_UNIQUE);
	}

	@Test
	@DisplayName("같은 이메일로 두 번째 회원을 저장하면 uk_users_email 에 걸려 UNIQUE 위반으로 판별되는 예외가 나고 행은 하나만 남는다")
	void secondUserWithSameEmailIsRejected() {
		// given
		String email = "unique-keys-" + runId + "@example.com";
		userRepository.saveAndFlush(emailMember("first", email));

		// when
		Throwable thrown = catchThrowable(() -> userRepository.saveAndFlush(emailMember("second", email)));

		// then
		assertThat(thrown).isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
			assertThat(UniqueConstraintViolations.isUniqueViolation(e)).as("UNIQUE 위반으로 판별한다").isTrue();
			assertThat(e.getMostSpecificCause()).as("MySQL 이 알려 준 제약 이름")
				.hasMessageContaining("uk_users_email");
		});
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("이메일이 없는(NULL) 소셜 회원 두 명은 uk_users_email 에 걸리지 않고 둘 다 저장된다")
	void usersWithoutEmailAreAllSaved() {
		// given
		userRepository.saveAndFlush(kakaoMemberWithoutEmail("first"));

		// when
		Throwable thrown = catchThrowable(() -> userRepository.saveAndFlush(kakaoMemberWithoutEmail("second")));

		// then
		assertThat(thrown).isNull();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM users WHERE username LIKE ? AND email IS NULL", Integer.class,
			"unique-keys-%-" + runId))
			.isEqualTo(2);
	}

	@Test
	@DisplayName("소셜 로그인의 첫 조회(제공자와 사용자 번호)는 표 전체를 훑지 않고 uk_users_social_id_type 으로 한 행을 찾는다")
	void socialLoginLookupUsesSocialIdTypeKey() {
		// given: 찾는 행이 없으면 MySQL 이 "no matching row in const table" 로 끝내 key 가 비므로 찾을 행을 먼저 저장한다
		User kakaoMember = userRepository.saveAndFlush(kakaoMemberWithoutEmail("lookup"));

		// when: findBySocialTypeAndSocialId 가 만드는 조건과 같은 SQL
		Map<String, Object> plan = jdbcTemplate.queryForMap(
			"EXPLAIN SELECT id FROM users WHERE social_type = ? AND social_id = ?", "KAKAO",
			kakaoMember.getSocialId());

		// then: const 는 UNIQUE 의 모든 컬럼을 값으로 찾아 많아야 한 행만 읽는다는 뜻이다
		assertThat(plan.get("key")).isEqualTo("uk_users_social_id_type");
		assertThat(plan.get("type")).as("접근 방식").isEqualTo("const");
	}

	private List<Map<String, Object>> keyColumnsInTestDatabase(String table, String keyName) {
		return jdbcTemplate.queryForList(
			"SELECT COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS"
				+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX",
			table, keyName);
	}

	// 이메일로 가입한 회원. 실제 이메일 가입은 username 과 email 이 같지만, 여기서는 이메일만 겹치게 username 을 label 로 다르게 만든다.
	private User emailMember(String label, String email) {
		return User.create(username(label), "이메일 회원", "encoded-password", email, LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false);
	}

	// 이메일 제공에 동의하지 않은 카카오 회원
	private User kakaoMemberWithoutEmail(String label) {
		return User.createByOauth(username(label), "카카오 회원", null, "kakao-" + label + "-" + runId,
			SocialType.KAKAO);
	}

	private String username(String label) {
		return "unique-keys-" + label + "-" + runId;
	}
}
