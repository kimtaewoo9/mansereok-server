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
public class GoogleProfileDto {

	private String sub; // Oauth 회원가입이 되어있는지 확인하고 싶으면 이 sub 를 DB에 검색하면 됨.
	private String name;
	private String email;

	// 구글이 이 이메일의 주인을 확인했는지. 값이 없으면 확인되지 않은 것으로 본다.
	@JsonProperty("email_verified")
	private Boolean emailVerified;

	private String picture; // 프로필 사진
	private String locale; // 사용자가 설정한 언어

	/**
	 * 구글이 email_verified=true 로 확인해 준 이메일만 신뢰한다.
	 */
	public OauthProfile toOauthProfile() {
		return new OauthProfile(SocialType.GOOGLE, sub, email, name, Boolean.TRUE.equals(emailVerified));
	}
}
