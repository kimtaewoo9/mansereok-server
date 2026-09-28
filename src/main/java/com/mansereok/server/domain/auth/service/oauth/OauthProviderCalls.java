package com.mansereok.server.domain.auth.service.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.auth.dto.response.AccessTokenDto;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * 소셜 로그인 제공자 하나에 보내는 호출의 결과를 로그인에 쓸 값이나 로그인 예외로 바꾼다. 네 제공자 서비스가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>제공자가 요청을 거절함(408·429 를 뺀 4xx), 토큰 응답에 액세스 토큰이 없음, 프로필이 비었거나 사용자 번호가 없음:
 *   {@link OauthLoginException}(401). 인가 코드 재사용·만료처럼 다시 로그인하면 되는 실패다.</li>
 *   <li>제공자가 5xx·408(요청 시간 초과)·429(호출 한도 초과)로 답함, 연결하지 못했거나 응답 헤더가 시간 안에 오지 않음
 *   ({@link ResourceAccessException}), 헤더는 왔지만 본문을 받다가 시간 제한에 걸리거나 끊김(원인이 IOException 인
 *   RestClientException): {@link OauthProviderUnavailableException}(503). 408·429 는 사용자 잘못이 아니라 제공자가 잠시 받지
 *   않는 것이라 장애로 보고 ERROR 로그로 알린다.</li>
 *   <li>응답을 다 받았지만 읽지 못함(그 밖의 RestClientException, 원인은 HttpMessageNotReadableException 등): 바꾸지 않고 그대로
 *   던진다. 우리 쪽 DTO 가 제공자 응답과 어긋난 서버 문제일 수 있어 RequestErrorExceptionHandler 가 500 으로 답하고 스택 트레이스를
 *   남긴다.</li>
 * </ul>
 *
 * <p>예외의 이유(reason)에는 단계, HTTP 상태, 제공자의 오류 코드(OAuth 표준 error 와 카카오 error_code), 원인 예외 이름만 넣는다.
 * 설정 오류(redirect_uri_mismatch, invalid_client)와 인가 코드 재사용(invalid_grant)을 로그로 가려내기 위해서다. 제공자 응답
 * 본문과 error_description 에는 인가 코드가 되돌아오기도 해서 넣지 않는다.
 */
@Slf4j
final class OauthProviderCalls {

	// 로그에 남길 오류 코드의 모양. invalid_grant, KOE320 처럼 짧은 코드만 남기고, 문장이나 인가 코드가 섞인 값은 버린다.
	private static final Pattern ERROR_CODE_FORMAT = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

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
			if (isTemporaryRefusal(e.getStatusCode())) {
				throw new OauthProviderUnavailableException(provider,
					step + " 요청을 제공자가 지금은 받지 않음(" + describe(e) + ")", e);
			}
			throw new OauthLoginException(provider, step + " 요청을 제공자가 거절함(" + describe(e) + ")", e);
		} catch (HttpServerErrorException e) {
			throw new OauthProviderUnavailableException(provider,
				step + " 요청에 제공자가 오류로 답함(HTTP " + e.getStatusCode().value() + ")", e);
		} catch (ResourceAccessException e) {
			throw new OauthProviderUnavailableException(provider,
				step + " 요청을 보내지 못했거나 시간 안에 답이 없음("
					+ e.getMostSpecificCause().getClass().getSimpleName() + ")", e);
		} catch (RestClientException e) {
			// 본문을 JSON 으로 읽지 못한 경우는 원인이 HttpMessageNotReadableException 이라(IOException 이 아니다) 여기서 걸러진다.
			if (e.getCause() instanceof IOException cause) {
				throw new OauthProviderUnavailableException(provider,
					step + " 응답 본문을 다 받기 전에 끊기거나 시간 안에 오지 않음(" + cause.getClass().getSimpleName() + ")",
					e);
			}
			throw e;
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
	 * 제공자 프로필 응답을 OauthProfile 로 바꾸고, DEBUG 로 제공자와 사용자 번호만 남긴다. 응답이 비었거나 사용자 번호가 없으면
	 * 누구인지 알 수 없어 로그인 실패로 끝낸다.
	 *
	 * @param profileResponse 제공자 프로필 응답. 없으면 null
	 * @param toOauthProfile  제공자 응답을 OauthProfile 로 바꾸는 방법. 필요한 값이 없으면 IllegalArgumentException 을 던진다
	 */
	<P> OauthProfile profileOf(P profileResponse, Function<P, OauthProfile> toOauthProfile) {
		if (profileResponse == null) {
			throw new OauthLoginException(provider, "프로필 응답 본문이 비어 있음");
		}
		OauthProfile oauthProfile;
		try {
			oauthProfile = toOauthProfile.apply(profileResponse);
		} catch (IllegalArgumentException e) {
			throw new OauthLoginException(provider, "프로필 응답에 사용자 정보가 없음", e);
		}
		log.debug("소셜 로그인 제공자 인증 완료: provider={}, socialId={}", provider, oauthProfile.socialId());
		return oauthProfile;
	}

	private static boolean isTemporaryRefusal(HttpStatusCode status) {
		return status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT) || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
	}

	/**
	 * "HTTP 400, error=invalid_grant, error_code=KOE320" 처럼 상태와 오류 코드만 적는다. 본문을 읽지 못했거나 코드가 없으면 상태만
	 * 적는다.
	 */
	private static String describe(HttpClientErrorException e) {
		StringBuilder description = new StringBuilder("HTTP ").append(e.getStatusCode().value());
		ProviderErrorBody body = readErrorBody(e);
		if (body != null) {
			appendErrorCode(description, "error", body.error());
			appendErrorCode(description, "error_code", body.errorCode());
		}
		return description.toString();
	}

	private static ProviderErrorBody readErrorBody(HttpClientErrorException e) {
		try {
			return e.getResponseBodyAs(ProviderErrorBody.class);
		} catch (RuntimeException ignored) {
			// JSON 이 아닌 본문(HTML 오류 페이지 등)이다. 이유에는 HTTP 상태만 남긴다.
			return null;
		}
	}

	private static void appendErrorCode(StringBuilder description, String name, String value) {
		if (value != null && ERROR_CODE_FORMAT.matcher(value).matches()) {
			description.append(", ").append(name).append('=').append(value);
		}
	}

	/**
	 * 제공자 오류 응답에서 로그에 남겨도 되는 값만 읽는다. error_description 과 나머지 필드는 읽지 않는다.
	 *
	 * @param error     OAuth 표준 오류 코드(invalid_grant, redirect_uri_mismatch 등)
	 * @param errorCode 카카오 오류 코드(KOE320 등)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record ProviderErrorBody(
		@JsonProperty("error") String error,
		@JsonProperty("error_code") String errorCode
	) {
	}
}
