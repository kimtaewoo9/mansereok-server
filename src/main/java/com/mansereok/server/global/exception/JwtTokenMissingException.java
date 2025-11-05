package com.mansereok.server.global.exception;

import org.springframework.http.HttpStatus;

/**
 * JWT 토큰이 누락된 경우 발생하는 예외
 */
public class JwtTokenMissingException extends JwtAuthenticationException {

	public JwtTokenMissingException(String message) {
		super(message, HttpStatus.UNAUTHORIZED.value(), "JWT_TOKEN_MISSING");
	}
}
