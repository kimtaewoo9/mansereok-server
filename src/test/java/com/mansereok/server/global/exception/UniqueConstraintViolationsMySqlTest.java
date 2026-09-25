package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link UniqueConstraintViolations} 가 기대는 두 가지를 실제 MySQL 로 확인한다.
 *
 * <ol>
 *   <li>Hibernate MySQL 방언이 중복 키(1062)는 ConstraintKind.UNIQUE 로, NOT NULL 위반(1048)은 다른 종류로 표시한다.</li>
 *   <li>스프링이 그 Hibernate 예외를 DataIntegrityViolationException 의 원인으로 담는다.</li>
 * </ol>
 *
 * <p>둘 다 방언과 번역기에 달린 문제라 손으로 만든 예외로는 확인할 수 없다. 하나라도 바뀌면 경합이 난 호출 지점이 UNIQUE 위반을
 * 알아보지 못해 "이미 있다" 대신 500 이 나간다. 테스트 DB 에 이미 있는 discount_codes.code 의 UNIQUE·NOT NULL 제약을 쓴다.
 */
class UniqueConstraintViolationsMySqlTest extends LocalMySqlTest {

	private static final int MYSQL_DUPLICATE_KEY = 1062;
	private static final int MYSQL_COLUMN_CANNOT_BE_NULL = 1048;

	@Autowired
	private DiscountCodeRepository discountCodeRepository;

	private final String code = "UNIQUE_CHECK_" + UUID.randomUUID().toString().substring(0, 8);

	@AfterEach
	void deleteDiscountCode() {
		jdbcTemplate.update("DELETE FROM discount_codes WHERE code = ?", code);
	}

	@Test
	@DisplayName("이미 있는 code 로 다시 저장해 난 예외는 UNIQUE 위반으로 판별한다")
	void duplicateCodeIsUniqueViolation() {
		// given
		discountCodeRepository.saveAndFlush(DiscountCodeFixture.usableCode().withoutId().code(code).build());

		// when
		Throwable thrown = catchThrowable(() -> discountCodeRepository.saveAndFlush(
			DiscountCodeFixture.usableCode().withoutId().code(code).build()));

		// then
		assertThat(thrown).isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
			assertThat(e.getMostSpecificCause())
				.as("MySQL 이 준 오류가 중복 키(1062)여야 한다")
				.isInstanceOfSatisfying(SQLException.class,
					sqlException -> assertThat(sqlException.getErrorCode()).isEqualTo(MYSQL_DUPLICATE_KEY));
			assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isTrue();
		});
	}

	@Test
	@DisplayName("code 없이 저장해 NOT NULL 을 어긴 예외는 UNIQUE 위반이 아니라고 판별한다")
	void nullCodeIsNotUniqueViolation() {
		// when
		Throwable thrown = catchThrowable(() -> discountCodeRepository.saveAndFlush(
			DiscountCodeFixture.usableCode().withoutId().code(null).build()));

		// then
		assertThat(thrown).isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
			assertThat(e.getMostSpecificCause())
				.as("Hibernate 가 미리 막지 않고 MySQL 이 NOT NULL 위반(1048)을 알려야 한다")
				.isInstanceOfSatisfying(SQLException.class,
					sqlException -> assertThat(sqlException.getErrorCode()).isEqualTo(MYSQL_COLUMN_CANNOT_BE_NULL));
			assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isFalse();
		});
	}
}
