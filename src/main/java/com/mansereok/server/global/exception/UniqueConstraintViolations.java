package com.mansereok.server.global.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DataIntegrityViolationException 이 UNIQUE 제약 위반 때문에 났는지 가려낸다.
 *
 * <p>스프링은 UNIQUE·NOT NULL·FK·길이 초과 위반을 모두 같은 DataIntegrityViolationException 으로 번역한다. 그래서 전역 처리기에서
 * 한꺼번에 409 로 바꾸지 않는다. 같은 값이 동시에 들어와 부딪힐 수 있는 호출 지점(가입, 리뷰 작성, 결제 저장 등)이 이 판별로 UNIQUE
 * 위반일 때만 "이미 있다" 는 도메인 예외로 바꿔 던지고, 다른 위반은 그대로 던진다.
 *
 * <p>JPA(Hibernate) 로 저장할 때 난 예외를 위한 판별이다. Hibernate 의 MySQL 방언은 오류 코드 1062(중복 키)를
 * {@link ConstraintKind#UNIQUE} 로 표시하고, 스프링은 그 Hibernate 예외를 원인으로 담는다. JdbcTemplate 으로 직접 실행한 SQL 은
 * 원인에 Hibernate 예외가 없어 false 가 나오므로, 그때는 스프링이 따로 주는 DuplicateKeyException 타입으로 확인한다.
 *
 * <p>이 파일은 결제 스택이 그대로 복사해 쓴다. 고칠 때는 두 스택에 같게 고친다.
 */
public final class UniqueConstraintViolations {

	private UniqueConstraintViolations() {
	}

	/**
	 * 원인 체인에서 가장 먼저 나오는 Hibernate 제약 위반 예외의 종류가 UNIQUE 인지 본다.
	 *
	 * @return UNIQUE 위반이면 true. 다른 제약 위반이거나 원인에 Hibernate 제약 위반 예외가 없으면 false.
	 */
	public static boolean isUniqueViolation(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintKind.UNIQUE;
			}
		}
		return false;
	}
}
