package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XProfileDto {

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
}
