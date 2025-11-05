package com.mansereok.server.global.exception;

import org.springframework.http.HttpStatus;

/**
 * JWT 토큰이 만료된 경우 발생하는 예외
 */
public class JwtTokenExpiredException extends JwtAuthenticationException {

	public JwtTokenExpiredException(String message) {
		super(message, HttpStatus.UNAUTHORIZED.value(), "JWT_TOKEN_EXPIRED");
	}
}
