package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.GoogleProfileDto;
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
public class GoogleService {

	@Value("${oauth.google.client-id}")
	private String googleClientId;

	@Value("${oauth.google.client-secret}")
	private String googleClientSecret;

	@Value("${oauth.google.redirect-uri}")
	private String googleRedirectUri;

	private final RestClient restClient;

	// code -> 인가 코드 .. 프론트엔드에서 인가코드 전달해주면 google에 access token 요청 .
	public AccessTokenDto getAccessToken(String code) {
		// form-data 형식 .. MultiValueMap 을 통해 자동으로 form-data 형식으로 body 조립 가능 .
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", googleClientId);
		params.add("client_secret", googleClientSecret);
		params.add("redirect_uri", googleRedirectUri);
		params.add("grant_type", "authorization_code"); // 사용 중인 권한 부여 방식 .. 인가 코드를 사용하는 경우 !.

		ResponseEntity<AccessTokenDto> response = restClient.post()
			.uri("https://oauth2.googleapis.com/token")
			// form-data 형식 (키-값 쌍)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.toEntity(AccessTokenDto.class);

		log.info("Access token: {}", response.getBody());

		return response.getBody();
	}

	// profile 받을때는 access token 만 있으면 됨 .
	public GoogleProfileDto getGoogleProfile(String accessToken) {
		ResponseEntity<GoogleProfileDto> response = restClient.get()
			.uri("https://openidconnect.googleapis.com/v1/userinfo")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.toEntity(GoogleProfileDto.class);
		log.info("profile JSON: " + response.getBody());

		return response.getBody();
	}
}
