package com.mansereok.server.global.exception;


import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * JWT 인증 관련 예외의 부모 클래스. 어떤 종류의 실패인지는 {@link JwtErrorCode} 로 나타낸다.
 */
@Getter
public class JwtAuthenticationException extends AuthenticationException {

	private final JwtErrorCode errorCode;

	public JwtAuthenticationException(String message, JwtErrorCode errorCode) {
		super(message);
		this.errorCode = errorCode;
	}

	public JwtAuthenticationException(String message, Throwable cause, JwtErrorCode errorCode) {
		super(message, cause);
		this.errorCode = errorCode;
	}
}
