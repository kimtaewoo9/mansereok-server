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
 * X 사용자 정보(/2/users/me)의 data 부분. toString 은 로그에 이메일·표시 이름·아이디(@username)가 남지 않도록 사용자 번호만
 * 보여 준다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class XProfileDto {

	@ToString.Include
	private String id;
	private String name; // 이름 ..
	private String username; // 사용자명 (고유 ID)
	private String description;
	private String location;

	@JsonProperty("profile_image_url")
	private String profileImageUrl;

	@JsonProperty("created_at")
	private String createdAt;

	@JsonProperty("confirmed_email")
	private String email;

	/**
	 * X 의 confirmed_email 은 X 가 확인한 주소만 내려오므로 신뢰한다. name 은 사용자가 아무 값으로나 바꿀 수 있는 표시
	 * 이름이다.
	 */
	public OauthProfile toOauthProfile() {
		return new OauthProfile(SocialType.X, id, email, name, true);
	}
}
