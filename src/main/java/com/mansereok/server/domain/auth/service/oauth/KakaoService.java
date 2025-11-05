package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.KakaoProfileDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoService {

	@Value("${oauth.kakao.client-id}")
	private String kakaoClientId;

	@Value("${oauth.kakao.redirect-uri}")
	private String kakaoRedirectUri;

	private final RestClient restClient;

	public AccessTokenDto getAccessTokenDto(String code) {

		// 인가코드, client_id, redirect_uri, grant_type
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", kakaoClientId);
		params.add("redirect_uri", kakaoRedirectUri);
		params.add("grant_type", "authorization_code");

		ResponseEntity<AccessTokenDto> response = restClient.post()
			.uri("https://kauth.kakao.com/oauth/token")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.toEntity(AccessTokenDto.class);

		log.info("Kakao Access Token: {}", response.getBody());

		return response.getBody();
	}

	public KakaoProfileDto getKakaoProfileDto(String accessToken) {
		ResponseEntity<KakaoProfileDto> response = restClient.get()
			.uri("https://kapi.kakao.com/v2/user/me")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.toEntity(KakaoProfileDto.class);

		log.info("Kakao Profile: {}", response.getBody());
		return response.getBody();
	}
}
