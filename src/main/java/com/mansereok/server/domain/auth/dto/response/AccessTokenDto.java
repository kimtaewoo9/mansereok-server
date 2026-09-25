package com.mansereok.server.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 소셜 로그인 제공자의 토큰 교환 응답. 제공자가 주는 snake_case 키를 자바 이름으로 읽는다.
 *
 * <p>액세스 토큰과 ID 토큰은 제공자 API 를 사용자 권한으로 부를 수 있는 값이라 toString 에서 값을 가린다. 이 객체가 로그나 예외
 * 메시지에 통째로 들어가도 토큰은 남지 않는다. 제공자의 리프레시 토큰은 쓰지 않아 읽지 않는다.
 *
 * @param accessToken 제공자 API(프로필 조회)를 부를 때 쓰는 토큰
 * @param expiresIn   액세스 토큰 유효 시간(초). 네이버는 문자열로 준다
 * @param scope       허용된 범위
 * @param tokenType   토큰 종류(Bearer)
 * @param idToken     OpenID Connect ID 토큰(구글). 안에 이메일이 들어 있다
 * @param error       실패 코드. 네이버는 잘못된 인가 코드에도 200 과 함께 이 값을 준다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessTokenDto(
	@JsonProperty("access_token") String accessToken,
	@JsonProperty("expires_in") String expiresIn,
	@JsonProperty("scope") String scope,
	@JsonProperty("token_type") String tokenType,
	@JsonProperty("id_token") String idToken,
	@JsonProperty("error") String error
) {

	private static final String HIDDEN = "(가림)";

	/**
	 * 토큰 값은 있는지만 보여 주고 가린다. 나머지 값은 토큰이 아니라 그대로 보여 준다.
	 */
	@Override
	public String toString() {
		return "AccessTokenDto[accessToken=" + hide(accessToken)
			+ ", expiresIn=" + expiresIn
			+ ", scope=" + scope
			+ ", tokenType=" + tokenType
			+ ", idToken=" + hide(idToken)
			+ ", error=" + error + "]";
	}

	private static String hide(String token) {
		return token == null ? "null" : HIDDEN;
	}
}
