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
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * 거부된 요청에 403 과 함께 거부 이유(CSRF 인지 권한 부족인지)와 SecurityContext 의 인증 정보를 본문에 담는 규칙을 확인한다.
 *
 * <p>CSRF 토큰이 없거나 틀리면 error 가 CSRF_FORBIDDEN, 그 밖의 거부는 FORBIDDEN 이다. 본문의 currentUser·currentRole 은 인증
 * 정보의 이름과 첫 권한이고, 인증 정보가 없으면 "익명"·ROLE_NONE 이다. 본문에 currentUser 를 담는 것은 요구사항으로 정한 것이
 * 아니라 지금 동작이고, 이 테스트는 그 동작을 그대로 고정한다. 담지 않기로 정하면 표의 currentUser 열도 함께 고친다.
 *
 * <p>여기서는 처리기 하나만 부른다. 운영 보안 필터 체인에서는 다음과 같이 조합이 좁아진다(SecurityRulesTest 가 확인한다).
 * <ul>
 *   <li>CSRF 검사가 JwtAuthenticationFilter 보다 먼저 돌아서, CSRF 실패는 유효한 토큰을 실어 와도 늘 "익명"·ROLE_NONE 이다.</li>
 *   <li>로그인하지 않은 요청의 권한 거부는 이 처리기가 아니라 진입점(JwtAuthenticationEntryPoint)으로 가서 401 이 된다.</li>
 *   <li>로그인한 회원의 권한 거부는 AuthorizationFilter 가 던진 AuthorizationDeniedException 이라 reason 이 그 이름이다.</li>
 * </ul>
 * 그래서 표의 "CSRF 토큰 없음·틀림, 로그인함" 과 "권한 부족, 로그인 안 함" 줄은 처리기를 따로 부를 때만 생기는 조합이다.
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
	@DisplayName("거부 이유와 SecurityContext 의 인증 정보마다 정해진 error 값과 currentUser·currentRole 로 403 을 답한다")
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

	// "CSRF 토큰 없음·틀림, 로그인함" 과 "권한 부족, 로그인 안 함" 은 운영 체인에서는 생기지 않는 조합이다(클래스 설명 참고).
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
			Arguments.of("권한 부족, 로그인함", authorizationDenied(), member(),
				"FORBIDDEN", "member@example.com", "ROLE_USER"),
			Arguments.of("권한 부족, 로그인 안 함", authorizationDenied(), null,
				"FORBIDDEN", "익명", "ROLE_NONE"),
			Arguments.of("권한 부족, 권한 없이 로그인함", authorizationDenied(),
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
			authorizationDenied());

		// then
		JsonNode body = readBody(response);
		assertThat(body.get("reason").asText()).isEqualTo("AuthorizationDeniedException");
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
		handler.handle(request, response, authorizationDenied());

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

	/**
	 * 운영 체인에서 로그인한 회원이 권한 없는 경로를 부를 때 AuthorizationFilter 가 던지는 예외.
	 */
	private static AccessDeniedException authorizationDenied() {
		return new AuthorizationDeniedException("Access Denied");
	}

	private static Authentication member() {
		return UsernamePasswordAuthenticationToken.authenticated("member@example.com", null,
			List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	private JsonNode readBody(MockHttpServletResponse response) throws Exception {
		return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
}
