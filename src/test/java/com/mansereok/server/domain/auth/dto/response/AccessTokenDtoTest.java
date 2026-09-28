package com.mansereok.server.domain.auth.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 제공자 토큰 응답의 snake_case 키가 자바 이름으로 읽히는지, toString 에 토큰 값이 나오지 않는지 확인한다.
 *
 * <p>JSON 은 RestClient 가 쓰는 것과 같은 설정의 ObjectMapper 로 읽는다.
 */
class AccessTokenDtoTest {

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("구글 토큰 응답의 snake_case 키를 자바 이름으로 읽고 숫자 expires_in 도 읽는다")
	void readsGoogleTokenResponse() throws Exception {
		// given
		String json = """
			{"access_token": "ya29.access", "expires_in": 3599, "scope": "openid email",
			 "token_type": "Bearer", "id_token": "eyJ.id.token", "refresh_token": "1//refresh"}
			""";

		// when
		AccessTokenDto token = objectMapper.readValue(json, AccessTokenDto.class);

		// then
		assertThat(token).isEqualTo(
			new AccessTokenDto("ya29.access", "3599", "openid email", "Bearer", "eyJ.id.token", null));
	}

	@Test
	@DisplayName("네이버가 잘못된 인가 코드에 주는 200 응답은 액세스 토큰 없이 error 만 읽힌다")
	void readsNaverErrorResponseWithoutToken() throws Exception {
		// given
		String json = """
			{"error": "invalid_request", "error_description": "no valid data in session"}
			""";

		// when
		AccessTokenDto token = objectMapper.readValue(json, AccessTokenDto.class);

		// then
		assertThat(token.accessToken()).isNull();
		assertThat(token.error()).isEqualTo("invalid_request");
	}

	@Test
	@DisplayName("toString 은 액세스 토큰과 ID 토큰 값을 가리고 나머지 값은 보여 준다")
	void toStringHidesTokens() {
		// given
		AccessTokenDto token = new AccessTokenDto("SECRET-ACCESS-TOKEN", "3599", "openid", "Bearer",
			"SECRET-ID-TOKEN", null);

		// when
		String text = token.toString();

		// then
		assertThat(text)
			.doesNotContain("SECRET-ACCESS-TOKEN", "SECRET-ID-TOKEN")
			.isEqualTo("AccessTokenDto[accessToken=(가림), expiresIn=3599, scope=openid, tokenType=Bearer,"
				+ " idToken=(가림), error=null]");
	}

	@Test
	@DisplayName("토큰이 없으면 toString 에 null 로 보여 줘 없는 것과 가린 것을 구별할 수 있다")
	void toStringShowsMissingTokenAsNull() {
		AccessTokenDto token = new AccessTokenDto(null, null, null, null, null, "invalid_request");

		assertThat(token.toString()).isEqualTo("AccessTokenDto[accessToken=null, expiresIn=null, scope=null,"
			+ " tokenType=null, idToken=null, error=invalid_request]");
	}
}
