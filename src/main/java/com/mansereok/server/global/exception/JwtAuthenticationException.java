package com.mansereok.server.global.exception;


import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * JWT 인증 관련 예외의 부모 클래스
 */
@Getter
public class JwtAuthenticationException extends AuthenticationException {

	private final int httpStatus;
	private final String errorCode;

	public JwtAuthenticationException(String message, int httpStatus, String errorCode) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	public JwtAuthenticationException(String message, Throwable cause, int httpStatus,
		String errorCode) {
		super(message, cause);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}
}
