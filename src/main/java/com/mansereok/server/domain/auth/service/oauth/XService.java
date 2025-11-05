package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XProfileDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XProfileResponse;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class XService {

	@Value("${oauth.x.client-id}")
	private String xClientId;

	@Value("${oauth.x.client-secret}")
	private String xClientSecret;

	@Value("${oauth.x.redirect-uri}")
	private String xRedirectUri;

	private static final String TOKEN_URI = "https://api.twitter.com/2/oauth2/token";
	private static final String X_PROFILE_API_URL = "https://api.twitter.com/2/users/me?user.fields=description,location,profile_image_url,created_at,verified,confirmed_email";

	private final RestClient restClient;

	// X 로그인 할떄, PKCE가 반드시 있어야함 .
	public AccessTokenDto getAccessToken(String code, String codeVerifier) {
		String auth = Base64.getEncoder().encodeToString(
			(xClientId + ":" + xClientSecret).getBytes()
		);

		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("grant_type", "authorization_code");
		params.add("code", code);
		params.add("redirect_uri", xRedirectUri);
		params.add("code_verifier", codeVerifier); // PKCE 필수
		// client_id 랑 client_secret 이랑 헤더에 담아서 전송함 !.

		ResponseEntity<AccessTokenDto> response = restClient.post()
			.uri(TOKEN_URI)
			.header("Content-Type", "application/x-www-form-urlencoded") // 폼데이터 형식임을 알려줘야함 .
			.header("Authorization", "Basic " + auth)
			.body(params)
			.retrieve()
			.toEntity(AccessTokenDto.class);
		log.info("response.getBody().getAccessToken={}", response.getBody().getAccess_token());

		return response.getBody();
	}

	public XProfileDto getXProfileDto(String accessToken) {
		try {
			ResponseEntity<XProfileResponse> response = restClient.get()
				.uri(X_PROFILE_API_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.retrieve()
				.toEntity(XProfileResponse.class);

			XProfileResponse profileResponse = response.getBody();
			if (profileResponse != null && profileResponse.getData() != null) {
				// 실제 프로필 정보는 data 필드 안에 있습니다.
				log.info("response.getBody(): {}", profileResponse);
				return profileResponse.getData();
			}

			throw new RuntimeException("X로 부터 프로필을 받지 못하였습니다.");
		} catch (Exception e) {
			log.error("Failed to get X profile: ", e);
			throw new RuntimeException("Failed to fetch X profile.", e);
		}
	}
}
