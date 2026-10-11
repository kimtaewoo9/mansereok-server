package com.mansereok.server.domain.auth.filter;

import static com.mansereok.server.support.fixture.AccessTokenFixture.ISSUER;
import static com.mansereok.server.support.fixture.AccessTokenFixture.MEMBER_ID;
import static com.mansereok.server.support.fixture.AccessTokenFixture.NOW;
import static com.mansereok.server.support.fixture.AccessTokenFixture.claimsOfThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedByThisServer;
import static com.mansereok.server.support.fixture.AccessTokenFixture.signedWithOtherKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import com.mansereok.server.support.fixture.AccessTokenFixture;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>필터는 어떤 경우에도 응답을 직접 쓰지 않고 요청을 다음 필터로 넘긴다. 예상하지 못한 예외(INTERNAL_ERROR)도 오류만 남기고
 * 넘긴다. 필터는 어느 경로가 로그인 없이 열리는지 모른다. 로그인이 필요한 경로인지는 SecurityConfig 가 정하고(SecurityRulesTest),
 * 그 경로에서 인증이 없으면 JwtAuthenticationEntryPoint 가 이 필터가 남긴 오류로 답한다.
 */
class JwtAuthenticationFilterTest {

	private static final String PROTECTED_PATH = "/api/v1/users/me/profiles";

	private final UserRepository userRepository = mock(UserRepository.class);
	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(AccessTokenFixture.jwtUtil(),
		userRepository);

	@BeforeEach
	void memberAndAdminAccountsExist() {
		// 표의 토큰이 가리키는 (userId, subject) 조합. 그 밖의 조합은 목이 false 를 돌려줘 "계정 없음" 이 된다.
		given(userRepository.existsByIdAndUsername(MEMBER_ID, "member")).willReturn(true);
		given(userRepository.existsByIdAndUsername(MEMBER_ID, "admin")).willReturn(true);
	}

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
			assertThat(authentication.isAuthenticated()).isTrue();
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
				signedByThisServer(claimsOfThisServer().expiration(Date.from(NOW.plusSeconds(1))))),
			// 요구사항이 아니라 지금 동작을 적은 줄이다. jjwt 는 지금 시각이 만료 시각보다 뒤일 때만 만료로 보므로 만료 시각과 같은
			// 순간의 토큰은 통과한다. RFC 7519 는 이 순간부터 거부하라고 하므로, 파서 설정을 그렇게 바꾸면 이 줄도 함께 바꾼다.
			Arguments.of("만료 시각과 같은 순간의 토큰", "member", "ROLE_USER",
				signedByThisServer(claimsOfThisServer().expiration(Date.from(NOW)))));
	}

	@Nested
	@DisplayName("헤더나 토큰을 믿을 수 없으면")
	class WhenTokenIsRejected {

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("com.mansereok.server.domain.auth.filter.JwtAuthenticationFilterTest#rejectedHeaders")
		@DisplayName("인증 정보를 넣지 않고 응답도 쓰지 않은 채, 오류 종류와 문구를 요청 속성에 남기고 다음 필터로 넘긴다")
		void leavesErrorAndContinues(String description, String authorizationHeader, JwtErrorCode expectedCode,
			String expectedMessage) throws Exception {
			// given
			MockHttpServletRequest request = request(PROTECTED_PATH, authorizationHeader);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE))
				.isInstanceOfSatisfying(JwtAuthenticationException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(expectedCode);
					assertThat(e.getMessage()).isEqualTo(expectedMessage);
				});
			assertThat(chain.getRequest()).isSameAs(request);
			assertThat(response.isCommitted()).isFalse();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentAsString()).isEmpty();
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
			// 발급자가 다른 토큰은 IncorrectClaimException, 발급자가 없는 토큰은 MissingClaimException 으로 파서를 빠져나온다.
			// 필터가 둘 중 하나라도 놓치면 그 토큰은 예상하지 못한 오류(INTERNAL_ERROR, 500)로 떨어진다.
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
			// 요구사항이 아니라 지금 동작을 적은 줄이다. RFC 7235 는 인증 방식 이름의 대소문자를 가리지 않으므로, 표준대로
			// 'bearer' 도 받게 고치면 이 줄은 회귀가 아니라 바뀐 동작에 맞춰 고칠 대상이다.
			Arguments.of("소문자 bearer 헤더",
				"bearer " + signedByThisServer(claimsOfThisServer()),
				JwtErrorCode.TOKEN_MALFORMED, "Authorization Header 는 'Bearer '로 시작해야 합니다."),
			// 필터가 종류별로 나눠 잡지 않은 예외는 모두 이 줄처럼 INTERNAL_ERROR 로 남는다. 이 서버 키로 서명했어도 role 이 문자열이
			// 아니면 클레임을 읽는 곳에서 jjwt 의 RequiredTypeException 이 난다.
			Arguments.of("role 이 숫자인 토큰",
				"Bearer " + signedByThisServer(claimsOfThisServer().claim("role", 1)),
				JwtErrorCode.INTERNAL_ERROR, "JWT 처리 중 내부 오류가 발생했습니다."));
	}

	@Nested
	@DisplayName("서명·발급자·만료는 맞지만 토큰이 가리키는 계정이 지금 없으면")
	class WhenAccountIsGone {

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.auth.filter.JwtAuthenticationFilterTest#tokensOfMissingAccounts")
		@DisplayName("인증 정보를 넣지 않고 ACCOUNT_MISMATCH 오류를 요청 속성에 남긴 채 다음 필터로 넘긴다")
		void leavesAccountMismatchAndContinues(String description, String token, Long lookedUpId,
			String lookedUpUsername) throws Exception {
			// given
			MockHttpServletRequest request = request(PROTECTED_PATH, "Bearer " + token);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			// when
			filter.doFilter(request, response, chain);

			// then
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			assertThat(request.getAttribute(JwtAuthenticationFilter.JWT_EXCEPTION_ATTRIBUTE))
				.isInstanceOfSatisfying(JwtAuthenticationException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(JwtErrorCode.ACCOUNT_MISMATCH);
					assertThat(e.getMessage()).isEqualTo("토큰이 가리키는 계정을 찾을 수 없습니다.");
				});
			if (lookedUpId != null) {
				then(userRepository).should().existsByIdAndUsername(lookedUpId, lookedUpUsername);
			}
			assertThat(chain.getRequest()).isSameAs(request);
			assertThat(response.isCommitted()).isFalse();
		}
	}

	static Stream<Arguments> tokensOfMissingAccounts() {
		return Stream.of(
			// 탈퇴한 회원의 토큰. 같은 username 의 계정이 없다.
			Arguments.of("탈퇴한 계정의 토큰",
				signedByThisServer(claimsOfThisServer().subject("withdrawn").claim("userId", 7L)),
				7L, "withdrawn"),
			// 탈퇴한 뒤 같은 이메일로 다시 가입하면 username 은 같고 id 는 다르다. 탈퇴 전 토큰은 옛 id 를 들고 있다.
			Arguments.of("탈퇴 뒤 같은 username 으로 다시 가입한 계정에 옛 id 로 온 토큰",
				signedByThisServer(claimsOfThisServer().claim("userId", 99L)),
				99L, "member"),
			// 이 서버는 늘 userId 를 넣어 발급한다. 없으면 이 서버의 토큰으로 보지 않고 계정 확인 없이 거절한다.
			Arguments.of("userId 클레임이 없는 토큰",
				signedByThisServer(claimsOfThisServer().claim("userId", null)),
				null, null),
			Arguments.of("userId 가 숫자가 아닌 토큰",
				signedByThisServer(claimsOfThisServer().claim("userId", String.valueOf(MEMBER_ID))),
				null, null));
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

	private static MockHttpServletRequest request(String path, String authorizationHeader) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
		return request;
	}
}
