package com.mansereok.server.domain.user.event;

/**
 * 비밀번호 재설정 토큰을 새로 만들거나 바꿨을 때 UserService 가 재설정 요청 트랜잭션 안에서 발행하는 이벤트.
 * {@link PasswordResetMailListener} 가 커밋 뒤에 받아 재설정 메일을 보낸다.
 *
 * @param userId 메일을 받을 회원 id. 로그에 남기는 값이다.
 * @param email  메일을 받을 주소
 * @param token  메일 링크에 넣을 재설정 토큰
 */
public record PasswordResetRequestedEvent(Long userId, String email, String token) {

	/**
	 * 로그에 이메일과 재설정 토큰이 남지 않도록 회원 id 만 보여 준다. 토큰이 로그에 남으면 로그를 읽는 사람이 비밀번호를 바꿀 수 있다.
	 */
	@Override
	public String toString() {
		return "PasswordResetRequestedEvent[userId=" + userId + "]";
	}
}
