package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
	private String picture; // 프로필 사진
	private String locale; // 사용자가 설정한 언어
}
