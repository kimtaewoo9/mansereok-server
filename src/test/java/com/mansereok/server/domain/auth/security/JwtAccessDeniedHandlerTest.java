package com.mansereok.server.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * 거부된 요청에 403 과 함께 거부 이유(CSRF 인지 권한 부족인지)와 요청한 사람을 본문에 담는 규칙을 확인한다.
 *
 * <p>CSRF 토큰이 없거나 틀리면 error 가 CSRF_FORBIDDEN, 그 밖의 거부는 FORBIDDEN 이다. 본문의 currentUser·currentRole 은 인증
 * 정보의 이름과 첫 권한이고, 인증 정보가 없으면 "익명"·ROLE_NONE 이다.
 */
class JwtAccessDeniedHandlerTest {

	private static final String CSRF_MESSAGE = "CSRF 토큰이 없거나 유효하지 않습니다.";
	private static final String CSRF_HINT =
		"GET /api/auth/csrf-token으로 토큰을 발급받고 X-XSRF-TOKEN 헤더와 쿠키를 함께 전송하세요.";
	private static final String FORBIDDEN_MESSAGE = "접근 권한이 부족합니다.";
	private static final String FORBIDDEN_HINT = "해당 리소스에 접근하려면 더 높은 권한이 필요합니다. 관리자에게 문의하세요.";

	private final JwtAccessDeniedHandler handler = new JwtAccessDeniedHandler();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest(name = "[{index}] {0} → {3}, {4}, {5}")
	@MethodSource("deniedRequests")
	@DisplayName("거부 이유와 인증 여부마다 정해진 error 값과 요청한 사람으로 403 을 답한다")
	void respondsForbidden(String description, AccessDeniedException exception, Authentication authentication,
		String expectedError, String expectedUser, String expectedRole) throws Exception {
		// given
		SecurityContextHolder.getContext().setAuthentication(authentication);
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		handler.handle(new MockHttpServletRequest("PATCH", "/api/v1/users/me/profiles"), response, exception);

		// then
		JsonNode body = readBody(response);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(body.get("status").asInt()).isEqualTo(403);
		assertThat(body.get("error").asText()).isEqualTo(expectedError);
		assertThat(body.get("currentUser").asText()).isEqualTo(expectedUser);
		assertThat(body.get("currentRole").asText()).isEqualTo(expectedRole);
	}

	static Stream<Arguments> deniedRequests() {
		return Stream.of(
			Arguments.of("CSRF 토큰 없음, 로그인함", missingCsrfToken(), member(),
				"CSRF_FORBIDDEN", "member@example.com", "ROLE_USER"),
			Arguments.of("CSRF 토큰 없음, 로그인 안 함", missingCsrfToken(), null,
				"CSRF_FORBIDDEN", "익명", "ROLE_NONE"),
			Arguments.of("CSRF 토큰 틀림, 로그인함", invalidCsrfToken(), member(),
				"CSRF_FORBIDDEN", "member@example.com", "ROLE_USER"),
			Arguments.of("CSRF 토큰 틀림, 로그인 안 함", invalidCsrfToken(), null,
				"CSRF_FORBIDDEN", "익명", "ROLE_NONE"),
			Arguments.of("권한 부족, 로그인함", new AccessDeniedException("denied"), member(),
				"FORBIDDEN", "member@example.com", "ROLE_USER"),
			Arguments.of("권한 부족, 로그인 안 함", new AccessDeniedException("denied"), null,
				"FORBIDDEN", "익명", "ROLE_NONE"),
			Arguments.of("권한 부족, 권한 없이 로그인함", new AccessDeniedException("denied"),
				UsernamePasswordAuthenticationToken.authenticated("member@example.com", null, List.of()),
				"FORBIDDEN", "member@example.com", "ROLE_NONE"));
	}

	@ParameterizedTest(name = "[{index}] {0} → reason {2}")
	@MethodSource("csrfFailures")
	@DisplayName("CSRF 토큰이 없거나 틀리면 거부한 예외 이름과 함께 CSRF 토큰을 받아 보내는 방법을 안내한다")
	void guidesCsrfToken(String description, AccessDeniedException exception, String expectedReason)
		throws Exception {
		// given
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		handler.handle(new MockHttpServletRequest("PATCH", "/api/v1/users/me/profiles"), response, exception);

		// then
		JsonNode body = readBody(response);
		assertThat(body.get("reason").asText()).isEqualTo(expectedReason);
		assertThat(body.get("message").asText()).isEqualTo(CSRF_MESSAGE);
		assertThat(body.get("hint").asText()).isEqualTo(CSRF_HINT);
	}

	static Stream<Arguments> csrfFailures() {
		return Stream.of(
			Arguments.of("CSRF 토큰 없음", missingCsrfToken(), "MissingCsrfTokenException"),
			Arguments.of("CSRF 토큰 틀림", invalidCsrfToken(), "InvalidCsrfTokenException"));
	}

	@Test
	@DisplayName("권한이 부족하면 거부한 예외 이름과 함께 더 높은 권한이 필요하다고 안내한다")
	void guidesMissingAuthority() throws Exception {
		// given
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		handler.handle(new MockHttpServletRequest("GET", "/api/admin/orders"), response,
			new AccessDeniedException("denied"));

		// then
		JsonNode body = readBody(response);
		assertThat(body.get("reason").asText()).isEqualTo("AccessDeniedException");
		assertThat(body.get("message").asText()).isEqualTo(FORBIDDEN_MESSAGE);
		assertThat(body.get("hint").asText()).isEqualTo(FORBIDDEN_HINT);
	}

	@Test
	@DisplayName("거부된 요청의 경로와 메서드를 본문에 담는다")
	void echoesPathAndMethod() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/users/me");
		request.setServletPath("/api/v1/users/me");
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		handler.handle(request, response, new AccessDeniedException("denied"));

		// then
		JsonNode body = readBody(response);
		assertThat(body.get("path").asText()).isEqualTo("/api/v1/users/me");
		assertThat(body.get("method").asText()).isEqualTo("DELETE");
	}

	private static MissingCsrfTokenException missingCsrfToken() {
		return new MissingCsrfTokenException(null);
	}

	private static InvalidCsrfTokenException invalidCsrfToken() {
		return new InvalidCsrfTokenException(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), "actual");
	}

	private static Authentication member() {
		return UsernamePasswordAuthenticationToken.authenticated("member@example.com", null,
			List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	private JsonNode readBody(MockHttpServletResponse response) throws Exception {
		return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
}
