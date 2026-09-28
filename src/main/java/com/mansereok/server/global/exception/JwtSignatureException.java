package com.mansereok.server.global.exception;

/**
 * JWT 토큰 서명 검증이 실패했거나, 서명은 맞지만 발급자가 이 서버가 아닌 경우 발생하는 예외
 */
public class JwtSignatureException extends JwtAuthenticationException {

	public JwtSignatureException(String message) {
		super(message, JwtErrorCode.SIGNATURE_INVALID);
	}
}
