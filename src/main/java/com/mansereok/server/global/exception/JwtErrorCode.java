package com.mansereok.server.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * JWT 인증 실패의 종류. 응답 상태, 응답 본문의 error 값, 사용자에게 보여 줄 안내 문구를 한곳에 묶는다.
 *
 * <p>error 값은 응답 본문에 그대로 실려 프론트엔드가 읽을 수 있으므로 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum JwtErrorCode {

	TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "JWT_TOKEN_MISSING",
		"Authorization 헤더에 'Bearer {토큰}' 형식으로 JWT 토큰을 포함해주세요."),
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "JWT_TOKEN_EXPIRED",
		"토큰이 만료되었습니다. refresh token을 사용하여 새 토큰을 발급받아주세요."),
	TOKEN_MALFORMED(HttpStatus.BAD_REQUEST, "JWT_TOKEN_MALFORMED",
		"토큰 형식이 올바르지 않습니다. 올바른 JWT 토큰인지 확인해주세요."),
	TOKEN_UNSUPPORTED(HttpStatus.BAD_REQUEST, "JWT_TOKEN_UNSUPPORTED",
		"지원하지 않는 토큰 형식입니다."),
	// 서명이 틀린 토큰과, 서명은 맞지만 이 서버가 발급하지 않은(발급자가 다른) 토큰을 함께 뜻한다.
	SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "JWT_SIGNATURE_INVALID",
		"토큰이 변조되었거나 유효하지 않습니다. 새로 로그인해주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "JWT_INTERNAL_ERROR",
		"서버에서 토큰을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");

	private final HttpStatus status;
	private final String error;
	private final String hint;
}
