package com.mansereok.server.domain.user.entity;

/**
 * 이름(User.name, 사용자 본명) 규칙. 가입과 프로필 수정이 같은 길이 한도를 쓰도록 값을 이곳 한 곳에만 둔다.
 *
 * <ul>
 *   <li>가입: RegisterRequest 가 {@code @NotBlank} 와 {@code @Size(max = MAX_LENGTH)} 로 검사한다. 최소 길이는 없다.</li>
 *   <li>프로필 수정: {@link User#updateProfile} 이 이름을 지금과 다른 값으로 바꿀 때만 길이를 검사한다. 소셜 가입은 제공자가 준
 *   이름을 길이 제한 없이 저장하므로, 20자가 넘는 지금 이름을 그대로 다시 보낸 요청은 받아들인다. 공백만 있는 이름은 이름을 바꾸지
 *   않는 것으로 본다.</li>
 * </ul>
 *
 * <p>검증 애너테이션의 속성으로 쓰므로 컴파일 시점 상수로 둔다.
 */
public final class NameRule {

	public static final int MAX_LENGTH = 20;

	public static final String REQUIRED_MESSAGE = "이름은 필수입니다.";

	public static final String TOO_LONG_MESSAGE = "이름은 " + MAX_LENGTH + "자까지 입력할 수 있습니다.";

	private NameRule() {
	}
}
