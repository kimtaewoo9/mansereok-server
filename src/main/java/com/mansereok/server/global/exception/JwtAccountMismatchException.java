package com.mansereok.server.global.exception;

/**
 * 토큰은 유효하지만 subject(username)가 가리키는 계정이 없거나, 토큰을 발급받은 계정과 다른 계정(탈퇴 뒤 같은 이메일로 재가입)인
 * 경우 발생하는 예외
 */
public class JwtAccountMismatchException extends JwtAuthenticationException {

	public JwtAccountMismatchException(String message) {
		super(message, JwtErrorCode.ACCOUNT_MISMATCH);
	}
}
