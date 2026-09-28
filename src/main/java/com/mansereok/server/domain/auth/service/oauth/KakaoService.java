package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.KakaoProfileDto;
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
public class KakaoService {

	@Value("${oauth.kakao.client-id}")
	private String kakaoClientId;

	@Value("${oauth.kakao.redirect-uri}")
	private String kakaoRedirectUri;

	private final RestClient restClient;

	private final OauthProviderCalls providerCalls = new OauthProviderCalls(SocialType.KAKAO);

	/**
	 * 프론트엔드가 받은 인가 코드로 카카오 로그인을 끝내고 사용자 정보를 돌려준다. 인가 코드를 액세스 토큰으로 바꾼 뒤 그 토큰으로
	 * 프로필을 읽는다. 액세스 토큰은 이 메서드 밖으로 나가지 않는다.
	 *
	 * @throws OauthLoginException               카카오가 요청을 거절했거나(같은 인가 코드 재사용 KOE320 등) 토큰·사용자 번호를 주지 않음
	 * @throws OauthProviderUnavailableException 카카오에 닿지 못했거나 시간 안에 답이 없거나 5xx·408·429
	 */
	public OauthProfile authenticate(String code) {
		String accessToken = providerCalls.accessTokenOf(
			providerCalls.send("토큰 교환", () -> requestAccessToken(code)));
		KakaoProfileDto profile = providerCalls.send("프로필 조회", () -> requestProfile(accessToken));
		return providerCalls.profileOf(profile, KakaoProfileDto::toOauthProfile);
	}

	private AccessTokenDto requestAccessToken(String code) {
		// 인가코드, client_id, redirect_uri, grant_type
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("code", code);
		params.add("client_id", kakaoClientId);
		params.add("redirect_uri", kakaoRedirectUri);
		params.add("grant_type", "authorization_code");

		return restClient.post()
			.uri("https://kauth.kakao.com/oauth/token")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.body(params)
			.retrieve()
			.body(AccessTokenDto.class);
	}

	private KakaoProfileDto requestProfile(String accessToken) {
		return restClient.get()
			.uri("https://kapi.kakao.com/v2/user/me")
			.header("Authorization", "Bearer " + accessToken)
			.retrieve()
			.body(KakaoProfileDto.class);
	}
}
