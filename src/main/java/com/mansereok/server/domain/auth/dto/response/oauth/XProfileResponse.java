package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * X 사용자 정보(/2/users/me) 응답. 실제 프로필은 data 안에 있다. 토큰이 잘못되면 X 는 200 이어도 data 없이 errors 만 준다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XProfileResponse {

	private XProfileDto data;

	/**
	 * data 가 없으면 누구인지 알 수 없어 IllegalArgumentException 을 던진다.
	 */
	public OauthProfile toOauthProfile() {
		if (data == null) {
			throw new IllegalArgumentException("X 로그인 응답에 사용자 정보(data)가 없습니다.");
		}
		return data.toOauthProfile();
	}
}
