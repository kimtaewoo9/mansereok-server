package com.mansereok.server.domain.user.event;

/**
 * 회원 탈퇴를 처리했을 때 UserService 가 탈퇴 트랜잭션 안에서 발행하는 이벤트. {@link UserNotificationListener} 가 커밋 뒤에
 * 받아 탈퇴 알림을 보낸다.
 *
 * <p>커밋 뒤에는 users 행이 없으므로 알림에 쓸 이름과 이메일을 지우기 전에 담아 둔다.
 *
 * @param userId 탈퇴한 회원 id
 * @param name   표시 이름. 없으면 null
 * @param email  이메일. 이메일 없는 소셜 회원이면 null
 */
public record UserWithdrawnEvent(Long userId, String name, String email) {

	/**
	 * 로그에 이메일과 이름이 남지 않도록 회원 id 만 보여 준다.
	 */
	@Override
	public String toString() {
		return "UserWithdrawnEvent[userId=" + userId + "]";
	}
}
