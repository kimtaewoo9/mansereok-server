package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * JWT 설정이 기동할 때 약한 비밀키, 잘못된 만료값, 빈 발급자를 막는지와, 설정값을 문자열로 찍어도 비밀키가 드러나지 않는지 검증한다.
 */
class JwtPropertiesTest {

	// 32자. HMAC-SHA256 이 요구하는 최소 길이(32바이트)와 같다.
	private static final String SECRET_32_CHARS = "0123456789abcdef0123456789abcdef";
	private static final long ACCESS_TOKEN_EXPIRATION = 1_800_000L;
	private static final long REFRESH_TOKEN_EXPIRATION = 604_800_000L;
	private static final String ISSUER = "www.namedsaju.com";

	@Nested
	@DisplayName("비밀키가")
	class Secret {

		@ParameterizedTest(name = "[{index}] \"{0}\"")
		@NullSource
		@ValueSource(strings = {"0123456789abcdef0123456789abcde"})
		@DisplayName("없거나 31자면 기동을 막는다")
		void rejectsShortSecret(String secret) {
			assertThatThrownBy(
				() -> new JwtProperties(secret, ACCESS_TOKEN_EXPIRATION, REFRESH_TOKEN_EXPIRATION, ISSUER))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("JWT secret must be at least 32 characters");
		}

		@Test
		@DisplayName("32자면 받아들이고, JwtConfig 가 그 비밀키로 HMAC-SHA256 서명 키를 만든다")
		void acceptsThirtyTwoCharSecret() {
			// given
			JwtProperties jwtProperties = new JwtProperties(SECRET_32_CHARS, ACCESS_TOKEN_EXPIRATION,
				REFRESH_TOKEN_EXPIRATION, ISSUER);

			// when
			SecretKey key = new JwtConfig().jwtSecretKey(jwtProperties);

			// then
			assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
		}
	}

	@Nested
	@DisplayName("만료 시간이")
	class Expiration {

		@ParameterizedTest(name = "[{index}] 액세스 토큰 만료 {0}")
		@NullSource
		@ValueSource(longs = {0L, -1L})
		@DisplayName("액세스 토큰 만료가 없거나 0 이하면 기동을 막는다")
		void rejectsNonPositiveAccessTokenExpiration(Long accessTokenExpiration) {
			assertThatThrownBy(
				() -> new JwtProperties(SECRET_32_CHARS, accessTokenExpiration, REFRESH_TOKEN_EXPIRATION, ISSUER))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Access token expiration must be positive");
		}

		@ParameterizedTest(name = "[{index}] 리프레시 토큰 만료 {0}")
		@NullSource
		@ValueSource(longs = {0L, -1L})
		@DisplayName("리프레시 토큰 만료가 없거나 0 이하면 기동을 막는다")
		void rejectsNonPositiveRefreshTokenExpiration(Long refreshTokenExpiration) {
			assertThatThrownBy(
				() -> new JwtProperties(SECRET_32_CHARS, ACCESS_TOKEN_EXPIRATION, refreshTokenExpiration, ISSUER))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Refresh token expiration must be positive");
		}
	}

	@Nested
	@DisplayName("발급자가")
	class Issuer {

		@ParameterizedTest(name = "[{index}] \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"  "})
		@DisplayName("없거나 공백뿐이면 기동을 막는다")
		void rejectsBlankIssuer(String issuer) {
			assertThatThrownBy(
				() -> new JwtProperties(SECRET_32_CHARS, ACCESS_TOKEN_EXPIRATION, REFRESH_TOKEN_EXPIRATION, issuer))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("JWT issuer cannot be blank");
		}
	}

	@Test
	@DisplayName("문자열로 바꾸면 비밀키 대신 비밀키 길이만 보여 준다")
	void toStringHidesSecret() {
		// given
		JwtProperties jwtProperties = new JwtProperties(SECRET_32_CHARS, ACCESS_TOKEN_EXPIRATION,
			REFRESH_TOKEN_EXPIRATION, ISSUER);

		// when
		String text = jwtProperties.toString();

		// then
		assertThat(text)
			.doesNotContain(SECRET_32_CHARS)
			.isEqualTo("JwtProperties[secret=(32자), accessTokenExpiration=1800000, "
				+ "refreshTokenExpiration=604800000, issuer=www.namedsaju.com]");
	}
}
