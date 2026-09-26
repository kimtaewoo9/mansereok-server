package com.mansereok.server.global.exception;

/**
 * JWT 토큰 형식이 잘못된 경우 발생하는 예외
 */
public class JwtTokenMalformedException extends JwtAuthenticationException {

	public JwtTokenMalformedException(String message) {
		super(message, JwtErrorCode.TOKEN_MALFORMED);
	}
}
