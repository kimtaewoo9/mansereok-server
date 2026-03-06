package com.mansereok.server.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

class JwtAccessDeniedHandlerTest {

	private final JwtAccessDeniedHandler handler = new JwtAccessDeniedHandler();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("CSRF 예외는 CSRF_FORBIDDEN으로 응답한다")
	void handle_ShouldReturnCsrfSpecificResponse() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/users/me/profiles");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(request, response, new MissingCsrfTokenException("missing"));

		JsonNode body = readBody(response);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(body.get("error").asText()).isEqualTo("CSRF_FORBIDDEN");
		assertThat(body.get("message").asText()).contains("CSRF");
		assertThat(body.get("hint").asText()).contains("X-XSRF-TOKEN");
	}

	@Test
	@DisplayName("일반 권한 예외는 FORBIDDEN으로 응답한다")
	void handle_ShouldReturnForbiddenForAuthorization() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(
				"tester",
				"pw",
				java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
			)
		);

		MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/users/me");
		MockHttpServletResponse response = new MockHttpServletResponse();
		DefaultCsrfToken csrfToken = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected");

		handler.handle(request, response, new InvalidCsrfTokenException(csrfToken, "actual"));
		JsonNode csrfBody = readBody(response);
		assertThat(csrfBody.get("error").asText()).isEqualTo("CSRF_FORBIDDEN");

		response = new MockHttpServletResponse();
		handler.handle(request, response, new org.springframework.security.access.AccessDeniedException("denied"));
		JsonNode body = readBody(response);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(body.get("error").asText()).isEqualTo("FORBIDDEN");
		assertThat(body.get("currentUser").asText()).isEqualTo("tester");
		assertThat(body.get("currentRole").asText()).isEqualTo("ROLE_USER");
	}

	private JsonNode readBody(MockHttpServletResponse response) throws Exception {
		String text = response.getContentAsString(StandardCharsets.UTF_8);
		return objectMapper.readTree(text);
	}
}
