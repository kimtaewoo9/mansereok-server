package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoProfileDto {

	private String id;
	private KakaoAccount kakao_account;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class KakaoAccount {

		private String email;
		private Profile profile;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Profile {

		private String nickname;
		private String profile_image_url;
	}

	public String getNickname() {
		if (kakao_account != null && kakao_account.getProfile() != null) {
			return kakao_account.getProfile().getNickname();
		}
		return "카카오 사용자";
	}
}
