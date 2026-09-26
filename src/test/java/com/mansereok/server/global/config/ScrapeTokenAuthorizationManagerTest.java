package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * 지표 수집 경로는 "Bearer {수집 토큰}" 을 실은 요청만 통과하고, 수집 토큰 설정이 비어 있으면 어떤 요청도 통과하지 못하는지 확인한다.
 */
class ScrapeTokenAuthorizationManagerTest {

	private static final String SCRAPE_TOKEN = "scrape-token-0123456789";

	@Nested
	@DisplayName("수집 토큰이 설정돼 있으면")
	class WhenScrapeTokenIsSet {

		private final ScrapeTokenAuthorizationManager manager = new ScrapeTokenAuthorizationManager(
			new ScrapeTokenProperties(SCRAPE_TOKEN));

		@ParameterizedTest(name = "[{index}] Authorization: {0} → 통과 {1}")
		@DisplayName("Authorization 헤더가 Bearer 와 같은 토큰일 때만 통과시킨다")
		@CsvSource(textBlock = """
			# Authorization 헤더 값(비우면 헤더 없음),     통과 여부
			'Bearer scrape-token-0123456789',              true
			# 토큰이 한 글자 모자라거나 넘치거나 다르면 막는다.
			'Bearer scrape-token-012345678',               false
			'Bearer scrape-token-01234567890',             false
			'Bearer scrape-token-0123456780',              false
			# Bearer 가 아닌 방식이거나 방식 이름이 없으면 같은 토큰이라도 막는다.
			'Basic scrape-token-0123456789',               false
			'scrape-token-0123456789',                     false
			'Bearer ',                                     false
			,                                              false
			""")
		void allowsOnlyMatchingBearerToken(String authorization, boolean expected) {
			// given
			MockHttpServletRequest request = scrapeRequest(authorization);

			// when
			AuthorizationResult result = manager.authorize(() -> null, new RequestAuthorizationContext(request));

			// then
			assertThat(result.isGranted()).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("수집 토큰 설정이 비어 있으면")
	class WhenScrapeTokenIsEmpty {

		@ParameterizedTest(name = "[{index}] 설정값 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"  ", "\n"})
		@DisplayName("빈 Bearer 토큰을 보내도 통과시키지 않는다")
		void deniesEmptyBearerToken(String configuredToken) {
			// given
			ScrapeTokenAuthorizationManager manager = new ScrapeTokenAuthorizationManager(
				new ScrapeTokenProperties(configuredToken));

			// when
			AuthorizationResult result = manager.authorize(() -> null,
				new RequestAuthorizationContext(scrapeRequest("Bearer ")));

			// then
			assertThat(result.isGranted()).isFalse();
		}
	}

	@Nested
	@DisplayName("수집 토큰 설정값은")
	class ScrapeTokenPropertiesValue {

		@Test
		@DisplayName("끝에 줄바꿈이 붙어 있어도 떼고 비교해 Prometheus 가 보내는 토큰과 맞춘다")
		void stripsTrailingNewline() {
			// given
			ScrapeTokenAuthorizationManager manager = new ScrapeTokenAuthorizationManager(
				new ScrapeTokenProperties(SCRAPE_TOKEN + "\n"));

			// when
			AuthorizationResult result = manager.authorize(() -> null,
				new RequestAuthorizationContext(scrapeRequest("Bearer " + SCRAPE_TOKEN)));

			// then
			assertThat(result.isGranted()).isTrue();
		}

		@Test
		@DisplayName("toString 에 토큰을 드러내지 않는다")
		void toStringHidesToken() {
			// when
			String text = new ScrapeTokenProperties(SCRAPE_TOKEN).toString();

			// then
			assertThat(text).doesNotContain(SCRAPE_TOKEN).contains("(설정됨)");
		}
	}

	private static MockHttpServletRequest scrapeRequest(String authorization) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
		if (authorization != null) {
			request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request;
	}
}
