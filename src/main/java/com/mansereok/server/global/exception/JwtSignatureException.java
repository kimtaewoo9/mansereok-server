package com.mansereok.server.global.exception;

import org.springframework.http.HttpStatus;

/**
 * JWT 토큰 서명 검증이 실패한 경우 발생하는 예외
 */
public class JwtSignatureException extends JwtAuthenticationException {

	public JwtSignatureException(String message) {
		super(message, HttpStatus.UNAUTHORIZED.value(), "JWT_SIGNATURE_INVALID");
	}
}
