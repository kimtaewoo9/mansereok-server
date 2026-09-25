package com.mansereok.server.global.exception;

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
