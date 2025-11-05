package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.NaverProfileDto;
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
public class NaverService {

	@Value("${oauth.naver.client-id}")
	private String naverClientId;

	@Value("${oauth.naver.client-secret}")
	private String naverClientSecret;

	@Value("${oauth.naver.redirect-uri}")
	private String naverRedirectUri;

	private final RestClient restClient;

	public AccessTokenDto getAccessTokenDto(String code, String state) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", naverClientId);
		params.add("client_secret", naverClientSecret);
		params.add("redirect_uri", naverRedirectUri);
		params.add("state", state);
		params.add("grant_type", "authorization_code");

		ResponseEntity<AccessTokenDto> response = restClient.post()
			.uri("https://nid.naver.com/oauth2.0/token")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.toEntity(AccessTokenDto.class);
		log.info("response.getBody().getAccessToken={}", response.getBody().getAccess_token());

		return response.getBody();
	}

	public NaverProfileDto getNaverProfileDto(String accessToken) {
		ResponseEntity<NaverProfileDto> response = restClient.get()
			.uri("https://openapi.naver.com/v1/nid/me")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.toEntity(NaverProfileDto.class);

		log.info("response.getBody(): {}", response.getBody());
		return response.getBody();
	}
}
