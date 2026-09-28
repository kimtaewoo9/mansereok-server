package com.mansereok.server.domain.auth.dto.response;

import com.mansereok.server.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TokenRefreshResponse {

	private UserDto userDto;
	private String accessToken;

	/**
	 * 재발급 응답에 담는 회원 정보. JSON 키와 값의 타입(id·createdAt 도 문자열)은 프론트엔드가 읽는 모양 그대로 둔다.
	 *
	 * <p>같은 String 칸이 다섯 개라 위치로 넘기면 칸이 뒤바뀌어도 컴파일러가 모른다. 그래서 생성자를 막고 {@link #from(User)} 로만
	 * 만든다.
	 */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class UserDto {

		private String id;
		private String createdAt;
		private String email;
		// 회원의 표시 이름(User.name). username(이메일 가입자는 이메일, 소셜 가입자는 제공자의 사용자 번호)이 아니다.
		private String name;
		private String role;
		// 계정 잠금 기능이 없어 늘 false 다. 프론트엔드가 읽는 키라 남겨 둔다.
		private boolean locked;

		public static UserDto from(User user) {
			return new UserDto(
				user.getId().toString(),
				user.getCreatedAt().toString(),
				user.getEmail(),
				user.getName(),
				user.getRole().toString(),
				false
			);
		}
	}
}
