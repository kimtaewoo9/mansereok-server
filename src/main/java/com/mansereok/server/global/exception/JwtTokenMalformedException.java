package com.mansereok.server.global.exception;

import org.springframework.http.HttpStatus;

/**
 * JWT 토큰 형식이 잘못된 경우 발생하는 예외
 */
public class JwtTokenMalformedException extends JwtAuthenticationException {

	public JwtTokenMalformedException(String message) {
		super(message, HttpStatus.BAD_REQUEST.value(), "JWT_TOKEN_MALFORMED");
	}
}
