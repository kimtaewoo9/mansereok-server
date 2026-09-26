package com.mansereok.server.domain.auth.security;

import static com.mansereok.server.support.fixture.AccessTokenFixture.ISSUER;
import static com.mansereok.server.support.fixture.AccessTokenFixture.NOW;
import static com.mansereok.server.support.fixture.AccessTokenFixture.claimsOfThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedByThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedWithOtherKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import com.mansereok.server.support.fixture.AccessTokenFixture;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 로그인이 필요한 경로에서 인증이 없을 때, 필터가 요청 속성에 남긴 오류 종류대로 응답하는지 검증한다. 진짜 필터를 거친 요청으로
 * Authorization 헤더 모양마다 프론트엔드가 받는 응답 상태와 error 값도 표로 확인한다.
 */
class JwtAuthenticationEntryPointTest {

	private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(JwtErrorCode.class)
	@DisplayName("필터가 남긴 오류 종류마다 그 종류의 상태 코드, error 값, 안내 문구로 답한다")
	void respondsWithValuesOfErrorCode(JwtErrorCode errorCode) throws Exception {
		// given
		MockHttpServletRequest request = requestWithJwtException(
			new JwtAuthenticationException("토큰을 처리하지 못했습니다.", errorCode));
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		entryPoint.commence(request, response, new InsufficientAuthenticationException("로그인 필요"));

		// then
		JsonNode body = readBody(response);
		assertThat(response.getStatus()).isEqualTo(errorCode.getStatus().value());
		assertThat(body.get("status").asInt()).isEqualTo(errorCode.getStatus().value());
		assertThat(body.get("error").asText()).isEqualTo(errorCode.getError());
		assertThat(body.get("message").asText()).isEqualTo("토큰을 처리하지 못했습니다.");
		assertThat(body.get("hint").asText()).isEqualTo(errorCode.getHint());
	}

	@ParameterizedTest(name = "[{index}] {0} → {1} {2}")
	@CsvSource(delimiter = '|', textBlock = """
		# 오류 종류        | 응답 상태 | error 값. 프론트엔드가 읽으므로 바꾸지 않는다 | 안내 문구
		TOKEN_MISSING     | 401 | JWT_TOKEN_MISSING     | Authorization 헤더에 'Bearer {토큰}' 형식으로 JWT 토큰을 포함해주세요.
		TOKEN_EXPIRED     | 401 | JWT_TOKEN_EXPIRED     | 토큰이 만료되었습니다. refresh token을 사용하여 새 토큰을 발급받아주세요.
		TOKEN_MALFORMED   | 400 | JWT_TOKEN_MALFORMED   | 토큰 형식이 올바르지 않습니다. 올바른 JWT 토큰인지 확인해주세요.
		TOKEN_UNSUPPORTED | 400 | JWT_TOKEN_UNSUPPORTED | 지원하지 않는 토큰 형식입니다.
		SIGNATURE_INVALID | 401 | JWT_SIGNATURE_INVALID | 토큰이 변조되었거나 유효하지 않습니다. 새로 로그인해주세요.
		INTERNAL_ERROR    | 500 | JWT_INTERNAL_ERROR    | 서버에서 토큰을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.
		""")
	@DisplayName("오류 종류마다 정해진 응답 상태, error 값, 안내 문구로 답한다")
	void keepsStatusErrorValueAndHint(JwtErrorCode errorCode, int expectedStatus, String expectedError,
		String expectedHint) throws Exception {
		// given
		MockHttpServletRequest request = requestWithJwtException(
			new JwtAuthenticationException("토큰을 처리하지 못했습니다.", errorCode));
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		entryPoint.commence(request, response, new InsufficientAuthenticationException("로그인 필요"));

		// then
		JsonNode body = readBody(response);
		assertThat(response.getStatus()).isEqualTo(expectedStatus);
		assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
		assertThat(body.get("error").asText()).isEqualTo(expectedError);
		assertThat(body.get("hint").asText()).isEqualTo(expectedHint);
	}

	@Nested
	@DisplayName("JwtAuthenticationFilter 를 거친 요청이 로그인이 필요한 경로에 오면")
	class AfterFilter {

		private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(AccessTokenFixture.jwtUtil());

		@AfterEach
		void clearSecurityContext() {
			SecurityContextHolder.clearContext();
		}

		@ParameterizedTest(name = "[{index}] {0} → {1} {2}")
		@MethodSource("com.mansereok.server.domain.auth.security.JwtAuthenticationEntryPointTest#headersWithoutAuthentication")
		@DisplayName("Authorization 헤더 모양마다 정해진 응답 상태와 error 값으로 답한다")
		void respondsByHeader(String description, int expectedStatus, String expectedError,
			String authorizationHeader) throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me/profiles");
			request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
			MockHttpServletResponse response = new MockHttpServletResponse();

			// when
			entryPoint.commence(request, response, new InsufficientAuthenticationException("로그인 필요"));

			// then
			assertThat(response.getStatus()).isEqualTo(expectedStatus);
			assertThat(readBody(response).get("error").asText()).isEqualTo(expectedError);
		}
	}

	static Stream<Arguments> headersWithoutAuthentication() {
		return Stream.of(
			Arguments.of("만료 1초 지난 토큰", 401, "JWT_TOKEN_EXPIRED",
				"Bearer " + signedByThisServer(claimsOfThisServer().expiration(Date.from(NOW.minusSeconds(1))))),
			Arguments.of("다른 키로 서명한 토큰", 401, "JWT_SIGNATURE_INVALID",
				"Bearer " + signedWithOtherKey(claimsOfThisServer())),
			Arguments.of("발급자가 다른 토큰", 401, "JWT_SIGNATURE_INVALID",
				"Bearer " + signedByThisServer(claimsOfThisServer().issuer("staging." + ISSUER))),
			Arguments.of("서명하지 않은 토큰(alg=none)", 400, "JWT_TOKEN_UNSUPPORTED",
				"Bearer " + claimsOfThisServer().compact()),
			Arguments.of("JWT 모양이 아닌 토큰", 400, "JWT_TOKEN_MALFORMED", "Bearer abc"),
			Arguments.of("Bearer 뒤가 빈 헤더", 401, "JWT_TOKEN_MISSING", "Bearer "),
			Arguments.of("Basic 인증 헤더", 400, "JWT_TOKEN_MALFORMED", "Basic bWVtYmVyOnBhc3N3b3Jk"),
			// 필터가 오류를 남기지 않아 기본 응답이 나간다
			Arguments.of("subject 가 없는 토큰", 401, "UNAUTHORIZED",
				"Bearer " + signedByThisServer(claimsOfThisServer().subject(null))));
	}

	@Test
	@DisplayName("필터가 남긴 오류가 없으면 401 UNAUTHORIZED 로 로그인을 안내한다")
	void respondsUnauthorizedWithoutJwtException() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/payments/me");
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		entryPoint.commence(request, response, new InsufficientAuthenticationException("로그인 필요"));

		// then
		JsonNode body = readBody(response);
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(body.get("status").asInt()).isEqualTo(401);
		assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
		assertThat(body.get("message").asText()).isEqualTo("인증이 필요합니다.");
		assertThat(body.get("hint").asText()).isEqualTo("로그인 후 JWT 토큰을 Authorization 헤더에 포함해주세요.");
	}

	private static MockHttpServletRequest requestWithJwtException(JwtAuthenticationException exception) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/payments/me");
		request.setAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE, exception);
		return request;
	}

	private JsonNode readBody(MockHttpServletResponse response) throws Exception {
		return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
}
