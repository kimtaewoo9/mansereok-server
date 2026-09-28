package com.mansereok.server.domain.auth.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.config.RefreshCookieProperties;
import com.mansereok.server.support.SetCookieHeader;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.web.server.Cookie.SameSite;

/**
 * 리프레시 토큰 쿠키의 속성과 수명을 Set-Cookie 헤더 모양 그대로 확인한다. 컨트롤러는 이 헤더를 싣기만 하므로 여기서 정한 속성이 모든
 * 로그인·재발급·로그아웃 응답의 쿠키 속성이다.
 */
class RefreshTokenCookiesTest {

	private static final long SEVEN_DAYS_MILLIS = 604_800_000L;
	private static final long FOURTEEN_DAYS_MILLIS = 1_209_600_000L;

	@Nested
	@DisplayName("새 토큰을 담을 때")
	class WhenIssuing {

		@Test
		@DisplayName("Path=/, HttpOnly, Secure, SameSite=Lax 에 토큰 수명 7일이면 Max-Age=604800 인 쿠키를 만든다")
		void issuesCookieWithAllAttributes() {
			// given
			RefreshTokenCookies cookies = cookiesWith(SEVEN_DAYS_MILLIS, SameSite.LAX);

			// when
			SetCookieHeader cookie = SetCookieHeader.parse(cookies.issue("new-refresh-token").toString());

			// then
			assertThat(cookie.name()).isEqualTo("REFRESH_TOKEN");
			assertThat(cookie.value()).isEqualTo("new-refresh-token");
			assertThat(cookie.attributesWithoutExpires()).containsExactlyInAnyOrderEntriesOf(Map.of(
				"path", "/", "max-age", "604800", "httponly", "", "secure", "", "samesite", "Lax"));
		}

		@Test
		@DisplayName("토큰 수명 설정을 14일로 바꾸면 쿠키의 Max-Age 도 1209600 으로 따라간다")
		void maxAgeFollowsRefreshTokenExpiration() {
			// given
			RefreshTokenCookies cookies = cookiesWith(FOURTEEN_DAYS_MILLIS, SameSite.LAX);

			// when
			SetCookieHeader cookie = SetCookieHeader.parse(cookies.issue("new-refresh-token").toString());

			// then
			assertThat(cookie.attributes()).containsEntry("max-age", "1209600");
		}

		@Test
		@DisplayName("SameSite 설정이 None 이면 SameSite=None 으로 만든다")
		void usesConfiguredSameSite() {
			// given
			RefreshTokenCookies cookies = cookiesWith(SEVEN_DAYS_MILLIS, SameSite.NONE);

			// when
			SetCookieHeader cookie = SetCookieHeader.parse(cookies.issue("new-refresh-token").toString());

			// then
			assertThat(cookie.attributes()).containsEntry("samesite", "None").containsKey("secure");
		}
	}

	@Nested
	@DisplayName("쿠키를 지울 때")
	class WhenExpiring {

		@Test
		@DisplayName("값이 비고 Max-Age=0 이며 나머지 속성은 새 토큰 쿠키와 같은 쿠키를 만든다")
		void expiresCookieWithSameAttributes() {
			// given
			RefreshTokenCookies cookies = cookiesWith(SEVEN_DAYS_MILLIS, SameSite.LAX);

			// when
			SetCookieHeader cookie = SetCookieHeader.parse(cookies.expire().toString());

			// then
			assertThat(cookie.name()).isEqualTo("REFRESH_TOKEN");
			assertThat(cookie.value()).isEmpty();
			assertThat(cookie.attributesWithoutExpires()).containsExactlyInAnyOrderEntriesOf(Map.of(
				"path", "/", "max-age", "0", "httponly", "", "secure", "", "samesite", "Lax"));
		}
	}

	@Nested
	@DisplayName("app.auth.refresh-cookie.same-site 를 읽을 때")
	class WhenBindingSameSite {

		@Test
		@DisplayName("값을 적지 않으면 Lax 다")
		void defaultsToLax() {
			// when
			RefreshCookieProperties properties = bind(Map.of());

			// then
			assertThat(properties.sameSite()).isEqualTo(SameSite.LAX);
		}

		@ParameterizedTest(name = "[{index}] same-site: {0} → {1}")
		@CsvSource(textBlock = """
			None,   NONE
			none,   NONE
			LAX,    LAX
			Strict, STRICT
			""")
		@DisplayName("대소문자를 가리지 않고 읽는다")
		void bindsIgnoringCase(String configured, SameSite expected) {
			// when
			RefreshCookieProperties properties = bind(Map.of("app.auth.refresh-cookie.same-site", configured));

			// then
			assertThat(properties.sameSite()).isEqualTo(expected);
		}

		@Test
		@DisplayName("SameSite 속성을 빼는 omitted 를 적으면 기동 때 바인딩이 실패한다")
		void rejectsOmitted() {
			assertThatThrownBy(() -> bind(Map.of("app.auth.refresh-cookie.same-site", "omitted")))
				.isInstanceOf(BindException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.rootCause()
				.hasMessage("app.auth.refresh-cookie.same-site 는 Strict, Lax, None 중 하나여야 합니다: OMITTED");
		}

		private RefreshCookieProperties bind(Map<String, String> properties) {
			return new Binder(new MapConfigurationPropertySource(properties))
				.bindOrCreate("app.auth.refresh-cookie", RefreshCookieProperties.class);
		}
	}

	private static RefreshTokenCookies cookiesWith(long refreshTokenExpirationMillis, SameSite sameSite) {
		JwtProperties jwtProperties = new JwtProperties("refresh-token-cookies-test-secret-0123456789", 1_800_000L,
			refreshTokenExpirationMillis, "mansereok");
		return new RefreshTokenCookies(jwtProperties, new RefreshCookieProperties(sameSite));
	}
}
