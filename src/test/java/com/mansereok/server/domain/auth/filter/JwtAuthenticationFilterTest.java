package com.mansereok.server.domain.auth.filter;

import static com.mansereok.server.support.fixture.AccessTokenFixture.ISSUER;
import static com.mansereok.server.support.fixture.AccessTokenFixture.NOW;
import static com.mansereok.server.support.fixture.AccessTokenFixture.claimsOfThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedByThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedWithOtherKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import com.mansereok.server.support.fixture.AccessTokenFixture;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 필터가 Authorization 헤더를 인증 정보나 요청 속성(JWT_EXCEPTION_ATTRIBUTE)의 오류로 바꾸는 규칙을 표로 확인한다.
 *
 * <p>필터는 어떤 경우에도 응답을 직접 쓰지 않고 요청을 다음 필터로 넘긴다. 로그인이 필요한 경로인지는 SecurityConfig 가 정하고, 그
 * 경로에서 인증이 없으면 JwtAuthenticationEntryPoint 가 이 필터가 남긴 오류로 답한다.
 */
class JwtAuthenticationFilterTest {

	private static final String PROTECTED_PATH = "/api/v1/users/me/profiles";

	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(AccessTokenFixture.jwtUtil());

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("이 서버가 발급했고 아직 만료되지 않은 토큰이면")
	class WhenTokenIsTrusted {

		@ParameterizedTest(name = "[{index}] {0} → principal {1}, 권한 {2}")
		@MethodSource("com.mansereok.server.domain.auth.filter.JwtAuthenticationFilterTest#trustedTokens")
		@DisplayName("토큰의 subject 를 principal 로, role 을 권한으로 넣고 오류를 남기지 않은 채 다음 필터로 넘긴다")
		void setsAuthentication(String description, String expectedPrincipal, String expectedAuthority,
			String token) throws Exception {
			// given
			MockHttpServletRequest request = request(PROTECTED_PATH, "Bearer " + token);
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, new MockHttpServletResponse(), chain);

			// then
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			assertThat(authentication.getPrincipal()).isEqualTo(expectedPrincipal);
			assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly(expectedAuthority);
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE)).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
		}
	}

	static Stream<Arguments> trustedTokens() {
		return Stream.of(
			Arguments.of("회원 토큰", "member", "ROLE_USER",
				signedByThisServer(claimsOfThisServer())),
			Arguments.of("관리자 토큰", "admin", "ROLE_ADMIN",
				signedByThisServer(claimsOfThisServer().subject("admin").claim("role", "ROLE_ADMIN"))),
			Arguments.of("만료 1초 전 토큰", "member", "ROLE_USER",
				signedByThisServer(claimsOfThisServer().expiration(Date.from(NOW.plusSeconds(1))))));
	}

	@Nested
	@DisplayName("헤더나 토큰을 믿을 수 없으면")
	class WhenTokenIsRejected {

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("com.mansereok.server.domain.auth.filter.JwtAuthenticationFilterTest#rejectedHeaders")
		@DisplayName("인증 정보를 넣지 않고 오류 종류와 문구를 요청 속성에 남긴 뒤 다음 필터로 넘긴다")
		void leavesErrorAndContinues(String description, String authorizationHeader, JwtErrorCode expectedCode,
			String expectedMessage) throws Exception {
			// given
			MockHttpServletRequest request = request(PROTECTED_PATH, authorizationHeader);
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, new MockHttpServletResponse(), chain);

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE))
				.isInstanceOfSatisfying(JwtAuthenticationException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(expectedCode);
					assertThat(e.getMessage()).isEqualTo(expectedMessage);
				});
			assertThat(chain.getRequest()).isSameAs(request);
		}
	}

	static Stream<Arguments> rejectedHeaders() {
		return Stream.of(
			Arguments.of("만료 1초 지난 토큰",
				"Bearer " + signedByThisServer(claimsOfThisServer().expiration(Date.from(NOW.minusSeconds(1)))),
				JwtErrorCode.TOKEN_EXPIRED, "JWT 토큰이 만료되었습니다."),
			Arguments.of("다른 키로 서명한 토큰",
				"Bearer " + signedWithOtherKey(claimsOfThisServer()),
				JwtErrorCode.SIGNATURE_INVALID, "JWT 토큰의 서명이 유효하지 않습니다."),
			Arguments.of("같은 키로 서명했지만 발급자가 다른 토큰",
				"Bearer " + signedByThisServer(claimsOfThisServer().issuer("staging." + ISSUER)),
				JwtErrorCode.SIGNATURE_INVALID, "JWT 토큰의 발급자가 올바르지 않습니다."),
			Arguments.of("같은 키로 서명했지만 발급자가 없는 토큰",
				"Bearer " + signedByThisServer(claimsOfThisServer().issuer(null)),
				JwtErrorCode.SIGNATURE_INVALID, "JWT 토큰의 발급자가 올바르지 않습니다."),
			Arguments.of("서명하지 않은 토큰(alg=none)",
				"Bearer " + claimsOfThisServer().compact(),
				JwtErrorCode.TOKEN_UNSUPPORTED, "지원하지 않는 JWT 토큰입니다."),
			Arguments.of("role 이 없는 토큰",
				"Bearer " + signedByThisServer(claimsOfThisServer().claim("role", null)),
				JwtErrorCode.TOKEN_MALFORMED, "JWT 토큰이 비어있거나 올바르지 않습니다."),
			Arguments.of("JWT 모양이 아닌 토큰",
				"Bearer abc",
				JwtErrorCode.TOKEN_MALFORMED, "JWT 토큰 형식이 올바르지 않습니다."),
			Arguments.of("Bearer 뒤가 빈 헤더",
				"Bearer ",
				JwtErrorCode.TOKEN_MISSING, "Bearer Token이 비어있습니다."),
			Arguments.of("Bearer 뒤가 공백뿐인 헤더",
				"Bearer    ",
				JwtErrorCode.TOKEN_MISSING, "Bearer Token이 비어있습니다."),
			Arguments.of("Basic 인증 헤더",
				"Basic bWVtYmVyOnBhc3N3b3Jk",
				JwtErrorCode.TOKEN_MALFORMED, "Authorization Header 는 'Bearer '로 시작해야 합니다."),
			Arguments.of("소문자 bearer 헤더",
				"bearer " + signedByThisServer(claimsOfThisServer()),
				JwtErrorCode.TOKEN_MALFORMED, "Authorization Header 는 'Bearer '로 시작해야 합니다."));
	}

	@Nested
	@DisplayName("인증할 사용자가 없는 요청이면")
	class WhenNoUserToAuthenticate {

		@Test
		@DisplayName("Authorization 헤더가 없으면 인증 정보도 오류도 남기지 않고 다음 필터로 넘긴다")
		void passesWithoutHeader() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, new MockHttpServletResponse(), chain);

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE)).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("서명과 발급자가 맞아도 subject 가 없는 토큰이면 인증 정보도 오류도 남기지 않고 다음 필터로 넘긴다")
		void passesTokenWithoutSubject() throws Exception {
			// given
			MockHttpServletRequest request = request(PROTECTED_PATH,
				"Bearer " + signedByThisServer(claimsOfThisServer().subject(null)));
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, new MockHttpServletResponse(), chain);

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE)).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
		}
	}

	@Nested
	@DisplayName("CORS 사전 요청과 actuator 경로는")
	class WhenRequestIsSkipped {

		@ParameterizedTest(name = "[{index}] {0} {1}")
		@CsvSource(textBlock = """
			OPTIONS, /api/v1/users/me/profiles
			GET,     /actuator/health
			GET,     /actuator/prometheus
			""")
		@DisplayName("잘못된 토큰이 실려 와도 토큰을 읽지 않아 오류를 남기지 않고 다음 필터로 넘긴다")
		void skipsTokenCheck(String method, String path) throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest(method, path);
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, new MockHttpServletResponse(), chain);

			// then
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE)).isNull();
			assertThat(chain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("actuator 경로에 유효한 토큰이 실려 와도 인증 정보를 넣지 않는다")
		void doesNotAuthenticateActuator() throws Exception {
			// given
			MockHttpServletRequest request = request("/actuator/prometheus",
				"Bearer " + signedByThisServer(claimsOfThisServer()));

			// when
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		}
	}

	@Nested
	@DisplayName("로그인 없이 여는 경로에 잘못된 토큰이 실려 오면")
	class WhenPublicPathHasBadToken {

		@Test
		@DisplayName("응답을 쓰지 않고 다음 필터로 넘겨, 경로를 열지 막을지는 뒤의 보안 규칙이 정하게 한다")
		void leavesResponseUntouched() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(chain.getRequest()).isSameAs(request);
			assertThat(response.isCommitted()).isFalse();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentAsString()).isEmpty();
		}
	}

	private static MockHttpServletRequest request(String path, String authorizationHeader) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
		return request;
	}
}
