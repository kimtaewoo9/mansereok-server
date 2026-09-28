package com.mansereok.server.domain.auth.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 필터가 토큰 검증 결과를 인증 정보나 요청 속성의 오류로 바꾸는지 검증한다. 특히 서명 키는 같지만 발급자가 다르거나 없는 토큰을
 * 인증하지 않고 서명 오류(SIGNATURE_INVALID)로 남기는지 본다.
 */
class JwtAuthenticationFilterTest {

	private static final String SECRET = "jwt-filter-test-secret-0123456789-abcdef";
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));

	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
		jwtUtil("www.namedsaju.com"));

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("이 서버가 발급한 토큰이면")
	class WhenIssuedByThisServer {

		@Test
		@DisplayName("토큰의 사용자와 역할로 인증 정보를 넣고 오류를 남기지 않는다")
		void setsAuthentication() throws Exception {
			// given
			MockHttpServletRequest request = requestWithBearer(
				jwtUtil("www.namedsaju.com").generateAccessToken("member", Map.of("role", "ROLE_USER")));

			// when
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

			// then
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			assertThat(authentication.getName()).isEqualTo("member");
			assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_USER");
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE)).isNull();
		}
	}

	@Nested
	@DisplayName("같은 키로 서명했지만 발급자가 다르거나 없는 토큰이면")
	class WhenIssuerDiffersOrMissing {

		// 발급자가 다른 토큰은 IncorrectClaimException, 발급자가 없는 토큰은 MissingClaimException 으로 파서를 빠져나온다.
		// 필터가 둘 중 하나라도 놓치면 그 토큰은 예상하지 못한 오류(INTERNAL_ERROR, 500)로 떨어진다.
		static Stream<Arguments> tokensNotIssuedByThisServer() {
			return Stream.of(
				Arguments.of("발급자가 다른 토큰(staging.namedsaju.com)",
					jwtUtil("staging.namedsaju.com").generateAccessToken("member", Map.of("role", "ROLE_USER"))),
				Arguments.of("발급자(iss)가 없는 토큰",
					Jwts.builder()
						.subject("member")
						.claim("role", "ROLE_USER")
						.issuedAt(Date.from(Instant.parse("2026-01-15T00:00:00Z")))
						.expiration(Date.from(Instant.parse("2026-01-15T00:30:00Z")))
						.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
						.compact())
			);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("tokensNotIssuedByThisServer")
		@DisplayName("인증 정보를 넣지 않고 SIGNATURE_INVALID 오류를 요청 속성에 남긴다")
		void leavesSignatureInvalid(String tokenDescription, String token) throws Exception {
			// given
			MockHttpServletRequest request = requestWithBearer(token);

			// when
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE))
				.isInstanceOfSatisfying(JwtAuthenticationException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(JwtErrorCode.SIGNATURE_INVALID);
					assertThat(e.getMessage()).isEqualTo("JWT 토큰의 발급자가 올바르지 않습니다.");
				});
		}
	}

	@Nested
	@DisplayName("Authorization 헤더가 Bearer 로 시작하지 않으면")
	class WhenHeaderIsNotBearer {

		@Test
		@DisplayName("인증 정보를 넣지 않고 TOKEN_MALFORMED 오류를 요청 속성에 남긴다")
		void leavesTokenMalformed() throws Exception {
			// given
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/payments/me");
			request.addHeader(HttpHeaders.AUTHORIZATION, "Basic bWVtYmVyOnBhc3N3b3Jk");

			// when
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE))
				.isInstanceOfSatisfying(JwtAuthenticationException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(JwtErrorCode.TOKEN_MALFORMED));
		}
	}

	private static MockHttpServletRequest requestWithBearer(String token) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/payments/me");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		return request;
	}

	/**
	 * 같은 비밀키와 같은 고정 시계를 쓰고 발급자만 다른 JwtUtil 을 만든다.
	 */
	private static JwtUtil jwtUtil(String issuer) {
		JwtProperties jwtProperties = new JwtProperties(SECRET, 1_800_000L, 604_800_000L, issuer);
		return new JwtUtil(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), jwtProperties, FIXED_CLOCK);
	}
}
