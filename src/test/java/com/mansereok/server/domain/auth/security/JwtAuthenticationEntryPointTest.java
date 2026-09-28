package com.mansereok.server.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * 로그인이 필요한 경로에서 인증이 없을 때, 필터가 요청 속성에 남긴 오류 종류대로 응답하는지 검증한다.
 */
class JwtAuthenticationEntryPointTest {

	private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@ParameterizedTest(name = "[{index}] {0} → {1} {2}")
	@CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
		# 응답 계약을 값 그대로 적는다. error 값은 프론트엔드가 읽을 수 있으므로 바꾸지 않는다. 오류 종류를 더하면 이 표에도 한 줄을 더한다.
		# 오류 종류       | 상태 | error 값              | 안내 문구(hint)
		TOKEN_MISSING     | 401 | JWT_TOKEN_MISSING     | Authorization 헤더에 'Bearer {토큰}' 형식으로 JWT 토큰을 포함해주세요.
		TOKEN_EXPIRED     | 401 | JWT_TOKEN_EXPIRED     | 토큰이 만료되었습니다. refresh token을 사용하여 새 토큰을 발급받아주세요.
		TOKEN_MALFORMED   | 400 | JWT_TOKEN_MALFORMED   | 토큰 형식이 올바르지 않습니다. 올바른 JWT 토큰인지 확인해주세요.
		TOKEN_UNSUPPORTED | 400 | JWT_TOKEN_UNSUPPORTED | 지원하지 않는 토큰 형식입니다.
		SIGNATURE_INVALID | 401 | JWT_SIGNATURE_INVALID | 토큰이 변조되었거나 유효하지 않습니다. 새로 로그인해주세요.
		INTERNAL_ERROR    | 500 | JWT_INTERNAL_ERROR    | 서버에서 토큰을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.
		""")
	@DisplayName("필터가 남긴 오류 종류마다 정해진 상태 코드, error 값, 안내 문구로 답한다")
	void respondsWithValuesOfErrorCode(JwtErrorCode errorCode, int expectedStatus, String expectedError,
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
		assertThat(body.get("message").asText()).isEqualTo("토큰을 처리하지 못했습니다.");
		assertThat(body.get("hint").asText()).isEqualTo(expectedHint);
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
		assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
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
