package com.mansereok.server.global.exception;

/**
 * JWT 토큰이 만료된 경우 발생하는 예외
 */
public class JwtTokenExpiredException extends JwtAuthenticationException {

	public JwtTokenExpiredException(String message) {
		super(message, JwtErrorCode.TOKEN_EXPIRED);
	}
}
