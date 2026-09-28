package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.entity.SocialType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 카카오 사용자 정보(/v2/user/me) 응답. 필드는 자바 이름으로 두고 카카오의 snake_case 키는 @JsonProperty 로 읽는다.
 *
 * <p>toString 은 로그에 이메일·닉네임·사진 주소가 남지 않도록 사용자 번호와 이메일 확인 여부만 보여 준다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoProfileDto {

	@ToString.Include
	private String id;

	@ToString.Include
	@JsonProperty("kakao_account")
	private KakaoAccount kakaoAccount;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@ToString(onlyExplicitlyIncluded = true)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class KakaoAccount {

		private String email;
		private Profile profile;

		// 이메일이 지금 쓸 수 있는 주소인지(다른 계정에 다시 쓰이지 않았는지)
		@ToString.Include
		@JsonProperty("is_email_valid")
		private Boolean emailValid;

		// 카카오가 이메일 주인을 확인했는지
		@ToString.Include
		@JsonProperty("is_email_verified")
		private Boolean emailVerified;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@ToString(onlyExplicitlyIncluded = true)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Profile {

		private String nickname;

		@JsonProperty("profile_image_url")
		private String profileImageUrl;
	}

	public String getNickname() {
		if (kakaoAccount != null && kakaoAccount.getProfile() != null) {
			return kakaoAccount.getProfile().getNickname();
		}
		return "카카오 사용자";
	}

	/**
	 * 카카오가 준 이메일은 is_email_valid 와 is_email_verified 가 둘 다 true 일 때만 신뢰한다. 사용자가 이메일 제공에
	 * 동의하지 않아 kakao_account 가 통째로 없으면 이메일 없음으로 본다.
	 */
	public OauthProfile toOauthProfile() {
		if (kakaoAccount == null) {
			return new OauthProfile(SocialType.KAKAO, id, null, getNickname(), false);
		}
		boolean emailTrusted = Boolean.TRUE.equals(kakaoAccount.getEmailValid())
			&& Boolean.TRUE.equals(kakaoAccount.getEmailVerified());
		return new OauthProfile(SocialType.KAKAO, id, kakaoAccount.getEmail(), getNickname(),
			emailTrusted);
	}
}
