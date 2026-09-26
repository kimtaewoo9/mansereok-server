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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * 로그인이 필요한 경로에서 인증이 없을 때, 필터가 요청 속성에 남긴 오류 종류대로 응답하는지 검증한다.
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
	@CsvSource(textBlock = """
		# 오류 종류,        응답 상태, error 값. error 값은 프론트엔드가 읽으므로 바꾸지 않는다.
		TOKEN_MISSING,     401,       JWT_TOKEN_MISSING
		TOKEN_EXPIRED,     401,       JWT_TOKEN_EXPIRED
		TOKEN_MALFORMED,   400,       JWT_TOKEN_MALFORMED
		TOKEN_UNSUPPORTED, 400,       JWT_TOKEN_UNSUPPORTED
		SIGNATURE_INVALID, 401,       JWT_SIGNATURE_INVALID
		INTERNAL_ERROR,    500,       JWT_INTERNAL_ERROR
		""")
	@DisplayName("오류 종류마다 정해진 응답 상태와 error 값으로 답한다")
	void keepsStatusAndErrorValue(JwtErrorCode errorCode, int expectedStatus, String expectedError)
		throws Exception {
		// given
		MockHttpServletRequest request = requestWithJwtException(
			new JwtAuthenticationException("토큰을 처리하지 못했습니다.", errorCode));
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		entryPoint.commence(request, response, new InsufficientAuthenticationException("로그인 필요"));

		// then
		assertThat(response.getStatus()).isEqualTo(expectedStatus);
		assertThat(readBody(response).get("error").asText()).isEqualTo(expectedError);
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
