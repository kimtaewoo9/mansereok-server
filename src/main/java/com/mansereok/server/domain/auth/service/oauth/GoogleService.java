package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.GoogleProfileDto;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class GoogleService {

	@Value("${oauth.google.client-id}")
	private String googleClientId;

	@Value("${oauth.google.client-secret}")
	private String googleClientSecret;

	@Value("${oauth.google.redirect-uri}")
	private String googleRedirectUri;

	private final RestClient restClient;

	private final OauthProviderCalls providerCalls = new OauthProviderCalls(SocialType.GOOGLE);

	/**
	 * 프론트엔드가 받은 인가 코드로 구글 로그인을 끝내고 사용자 정보를 돌려준다. 인가 코드를 액세스 토큰으로 바꾼 뒤 그 토큰으로
	 * 프로필을 읽는다. 액세스 토큰은 이 메서드 밖으로 나가지 않는다.
	 *
	 * @throws OauthLoginException               구글이 요청을 거절했거나(인가 코드 재사용·만료 등) 토큰·사용자 번호를 주지 않음
	 * @throws OauthProviderUnavailableException 구글에 닿지 못했거나 시간 안에 답이 없거나 5xx·408·429
	 */
	public OauthProfile authenticate(String code) {
		String accessToken = providerCalls.accessTokenOf(
			providerCalls.send("토큰 교환", () -> requestAccessToken(code)));
		GoogleProfileDto profile = providerCalls.send("프로필 조회", () -> requestProfile(accessToken));
		return providerCalls.profileOf(profile, GoogleProfileDto::toOauthProfile);
	}

	// 인가 코드를 액세스 토큰으로 바꾼다.
	private AccessTokenDto requestAccessToken(String code) {
		// form-data 형식 .. MultiValueMap 을 통해 자동으로 form-data 형식으로 body 조립 가능 .
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", googleClientId);
		params.add("client_secret", googleClientSecret);
		params.add("redirect_uri", googleRedirectUri);
		params.add("grant_type", "authorization_code"); // 사용 중인 권한 부여 방식 .. 인가 코드를 사용하는 경우 !.

		return restClient.post()
			.uri("https://oauth2.googleapis.com/token")
			// form-data 형식 (키-값 쌍)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.body(AccessTokenDto.class);
	}

	// profile 받을때는 access token 만 있으면 됨 .
	private GoogleProfileDto requestProfile(String accessToken) {
		return restClient.get()
			.uri("https://openidconnect.googleapis.com/v1/userinfo")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.body(GoogleProfileDto.class);
	}
}
