package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.entity.SocialType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverProfileDto {

	private String resultcode;
	private String message;
	private Response response;

	@Data
	public static class Response {

		private String id; // social id
		private String nickname;
		private String email;
		private String name;
	}

	/**
	 * 네이버는 이메일 인증 여부를 알려 주지 않는다. 그래도 지금은 네이버 이메일을 신뢰해 같은 이메일의 기존 계정에 붙인다. 예전에
	 * 이메일로 기존 계정에 붙어 로그인하던 네이버 사용자는 계정에 네이버 사용자 번호가 기록돼 있지 않아서, 신뢰하지 않으면 새로
	 * 가입하려다 같은 이메일 중복(409)으로 로그인이 막힌다. 계정마다 어떤 소셜 계정이 붙었는지 따로 기록하게 되면 다시 정한다.
	 */
	public OauthProfile toOauthProfile() {
		if (response == null) {
			throw new IllegalArgumentException("NAVER 로그인 응답에 사용자 정보가 없습니다.");
		}
		return new OauthProfile(SocialType.NAVER, response.getId(), response.getEmail(),
			response.getName(), true);
	}
}
