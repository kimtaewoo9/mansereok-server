package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.auth.security.JwtAccessDeniedHandler;
import com.mansereok.server.domain.auth.security.JwtAuthenticationEntryPoint;
import com.mansereok.server.domain.auth.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 없이 부를 수 있는 경로, 로그인이나 관리자 역할이 필요한 경로, CSRF 토큰을 확인하지 않는 경로를 표 하나로 고정한다.
 *
 * <p>SecurityConfig 의 보안 필터 체인(JwtAuthenticationFilter, AllowedOriginFilter, 진입점, 거부 처리기 포함)은 운영과 같게 띄우고,
 * 컨트롤러 자리에는 모든 경로를 받아 200 을 돌려주는 {@link RouteCheckController} 만 둔다. 그래서 200 은 "보안 필터를 통과했다" 는
 * 뜻이고, 401·403 은 보안 필터가 막았다는 뜻이다. 토큰은 운영과 같은 JwtUtil 로 만들고, 만료된 토큰만 같은 키로 직접 만든다.
 *
 * <p>spring-security-test 가 의존성에 없으므로 CSRF 토큰은 프론트엔드처럼 받는다. 먼저 GET 요청으로 XSRF-TOKEN 쿠키를 받고,
 * 같은 값을 쿠키와 X-XSRF-TOKEN 헤더에 담아 되돌려 보낸다.
 */
@WebMvcTest(
	controllers = SecurityRulesTest.RouteCheckController.class,
	properties = {
		"app.jwt.secret=security-rules-test-secret-0123456789abcdef",
		"app.cors.allowed-origins=https://www.namedsaju.com",
		"spring.security.oauth2.client.registration.google.client-id=security-rules-test",
		"spring.security.oauth2.client.registration.google.client-secret=security-rules-test",
		"logging.level.org.springframework.security=INFO"
	})
@Import({
	SecurityConfig.class,
	JwtAuthenticationEntryPoint.class,
	JwtAccessDeniedHandler.class,
	JwtUtil.class,
	JwtConfig.class,
	SecurityRulesTest.RouteCheckController.class
})
class SecurityRulesTest {

	private static final String ALLOWED_ORIGIN = "https://www.namedsaju.com";
	private static final String UNKNOWN_ORIGIN = "https://evil.vercel.app";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JwtUtil jwtUtil;
	@Autowired
	private SecretKey jwtSecretKey;

	@ParameterizedTest(name = "[{index}] {0} {1} ({2}, CSRF 토큰 {3}) → {4}")
	@CsvSource(textBlock = """
		# 메서드, 경로,                        요청자,       CSRF 토큰, 기대 상태
		# 로그인 없이 여는 조회. 만료된 토큰을 붙여 와도 막지 않는다.
		GET,    /api/v1/products,               ANONYMOUS,    false,     200
		GET,    /api/v1/products/1,             EXPIRED_USER, false,     200
		GET,    /api/v1/reviews,                ANONYMOUS,    false,     200
		GET,    /api/v1/reviews/pagination,     ANONYMOUS,    false,     200
		GET,    /api/v1/reviews/pagination,     EXPIRED_USER, false,     200
		GET,    /api/auth/csrf-token,           ANONYMOUS,    false,     200
		# 로그인 전에 부르는 쓰기. 로그인도 CSRF 토큰도 없이 받는다(쿠키로 동작하는 요청은 출처를 따로 확인한다).
		POST,   /api/v1/manseryeok/calculate,   ANONYMOUS,    false,     200
		POST,   /api/auth/refresh,              ANONYMOUS,    false,     200
		POST,   /member/kakao/doLogin,          ANONYMOUS,    false,     200
		# 포트원이 보내는 결제 웹훅은 로그인도 CSRF 토큰도 없이 받는다.
		POST,   /api/payment/webhook,           ANONYMOUS,    false,     200
		# 내 정보와 결제는 로그인이 필요하다. 만료된 토큰도 401.
		GET,    /api/payments/me,               ANONYMOUS,    false,     401
		GET,    /api/payments/me,               EXPIRED_USER, false,     401
		GET,    /api/payments/me,               USER,         false,     200
		GET,    /api/v1/users/me/profiles,      ANONYMOUS,    false,     401
		# 리뷰 조회 중 공개는 목록과 페이지 목록뿐이다. 같은 주소 아래의 다른 GET(작성 자격 확인)은 로그인이 필요하다.
		GET,    /api/v1/reviews/eligibility,    ANONYMOUS,    false,     401
		# 할인 코드 확인은 로그인이 필요하다.
		POST,   /api/payment/discount,          ANONYMOUS,    true,      401
		POST,   /api/payment/discount,          USER,         true,      200
		# 리뷰 삭제는 관리자(ADMIN, SUPER_ADMIN)만 한다. 매니저도 삭제하지 못한다.
		DELETE, /api/v1/reviews/7,              ANONYMOUS,    true,      401
		DELETE, /api/v1/reviews/7,              USER,         true,      403
		DELETE, /api/v1/reviews/7,              MANAGER,      true,      403
		DELETE, /api/v1/reviews/7,              ADMIN,        true,      200
		DELETE, /api/v1/reviews/7,              SUPER_ADMIN,  true,      200
		# 로그인한 회원의 쓰기도 CSRF 토큰이 없으면 403.
		POST,   /api/v1/reviews,                USER,         false,     403
		POST,   /api/v1/reviews,                USER,         true,      200
		# 공개 조회와 같은 주소라도 GET 이 아니면 로그인이 필요하고, 유효한 토큰이면 통과한다.
		POST,   /api/v1/products,               ANONYMOUS,    true,      401
		POST,   /api/v1/products,               ADMIN,        true,      200
		""")
	@DisplayName("경로마다 로그인·역할·CSRF 토큰 규칙대로 통과시키거나 막는다")
	void appliesAccessRule(HttpMethod method, String path, Caller caller, boolean withCsrfToken,
		int expectedStatus) throws Exception {
		// when
		ResultActions result = mockMvc.perform(
			request(method, path).with(as(caller)).with(csrfToken(withCsrfToken)));

		// then
		result.andExpect(status().is(expectedStatus));
	}

	@Nested
	@DisplayName("다른 사이트에서 온 요청은")
	class CrossOrigin {

		@Test
		@DisplayName("허용하지 않은 출처의 재발급 요청이면 403 CORS_ORIGIN_FORBIDDEN 으로 막고 CORS 허용 헤더를 붙이지 않는다")
		void refreshFromUnknownOriginIsForbidden() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				post("/api/auth/refresh").header(HttpHeaders.ORIGIN, UNKNOWN_ORIGIN));

			// then
			result.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("CORS_ORIGIN_FORBIDDEN"))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		}

		@Test
		@DisplayName("허용한 출처의 재발급 요청이면 통과시키고 그 출처와 쿠키 허용을 CORS 헤더로 알려 준다")
		void refreshFromAllowedOriginGetsCorsHeaders() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				post("/api/auth/refresh").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));

			// then
			result.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
		}

		@Test
		@DisplayName("허용하지 않은 출처라면 유효한 토큰을 실은 조회도 CORS 가 403 으로 막고 허용 헤더를 붙이지 않는다")
		void corsRejectsUnknownOriginOnOtherPaths() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				get("/api/payments/me").with(as(Caller.USER)).header(HttpHeaders.ORIGIN, UNKNOWN_ORIGIN));

			// then
			result.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		}
	}

	@Test
	@DisplayName("응답에 X-Frame-Options: DENY 를 붙여 다른 사이트가 화면을 iframe 에 넣지 못하게 한다")
	void deniesFraming() throws Exception {
		// when
		ResultActions result = mockMvc.perform(get("/api/v1/products"));

		// then
		result.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	/**
	 * 요청을 보내는 사람. 표에서 이름으로 적는다.
	 */
	enum Caller {
		ANONYMOUS, EXPIRED_USER, USER, MANAGER, ADMIN, SUPER_ADMIN
	}

	private RequestPostProcessor as(Caller caller) {
		return switch (caller) {
			case ANONYMOUS -> request -> request;
			case EXPIRED_USER -> bearer(expiredToken("member", "ROLE_USER"));
			case USER -> bearer(tokenWithRole("member", "ROLE_USER"));
			case MANAGER -> bearer(tokenWithRole("manager", "ROLE_MANAGER"));
			case ADMIN -> bearer(tokenWithRole("admin", "ROLE_ADMIN"));
			case SUPER_ADMIN -> bearer(tokenWithRole("super-admin", "ROLE_SUPER_ADMIN"));
		};
	}

	private static RequestPostProcessor bearer(String token) {
		return request -> {
			request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			return request;
		};
	}

	/**
	 * 로그인·재발급 때와 같은 방식으로 역할 클레임을 담은 액세스 토큰을 만든다.
	 */
	private String tokenWithRole(String username, String role) {
		return jwtUtil.generateAccessToken(username, Map.of("role", role));
	}

	/**
	 * 같은 키로 서명했지만 2020년에 만료된 토큰. JwtUtil 은 발급 시각을 시스템 시계로 정하므로 직접 만든다.
	 */
	private String expiredToken(String username, String role) {
		return Jwts.builder()
			.subject(username)
			.claim("role", role)
			.issuer("www.namedsaju.com")
			.issuedAt(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
			.expiration(Date.from(Instant.parse("2020-01-01T00:30:00Z")))
			.signWith(jwtSecretKey)
			.compact();
	}

	/**
	 * 프론트엔드처럼 GET 요청으로 받은 XSRF-TOKEN 쿠키 값을 쿠키와 X-XSRF-TOKEN 헤더에 담는다. false 면 아무것도 담지 않는다.
	 */
	private RequestPostProcessor csrfToken(boolean attach) throws Exception {
		if (!attach) {
			return request -> request;
		}
		Cookie xsrfCookie = mockMvc.perform(get("/api/auth/csrf-token")).andReturn()
			.getResponse().getCookie("XSRF-TOKEN");
		assertThat(xsrfCookie).as("GET 응답에 실린 XSRF-TOKEN 쿠키").isNotNull();
		return request -> {
			request.setCookies(xsrfCookie);
			request.addHeader("X-XSRF-TOKEN", xsrfCookie.getValue());
			return request;
		};
	}

	/**
	 * 보안 규칙만 보려고 모든 경로를 받아 200 을 돌려주는 컨트롤러. 실제 컨트롤러와 서비스를 띄우지 않아도, 요청이 보안 필터를
	 * 통과했는지를 상태 코드로 알 수 있다. 테스트 클래스 안의 클래스라 다른 테스트의 컴포넌트 스캔에는 잡히지 않는다.
	 */
	@RestController
	static class RouteCheckController {

		@RequestMapping("/**")
		String passed() {
			return "passed";
		}
	}
}
