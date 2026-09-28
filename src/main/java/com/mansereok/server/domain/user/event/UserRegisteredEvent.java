package com.mansereok.server.domain.user.event;

import com.mansereok.server.domain.user.entity.User;
import java.time.LocalDateTime;

/**
 * 새 회원이 저장됐을 때 UserService 가 가입 트랜잭션 안에서 발행하는 이벤트. {@link UserNotificationListener} 가 커밋 뒤에 받아
 * 가입 알림을 보낸다.
 *
 * <p>알림에 쓸 값을 그대로 싣는다. 리스너가 커밋 뒤에 사용자를 다시 읽지 않아도 되고, 만든 뒤에는 값이 바뀌지 않는다.
 *
 * @param userId     새 회원 id
 * @param name       표시 이름. 없으면 null
 * @param email      이메일. 이메일 없는 소셜 가입이면 null
 * @param signupPath 가입 경로. 이메일 가입은 "일반 회원가입", 소셜 가입은 "KAKAO OAuth" 처럼 제공자 이름 뒤에 " OAuth"
 * @param createdAt  가입 시각
 */
public record UserRegisteredEvent(
	Long userId,
	String name,
	String email,
	String signupPath,
	LocalDateTime createdAt
) {

	public static UserRegisteredEvent of(User user, String signupPath) {
		return new UserRegisteredEvent(user.getId(), user.getName(), user.getEmail(), signupPath,
			user.getCreatedAt());
	}

	/**
	 * 로그에 이메일과 이름이 남지 않도록 회원 id 와 가입 경로만 보여 준다.
	 */
	@Override
	public String toString() {
		return "UserRegisteredEvent[userId=" + userId + ", signupPath=" + signupPath + "]";
	}
}
