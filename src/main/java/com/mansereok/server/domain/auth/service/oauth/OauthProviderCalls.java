package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 소셜 로그인 제공자 하나에 보내는 호출의 결과를 로그인에 쓸 값이나 로그인 예외로 바꾼다. 네 제공자 서비스가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>제공자가 요청을 거절함(4xx), 토큰 응답에 액세스 토큰이 없음, 프로필이 비었거나 사용자 번호가 없음:
 *   {@link OauthLoginException}(401). 인가 코드 재사용·만료처럼 다시 로그인하면 되는 실패다.</li>
 *   <li>제공자가 5xx 로 답함, 연결하지 못했거나 연결·응답 시간 안에 끝나지 않음({@link ResourceAccessException}):
 *   {@link OauthProviderUnavailableException}(503).</li>
 *   <li>응답을 받았지만 읽지 못함(그 밖의 RestClientException): 바꾸지 않고 그대로 던진다. 우리 쪽 DTO 가 제공자 응답과 어긋난
 *   서버 문제일 수 있어 RequestErrorExceptionHandler 가 500 으로 답하고 스택 트레이스를 남긴다.</li>
 * </ul>
 *
 * <p>예외의 이유(reason)에는 단계, HTTP 상태, 원인 예외 이름만 넣는다. 제공자 응답 본문에는 인가 코드가 되돌아오기도 해서 넣지 않는다.
 */
final class OauthProviderCalls {

	private final SocialType provider;

	OauthProviderCalls(SocialType provider) {
		this.provider = provider;
	}

	/**
	 * 제공자에 요청 하나를 보내고 응답 본문을 돌려준다. 실패는 위 규칙대로 로그인 예외로 바꾼다.
	 *
	 * @param step    로그에 남길 단계 이름(토큰 교환, 프로필 조회)
	 * @param request 제공자에 보내는 요청
	 */
	<T> T send(String step, Supplier<T> request) {
		try {
			return request.get();
		} catch (HttpClientErrorException e) {
			throw new OauthLoginException(provider,
				step + " 요청을 제공자가 거절함(HTTP " + e.getStatusCode().value() + ")", e);
		} catch (HttpServerErrorException e) {
			throw new OauthProviderUnavailableException(provider,
				step + " 요청에 제공자가 오류로 답함(HTTP " + e.getStatusCode().value() + ")", e);
		} catch (ResourceAccessException e) {
			throw new OauthProviderUnavailableException(provider,
				step + " 요청을 보내지 못했거나 시간 안에 답이 없음("
					+ e.getMostSpecificCause().getClass().getSimpleName() + ")", e);
		}
	}

	/**
	 * 토큰 응답에서 액세스 토큰을 꺼낸다. 없으면 프로필을 부르지 않고 로그인 실패로 끝낸다. 네이버는 잘못된 인가 코드에도 200 과
	 * error 만 담은 본문을 주므로 HTTP 상태만으로는 실패를 알 수 없다.
	 */
	String accessTokenOf(AccessTokenDto tokenResponse) {
		if (tokenResponse == null) {
			throw new OauthLoginException(provider, "토큰 응답 본문이 비어 있음");
		}
		if (!StringUtils.hasText(tokenResponse.accessToken())) {
			throw new OauthLoginException(provider,
				"토큰 응답에 access_token 이 없음(error=" + tokenResponse.error() + ")");
		}
		return tokenResponse.accessToken();
	}

	/**
	 * 제공자 프로필 응답을 OauthProfile 로 바꾼다. 응답이 비었거나 사용자 번호가 없으면 누구인지 알 수 없어 로그인 실패로 끝낸다.
	 *
	 * @param profileResponse 제공자 프로필 응답. 없으면 null
	 * @param toOauthProfile  제공자 응답을 OauthProfile 로 바꾸는 방법. 필요한 값이 없으면 IllegalArgumentException 을 던진다
	 */
	<P> OauthProfile profileOf(P profileResponse, Function<P, OauthProfile> toOauthProfile) {
		if (profileResponse == null) {
			throw new OauthLoginException(provider, "프로필 응답 본문이 비어 있음");
		}
		try {
			return toOauthProfile.apply(profileResponse);
		} catch (IllegalArgumentException e) {
			throw new OauthLoginException(provider, "프로필 응답에 사용자 정보가 없음", e);
		}
	}
}
