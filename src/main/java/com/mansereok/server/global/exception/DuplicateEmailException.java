package com.mansereok.server.global.exception;

/**
 * 이미 있는 계정과 겹쳐 가입할 수 없을 때 409 로 답하는 예외. 이름과 달리 이메일만이 아니라 username, 소셜 계정(social_id,
 * social_type)이 겹쳐 users 의 UNIQUE 에 걸린 가입도 이 예외로 끝난다.
 */
public class DuplicateEmailException extends RuntimeException {

	public DuplicateEmailException(String message) {
		super(message);
	}

	/**
	 * DB 의 UNIQUE 위반을 이 예외로 바꿔 던질 때 쓴다. 원래 예외를 원인으로 담아 어느 제약에 걸렸는지 로그에서 볼 수 있게 한다.
	 */
	public DuplicateEmailException(String message, Throwable cause) {
		super(message, cause);
	}
}
