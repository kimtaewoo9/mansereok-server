package com.mansereok.server.domain.auth.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 리프레시 토큰 쿠키로 동작하는 요청(재발급, 로그아웃, 소셜 로그인)을 허용한 출처에서 온 것만 받는지 확인한다.
 *
 * <p>막힌 요청은 다음 필터로 넘어가지 않고 403 CORS_ORIGIN_FORBIDDEN 으로 끝난다. 넘어간 요청은 MockFilterChain 이 받은 요청으로
 * 확인한다.
 */
class AllowedOriginFilterTest {

	private final AllowedOriginFilter filter = new AllowedOriginFilter(
		List.of("https://namedsaju.com", "https://www.namedsaju.com"));
	private final ObjectMapper objectMapper = new ObjectMapper();

	@ParameterizedTest(name = "[{index}] {0} {1}, Origin {2} → 403")
	@CsvSource(textBlock = """
		# 메서드, 경로,                  Origin 헤더
		POST,     /api/auth/refresh,     https://evil.vercel.app
		POST,     /api/auth/refresh,     https://manselab-front-git-main.vercel.app
		POST,     /api/auth/refresh,     https://www.namedsaju.com.evil.com
		POST,     /api/auth/refresh,     http://www.namedsaju.com
		POST,     /api/auth/refresh,     null
		POST,     /api/auth/sign-out,    https://evil.vercel.app
		POST,     /member/google/doLogin, https://evil.vercel.app
		POST,     /member/kakao/doLogin, https://evil.vercel.app
		POST,     /member/X/doLogin,     https://evil.vercel.app
		""")
	@DisplayName("쿠키로 동작하는 요청이 허용하지 않은 출처에서 오면 다음 필터로 넘기지 않고 403 CORS_ORIGIN_FORBIDDEN 으로 끝낸다")
	void blocksUnknownOrigin(String method, String path, String origin) throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.addHeader("Origin", origin);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		// when
		filter.doFilter(request, response, chain);

		// then
		assertThat(chain.getRequest()).as("다음 필터로 넘긴 요청").isNull();
		assertThat(response.getStatus()).isEqualTo(403);
		JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(body.get("error").asText()).isEqualTo("CORS_ORIGIN_FORBIDDEN");
		assertThat(body.get("path").asText()).isEqualTo(path);
	}

	@ParameterizedTest(name = "[{index}] {0} {1}, Origin {2} → 통과")
	@CsvSource(textBlock = """
		# 메서드, 경로,                  Origin 헤더
		# 허용한 출처에서 온 쿠키 요청
		POST,     /api/auth/refresh,     https://www.namedsaju.com
		POST,     /api/auth/refresh,     https://namedsaju.com
		POST,     /api/auth/sign-out,    https://www.namedsaju.com
		POST,     /member/kakao/doLogin, https://www.namedsaju.com
		# 이 필터가 보지 않는 요청. 쿠키로 동작하지 않는 쓰기는 CSRF 토큰이, 사전 요청과 그 밖의 출처 판정은 CORS 설정이 맡는다
		POST,     /api/v1/reviews,       https://evil.vercel.app
		POST,     /api/auth/sign-in,     https://evil.vercel.app
		GET,      /api/auth/csrf-token,  https://evil.vercel.app
		OPTIONS,  /api/auth/refresh,     https://evil.vercel.app
		""")
	@DisplayName("허용한 출처의 요청과 이 필터가 맡지 않는 요청은 다음 필터로 넘긴다")
	void passesAllowedOriginAndOtherRequests(String method, String path, String origin)
		throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.addHeader("Origin", origin);
		MockFilterChain chain = new MockFilterChain();

		// when
		filter.doFilter(request, new MockHttpServletResponse(), chain);

		// then
		assertThat(chain.getRequest()).as("다음 필터로 넘긴 요청").isSameAs(request);
	}

	@Test
	@DisplayName("Origin 헤더가 없는 재발급 요청은 같은 출처나 브라우저 밖에서 온 것이므로 다음 필터로 넘긴다")
	void passesRequestWithoutOrigin() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
		MockFilterChain chain = new MockFilterChain();

		// when
		filter.doFilter(request, new MockHttpServletResponse(), chain);

		// then
		assertThat(chain.getRequest()).as("다음 필터로 넘긴 요청").isSameAs(request);
	}
}
