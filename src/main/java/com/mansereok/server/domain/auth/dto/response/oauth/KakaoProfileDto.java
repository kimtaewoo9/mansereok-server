package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.entity.SocialType;
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

		// 이메일이 지금 쓸 수 있는 주소인지(다른 계정에 다시 쓰이지 않았는지)
		@JsonProperty("is_email_valid")
		private Boolean emailValid;

		// 카카오가 이메일 주인을 확인했는지
		@JsonProperty("is_email_verified")
		private Boolean emailVerified;
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

	/**
	 * 카카오가 준 이메일은 is_email_valid 와 is_email_verified 가 둘 다 true 일 때만 신뢰한다. 사용자가 이메일 제공에
	 * 동의하지 않아 kakao_account 가 통째로 없으면 이메일 없음으로 본다.
	 */
	public OauthProfile toOauthProfile() {
		if (kakao_account == null) {
			return new OauthProfile(SocialType.KAKAO, id, null, getNickname(), false);
		}
		boolean emailTrusted = Boolean.TRUE.equals(kakao_account.getEmailValid())
			&& Boolean.TRUE.equals(kakao_account.getEmailVerified());
		return new OauthProfile(SocialType.KAKAO, id, kakao_account.getEmail(), getNickname(),
			emailTrusted);
	}
}
