package com.mansereok.server.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 지원하지 않는 JWT 토큰인 경우 발생하는 예외
 */
public class JwtUnsupportedException extends JwtAuthenticationException {

	public JwtUnsupportedException(String message) {
		super(message, HttpStatus.BAD_REQUEST.value(), "JWT_TOKEN_UNSUPPORTED");
	}
}
