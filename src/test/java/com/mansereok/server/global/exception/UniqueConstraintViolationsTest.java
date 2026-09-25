package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PersistenceException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DataIntegrityViolationException 이 UNIQUE 위반일 때만 true 를 돌려주는지 확인한다.
 *
 * <p>예외는 Hibernate MySQL 방언이 실제로 만드는 모양대로 손으로 만든다. 중복 키(1062)는 ConstraintKind.UNIQUE 를 담고, NOT NULL
 * 위반(1048)처럼 다른 제약 위반은 종류를 따로 적지 않아 ConstraintKind.OTHER 가 된다. 방언과 스프링 번역이 정말 이 모양을
 * 만드는지는 실제 MySQL 에 저장해 보는 UniqueConstraintViolationsMySqlTest 가 확인한다.
 *
 * <p>이 파일은 결제 스택이 그대로 복사해 쓴다.
 */
class UniqueConstraintViolationsTest {

	@Test
	@DisplayName("원인이 Hibernate 의 UNIQUE 위반이면 true 를 돌려준다")
	void uniqueViolation() {
		// given
		DataIntegrityViolationException e = new DataIntegrityViolationException("중복 키",
			hibernateUniqueViolation());

		// when & then
		assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isTrue();
	}

	@Test
	@DisplayName("원인이 NOT NULL 위반이면 false 를 돌려준다")
	void notNullViolation() {
		// given
		ConstraintViolationException notNull = new ConstraintViolationException(
			"could not execute statement",
			new SQLIntegrityConstraintViolationException("Column 'email' cannot be null", "23000", 1048),
			"insert into users (email) values (?)");
		DataIntegrityViolationException e = new DataIntegrityViolationException("NOT NULL", notNull);

		// when & then
		assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isFalse();
	}

	@Test
	@DisplayName("원인이 없으면 false 를 돌려준다")
	void noCause() {
		// given
		DataIntegrityViolationException e = new DataIntegrityViolationException("원인 없음");

		// when & then
		assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isFalse();
	}

	@Test
	@DisplayName("UNIQUE 위반이 다른 예외에 한 겹 더 싸여 있어도 원인을 따라가 true 를 돌려준다")
	void uniqueViolationWrappedTwice() {
		// given: 실제 MySQL 에 저장할 때는 DataIntegrityViolationException 바로 아래에 Hibernate 예외가 온다.
		// 이 테스트는 그 사이에 다른 예외가 끼어 있어도 원인 체인을 끝까지 따라가는지만 본다.
		DataIntegrityViolationException e = new DataIntegrityViolationException("커밋 중 중복 키",
			new PersistenceException("커밋 실패", hibernateUniqueViolation()));

		// when & then
		assertThat(UniqueConstraintViolations.isUniqueViolation(e)).isTrue();
	}

	private static ConstraintViolationException hibernateUniqueViolation() {
		return new ConstraintViolationException("could not execute statement",
			new SQLIntegrityConstraintViolationException(
				"Duplicate entry 'a@example.com' for key 'users.email'", "23000", 1062),
			"insert into users (email) values (?)", ConstraintKind.UNIQUE, "users.email");
	}
}
