package com.mansereok.server.domain.auth.dto.request;

/**
 * 가입과 프로필 수정이 함께 쓰는 이름(사용자 본명) 규칙.
 *
 * <p>두 요청이 같은 길이를 요구하도록 값을 이곳 한 곳에만 둔다. 예전에는 가입만 3자 이상을 요구해 "이훈" 같은 두 글자 이름으로는
 * 가입할 수 없었고, 프로필 수정에는 길이 제한이 없었다. 최소 길이는 따로 두지 않는다. 공백만 있는 이름은 가입에서는
 * {@code @NotBlank} 가 막고, 프로필 수정에서는 이름을 바꾸지 않는 것으로 본다. 검증 애너테이션의 속성으로 쓰므로 컴파일 시점
 * 상수로 둔다.
 */
public final class NameRule {

	public static final int MAX_LENGTH = 20;

	public static final String REQUIRED_MESSAGE = "이름은 필수입니다.";

	public static final String TOO_LONG_MESSAGE = "이름은 " + MAX_LENGTH + "자까지 입력할 수 있습니다.";

	private NameRule() {
	}
}
