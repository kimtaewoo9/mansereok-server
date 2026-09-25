package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.NaverProfileDto;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

	private final OauthProviderCalls providerCalls = new OauthProviderCalls(SocialType.NAVER);

	/**
	 * 프론트엔드가 받은 인가 코드와 state 로 네이버 로그인을 끝내고 사용자 정보를 돌려준다. 인가 코드를 액세스 토큰으로 바꾼 뒤 그
	 * 토큰으로 프로필을 읽는다. 액세스 토큰은 이 메서드 밖으로 나가지 않는다.
	 *
	 * <p>네이버는 잘못된 인가 코드에도 200 과 error 만 담은 본문을 준다. 이때는 액세스 토큰이 없으므로 프로필을 부르지 않고 로그인
	 * 실패로 끝낸다.
	 *
	 * @throws OauthLoginException               네이버가 요청을 거절했거나 토큰·사용자 번호를 주지 않음
	 * @throws OauthProviderUnavailableException 네이버에 닿지 못했거나 시간 안에 답이 없거나 5xx
	 */
	public OauthProfile authenticate(String code, String state) {
		String accessToken = providerCalls.accessTokenOf(
			providerCalls.send("토큰 교환", () -> requestAccessToken(code, state)));
		NaverProfileDto profile = providerCalls.send("프로필 조회", () -> requestProfile(accessToken));
		OauthProfile oauthProfile = providerCalls.profileOf(profile, NaverProfileDto::toOauthProfile);
		log.debug("소셜 로그인 제공자 인증 완료: provider={}, socialId={}", SocialType.NAVER,
			oauthProfile.socialId());
		return oauthProfile;
	}

	private AccessTokenDto requestAccessToken(String code, String state) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", naverClientId);
		params.add("client_secret", naverClientSecret);
		params.add("redirect_uri", naverRedirectUri);
		params.add("state", state);
		params.add("grant_type", "authorization_code");

		return restClient.post()
			.uri("https://nid.naver.com/oauth2.0/token")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.body(AccessTokenDto.class);
	}

	private NaverProfileDto requestProfile(String accessToken) {
		return restClient.get()
			.uri("https://openapi.naver.com/v1/nid/me")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.body(NaverProfileDto.class);
	}
}
