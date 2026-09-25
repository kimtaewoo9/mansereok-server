package com.mansereok.server.domain.auth.dto.request;

/**
 * 가입과 비밀번호 재설정이 함께 쓰는 비밀번호 규칙.
 *
 * <p>두 요청이 같은 길이를 요구하도록 값을 이곳 한 곳에만 둔다. 예전에는 가입 요청에만 6자 규칙이 있어서 재설정으로는 빈 문자열이나
 * 한 글자 비밀번호도 저장됐다. 검증 애너테이션의 속성으로 쓰므로 컴파일 시점 상수로 둔다.
 */
public final class PasswordRule {

	public static final int MIN_LENGTH = 6;

	public static final String REQUIRED_MESSAGE = "비밀번호는 필수입니다";

	public static final String TOO_SHORT_MESSAGE = "비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다.";

	private PasswordRule() {
	}
}
