package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 User 에 id 를 채우거나 역할을 바꾼다. id 는 DB 가 채우고 역할을 바꾸는 기능은 운영 코드에 없어서 User 에 바꾸는 메서드가
 * 없다. 그래서 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다. 필드 이름이 바뀌면 이 클래스만 고친다.
 */
public final class UserFixture {

	private UserFixture() {
	}

	/**
	 * DB 에 저장된 회원처럼 id 를 채우고, 받은 user 를 그대로 돌려준다.
	 */
	public static User withId(User user, Long id) {
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	/**
	 * 역할을 바꾸고, 받은 user 를 그대로 돌려준다. 가입한 회원은 언제나 USER 로 시작한다.
	 */
	public static User withRole(User user, Role role) {
		ReflectionTestUtils.setField(user, "role", role);
		return user;
	}
}
