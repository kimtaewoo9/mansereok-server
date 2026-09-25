package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.auth.dto.response.oauth.XProfileResponse;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
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

	private final OauthProviderCalls providerCalls = new OauthProviderCalls(SocialType.X);

	/**
	 * 프론트엔드가 받은 인가 코드와 PKCE code_verifier 로 X 로그인을 끝내고 사용자 정보를 돌려준다. 인가 코드를 액세스 토큰으로
	 * 바꾼 뒤 그 토큰으로 프로필을 읽는다. 액세스 토큰은 이 메서드 밖으로 나가지 않는다.
	 *
	 * @throws OauthLoginException               X 가 요청을 거절했거나 토큰·사용자 정보(data)를 주지 않음
	 * @throws OauthProviderUnavailableException X 에 닿지 못했거나 시간 안에 답이 없거나 5xx·408·429
	 */
	public OauthProfile authenticate(String code, String codeVerifier) {
		String accessToken = providerCalls.accessTokenOf(
			providerCalls.send("토큰 교환", () -> requestAccessToken(code, codeVerifier)));
		XProfileResponse profile = providerCalls.send("프로필 조회", () -> requestProfile(accessToken));
		return providerCalls.profileOf(profile, XProfileResponse::toOauthProfile);
	}

	// X 로그인 할떄, PKCE가 반드시 있어야함 .
	private AccessTokenDto requestAccessToken(String code, String codeVerifier) {
		String auth = Base64.getEncoder().encodeToString(
			(xClientId + ":" + xClientSecret).getBytes()
		);

		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("grant_type", "authorization_code");
		params.add("code", code);
		params.add("redirect_uri", xRedirectUri);
		params.add("code_verifier", codeVerifier); // PKCE 필수
		// client_id 랑 client_secret 이랑 헤더에 담아서 전송함 !.

		return restClient.post()
			.uri(TOKEN_URI)
			.header("Content-Type", "application/x-www-form-urlencoded") // 폼데이터 형식임을 알려줘야함 .
			.header("Authorization", "Basic " + auth)
			.body(params)
			.retrieve()
			.body(AccessTokenDto.class);
	}

	private XProfileResponse requestProfile(String accessToken) {
		return restClient.get()
			.uri(X_PROFILE_API_URL)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.retrieve()
			.body(XProfileResponse.class);
	}
}
