package com.mansereok.server.domain.auth.dto.request;

/**
 * 가입과 비밀번호 재설정이 함께 쓰는 비밀번호 규칙.
 *
 * <p>두 요청이 같은 길이를 요구하도록 값을 이곳 한 곳에만 둔다. 예전에는 가입 요청에만 6자 규칙이 있어서 재설정으로는 빈 문자열이나
 * 한 글자 비밀번호도 저장됐다. 검증 애너테이션의 속성으로 쓰므로 컴파일 시점 상수로 둔다.
 */
public final class PasswordRule {

	public static final int MIN_LENGTH = 6;

	/**
	 * 비밀번호를 UTF-8 로 바꾼 바이트 수의 상한. BCryptPasswordEncoder.encode 는 72바이트를 넘는 비밀번호에
	 * IllegalArgumentException("password cannot be more than 72 bytes")을 던지고, 그 영어 문구가 400 본문에 그대로 나간다.
	 * 입구에서 먼저 막는다. 영문·숫자는 한 글자가 1바이트, 한글은 3바이트다.
	 */
	public static final int MAX_BYTES = 72;

	public static final String REQUIRED_MESSAGE = "비밀번호는 필수입니다";

	public static final String TOO_SHORT_MESSAGE = "비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다.";

	public static final String TOO_LONG_MESSAGE =
		"비밀번호는 " + MAX_BYTES + "바이트(영문·숫자 " + MAX_BYTES + "자, 한글 " + MAX_BYTES / 3 + "자)까지 입력할 수 있습니다.";

	private PasswordRule() {
	}
}
