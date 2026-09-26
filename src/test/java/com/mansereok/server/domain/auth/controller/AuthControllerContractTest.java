package com.mansereok.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mansereok.server.domain.auth.service.oauth.GoogleService;
import com.mansereok.server.domain.auth.service.oauth.KakaoService;
import com.mansereok.server.domain.auth.service.oauth.NaverService;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginResult;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginService;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.auth.service.oauth.XService;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.auth.util.RefreshTokenCookies;
import com.mansereok.server.domain.user.controller.ProfileController;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.CustomUserDetailsService;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.domain.user.service.RotatedRefreshToken;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.config.RefreshCookieProperties;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.InvalidRefreshTokenException;
import com.mansereok.server.global.exception.OauthExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import com.mansereok.server.support.SetCookieHeader;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 로그인·재발급·로그아웃·소셜 로그인·탈퇴 API 가 프론트엔드와 맺은 약속(상태 코드, 본문, REFRESH_TOKEN 쿠키)을 확인한다.
 *
 * <p>운영과 같게 세 예외 처리기를 등록한 standalone MockMvc 로 부른다. 이메일 로그인은 운영과 같은 구성(DaoAuthenticationProvider
 * + CustomUserDetailsService + BCrypt)의 진짜 AuthenticationManager 를 쓰고, 회원 조회만 UserRepository 스텁으로 정한다.
 * JwtUtil 과 RefreshTokenCookies 도 진짜다. 리프레시 토큰의 발급·회전·폐기 규칙은 DB 잠금에 기대므로 RefreshTokenServiceTest 와
 * MySQL 테스트가 따로 확인하고, 여기서는 RefreshTokenService 가 돌려주거나 던지는 값을 스텁해 컨트롤러가 그것을 응답으로 옮기는
 * 모양만 본다. 소셜 제공자 호출(GoogleService)과 계정 찾기·가입(OauthLoginService)도 결과만 스텁한다.
 */
class AuthControllerContractTest {

	private static final String SIGN_IN_URL = "/api/auth/sign-in";
	private static final String REFRESH_URL = "/api/auth/refresh";
	private static final String SIGN_OUT_URL = "/api/auth/sign-out";
	private static final String GOOGLE_LOGIN_URL = "/member/google/doLogin";
	private static final String DELETE_ME_URL = "/api/v1/users/me";

	private static final String EMAIL_MEMBER = "member@example.com";
	private static final String SOCIAL_MEMBER_EMAIL = "social@example.com";
	private static final String UNKNOWN_EMAIL = "nobody@example.com";
	private static final String PASSWORD = "correct-password";

	private static final String PRESENTED_TOKEN = "presented-refresh-token";
	private static final String ISSUED_TOKEN = "issued-refresh-token";

	/** 새 토큰 쿠키의 속성(토큰 수명 7일). Expires 는 응답 시각마다 달라 뺀다. */
	private static final Map<String, String> ISSUED_COOKIE_ATTRIBUTES = Map.of(
		"path", "/", "max-age", "604800", "httponly", "", "secure", "", "samesite", "Lax");
	/** 지우는 쿠키의 속성. Max-Age 만 0 이고 나머지는 새 토큰 쿠키와 같다. */
	private static final Map<String, String> EXPIRED_COOKIE_ATTRIBUTES = Map.of(
		"path", "/", "max-age", "0", "httponly", "", "secure", "", "samesite", "Lax");

	private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserService userService = mock(UserService.class);
	private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
	private final GoogleService googleService = mock(GoogleService.class);
	private final OauthLoginService oauthLoginService = mock(OauthLoginService.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		JwtProperties jwtProperties = new JwtProperties("auth-controller-contract-test-secret-0123456789",
			1_800_000L, 604_800_000L, "mansereok");
		JwtUtil jwtUtil = new JwtUtil(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)),
			jwtProperties);
		RefreshTokenCookies refreshTokenCookies = new RefreshTokenCookies(jwtProperties,
			new RefreshCookieProperties(SameSite.LAX));

		DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(
			new CustomUserDetailsService(userRepository));
		authenticationProvider.setPasswordEncoder(PASSWORD_ENCODER);

		AuthController authController = new AuthController(new ProviderManager(authenticationProvider),
			userService, jwtUtil, refreshTokenService, refreshTokenCookies);
		OauthController oauthController = new OauthController(oauthLoginService, googleService,
			mock(KakaoService.class), mock(NaverService.class), mock(XService.class), jwtUtil, refreshTokenService,
			refreshTokenCookies);
		ProfileController profileController = new ProfileController(userService, refreshTokenCookies);

		mockMvc = MockMvcBuilders.standaloneSetup(authController, oauthController, profileController)
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler(),
				new OauthExceptionHandler())
			.build();
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("이메일로 로그인할 때")
	class WhenSigningIn {

		@Test
		@DisplayName("이메일과 비밀번호가 맞으면 200 과 액세스 토큰을 주고 새 리프레시 토큰을 쿠키 하나에 담는다")
		void issuesRefreshCookieOnSuccess() throws Exception {
			// given
			User member = emailMember(1L, EMAIL_MEMBER, "홍길동");
			given(userRepository.findByEmail(EMAIL_MEMBER)).willReturn(Optional.of(member));
			given(refreshTokenService.issue(member)).willReturn(ISSUED_TOKEN);

			// when
			MockHttpServletResponse response = mockMvc.perform(signIn(EMAIL_MEMBER, PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.email").value(EMAIL_MEMBER))
				.andReturn().getResponse();

			// then
			SetCookieHeader cookie = refreshCookie(response);
			assertThat(cookie.value()).isEqualTo(ISSUED_TOKEN);
			assertThat(cookie.attributesWithoutExpires()).isEqualTo(ISSUED_COOKIE_ATTRIBUTES);
		}

		@Test
		@DisplayName("소셜 가입 이메일에 아무 비밀번호나 보내도, 가입하지 않은 이메일과 똑같은 401 INVALID_CREDENTIALS 본문을 준다")
		void socialAccountLooksLikeUnknownEmail() throws Exception {
			// given
			given(userRepository.findByEmail(SOCIAL_MEMBER_EMAIL))
				.willReturn(Optional.of(googleMember(2L, SOCIAL_MEMBER_EMAIL, "구글회원")));
			given(userRepository.findByEmail(UNKNOWN_EMAIL)).willReturn(Optional.empty());

			// when
			MockHttpServletResponse socialResponse = mockMvc.perform(signIn(SOCIAL_MEMBER_EMAIL, "any-password"))
				.andReturn().getResponse();
			MockHttpServletResponse unknownResponse = mockMvc.perform(signIn(UNKNOWN_EMAIL, "any-password"))
				.andReturn().getResponse();

			// then
			assertThat(socialResponse.getStatus()).isEqualTo(401);
			assertThat(unknownResponse.getStatus()).isEqualTo(401);
			assertThat(bodyWithoutTimestamp(socialResponse))
				.as("소셜 가입 이메일과 가입하지 않은 이메일의 응답 본문(시각 제외)이 같아야 한다")
				.isEqualTo(bodyWithoutTimestamp(unknownResponse))
				.isEqualTo(objectMapper.readTree("""
					{"status": 401, "errorCode": "INVALID_CREDENTIALS",
					 "message": "이메일 또는 비밀번호가 일치하지 않습니다. 소셜로 가입하셨다면 소셜 로그인을 이용해주세요.",
					 "errors": null}
					"""));
			assertThat(socialResponse.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		}

		@Test
		@DisplayName("이메일 가입자가 비밀번호를 틀리면 가입하지 않은 이메일과 같은 401 INVALID_CREDENTIALS 를 준다")
		void wrongPasswordLooksLikeUnknownEmail() throws Exception {
			// given
			given(userRepository.findByEmail(EMAIL_MEMBER))
				.willReturn(Optional.of(emailMember(1L, EMAIL_MEMBER, "홍길동")));

			// when & then
			mockMvc.perform(signIn(EMAIL_MEMBER, "wrong-password"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
		}
	}

	@Nested
	@DisplayName("토큰을 재발급할 때")
	class WhenRefreshing {

		@Test
		@DisplayName("쿠키의 토큰이 쓸 수 있으면 200 과 새 토큰 쿠키를 주고, userDto.name 에는 username 이 아니라 회원 이름을 담는다")
		void returnsMemberNameAndNewCookie() throws Exception {
			// given
			User member = emailMember(1L, EMAIL_MEMBER, "홍길동");
			given(refreshTokenService.rotate(PRESENTED_TOKEN)).willReturn(new RotatedRefreshToken(ISSUED_TOKEN, member));

			// when
			MockHttpServletResponse response = mockMvc.perform(refresh())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.userDto.id").value("1"))
				.andExpect(jsonPath("$.userDto.email").value(EMAIL_MEMBER))
				.andExpect(jsonPath("$.userDto.name").value("홍길동"))
				.andExpect(jsonPath("$.userDto.role").value("USER"))
				.andExpect(jsonPath("$.userDto.locked").value(false))
				.andReturn().getResponse();

			// then
			SetCookieHeader cookie = refreshCookie(response);
			assertThat(cookie.value()).isEqualTo(ISSUED_TOKEN);
			assertThat(cookie.attributesWithoutExpires()).isEqualTo(ISSUED_COOKIE_ATTRIBUTES);
		}

		@Test
		@DisplayName("쿠키가 없으면 401 INVALID_REFRESH_TOKEN 과 함께 쿠키를 지우는 Set-Cookie 를 준다")
		void expiresCookieWhenMissing() throws Exception {
			// when
			MockHttpServletResponse response = mockMvc.perform(post(REFRESH_URL))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"))
				.andExpect(jsonPath("$.message").value("인증 정보가 없습니다."))
				.andReturn().getResponse();

			// then
			SetCookieHeader cookie = refreshCookie(response);
			assertThat(cookie.value()).isEmpty();
			assertThat(cookie.attributesWithoutExpires()).isEqualTo(EXPIRED_COOKIE_ATTRIBUTES);
		}

		@Test
		@DisplayName("이미 쓴 토큰이라 재발급이 거절되면 401 과 거절 사유를 주고 쿠키를 지운다")
		void expiresCookieWhenTokenIsRejected() throws Exception {
			// given
			given(refreshTokenService.rotate(PRESENTED_TOKEN))
				.willThrow(new InvalidRefreshTokenException("이미 사용한 리프레시 토큰입니다. 다시 로그인해주세요."));

			// when
			MockHttpServletResponse response = mockMvc.perform(refresh())
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"))
				.andExpect(jsonPath("$.message").value("이미 사용한 리프레시 토큰입니다. 다시 로그인해주세요."))
				.andReturn().getResponse();

			// then
			SetCookieHeader cookie = refreshCookie(response);
			assertThat(cookie.value()).isEmpty();
			assertThat(cookie.attributesWithoutExpires()).isEqualTo(EXPIRED_COOKIE_ATTRIBUTES);
		}
	}

	@Nested
	@DisplayName("로그아웃할 때")
	class WhenSigningOut {

		@Test
		@DisplayName("쿠키의 토큰을 폐기하고 204 와 함께 리프레시 쿠키와 세션 쿠키를 지운다")
		void revokesTokenAndExpiresCookies() throws Exception {
			// when
			MockHttpServletResponse response = mockMvc.perform(post(SIGN_OUT_URL)
					.cookie(new Cookie("REFRESH_TOKEN", PRESENTED_TOKEN)))
				.andExpect(status().isNoContent())
				.andReturn().getResponse();

			// then
			then(refreshTokenService).should().revoke(PRESENTED_TOKEN);
			assertThat(refreshCookie(response).attributesWithoutExpires()).isEqualTo(EXPIRED_COOKIE_ATTRIBUTES);
			assertThat(SetCookieHeader.findOnly(response.getHeaders(HttpHeaders.SET_COOKIE), "JSESSIONID")
				.attributes()).containsEntry("max-age", "0");
		}
	}

	@Nested
	@DisplayName("리프레시 쿠키를 내려주는 모든 API 는")
	class AcrossAllEndpoints {

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.auth.controller.AuthControllerContractTest#issuingRequests")
		@DisplayName("새 토큰 쿠키를 같은 속성(Path=/, HttpOnly, Secure, SameSite=Lax, Max-Age=604800)으로 한 번만 싣는다")
		void issueCookieWithSameAttributes(String endpoint, RequestBuilder request) throws Exception {
			// given
			givenEveryLoginSucceeds();

			// when
			MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

			// then
			assertThat(response.getStatus()).as(endpoint).isEqualTo(200);
			assertThat(refreshCookie(response).attributesWithoutExpires()).as(endpoint)
				.isEqualTo(ISSUED_COOKIE_ATTRIBUTES);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.auth.controller.AuthControllerContractTest#expiringRequests")
		@DisplayName("지우는 쿠키도 새 토큰 쿠키와 같은 속성에 Max-Age=0 으로 한 번만 싣는다")
		void expireCookieWithSameAttributes(String endpoint, RequestBuilder request) throws Exception {
			// given
			willThrow(new InvalidRefreshTokenException("만료된 리프레시 토큰입니다. 다시 로그인해주세요."))
				.given(refreshTokenService).rotate(PRESENTED_TOKEN);
			SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(EMAIL_MEMBER, null, List.of()));

			// when
			MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

			// then
			assertThat(refreshCookie(response).attributesWithoutExpires()).as(endpoint)
				.isEqualTo(EXPIRED_COOKIE_ATTRIBUTES);
		}

		/**
		 * 이메일 로그인, 재발급, 구글 로그인이 모두 성공하도록 스텁한다.
		 */
		private void givenEveryLoginSucceeds() {
			User member = emailMember(1L, EMAIL_MEMBER, "홍길동");
			User googleMember = googleMember(2L, SOCIAL_MEMBER_EMAIL, "구글회원");
			OauthProfile profile = new OauthProfile(SocialType.GOOGLE, "google-sub-1", SOCIAL_MEMBER_EMAIL, "구글회원",
				true);
			given(userRepository.findByEmail(EMAIL_MEMBER)).willReturn(Optional.of(member));
			given(refreshTokenService.issue(member)).willReturn(ISSUED_TOKEN);
			given(refreshTokenService.rotate(PRESENTED_TOKEN)).willReturn(new RotatedRefreshToken(ISSUED_TOKEN, member));
			given(googleService.authenticate("google-auth-code")).willReturn(profile);
			given(oauthLoginService.loginOrRegister(profile)).willReturn(new OauthLoginResult(googleMember, false));
			given(refreshTokenService.issue(googleMember)).willReturn(ISSUED_TOKEN);
		}
	}

	static Stream<Arguments> issuingRequests() {
		return Stream.of(
			Arguments.of("이메일 로그인", signIn(EMAIL_MEMBER, PASSWORD)),
			Arguments.of("재발급", refresh()),
			Arguments.of("구글 로그인", post(GOOGLE_LOGIN_URL).contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"google-auth-code\"}")));
	}

	static Stream<Arguments> expiringRequests() {
		return Stream.of(
			Arguments.of("재발급 실패", refresh()),
			Arguments.of("로그아웃", post(SIGN_OUT_URL).cookie(new Cookie("REFRESH_TOKEN", PRESENTED_TOKEN))),
			Arguments.of("회원 탈퇴", delete(DELETE_ME_URL)));
	}

	private static RequestBuilder signIn(String email, String password) {
		return post(SIGN_IN_URL).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}");
	}

	private static RequestBuilder refresh() {
		return post(REFRESH_URL).cookie(new Cookie("REFRESH_TOKEN", PRESENTED_TOKEN));
	}

	/**
	 * 응답에 REFRESH_TOKEN 쿠키가 딱 하나 있으면 그 쿠키를 돌려준다. 같은 이름이 두 번 실리면 실패한다.
	 */
	private static SetCookieHeader refreshCookie(MockHttpServletResponse response) {
		return SetCookieHeader.findOnly(response.getHeaders(HttpHeaders.SET_COOKIE), "REFRESH_TOKEN");
	}

	private JsonNode bodyWithoutTimestamp(MockHttpServletResponse response) throws Exception {
		ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		body.remove("timestamp");
		return body;
	}

	/**
	 * 이메일 가입자. username 과 email 이 같고 비밀번호는 PASSWORD 를 BCrypt 로 암호화한 값이다.
	 */
	private static User emailMember(Long id, String email, String name) {
		User user = User.create(email, name, PASSWORD_ENCODER.encode(PASSWORD), email, LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false);
		user.setId(id);
		return user;
	}

	/**
	 * 구글 가입자. username 은 구글의 사용자 번호이고 비밀번호가 없다.
	 */
	private static User googleMember(Long id, String email, String name) {
		User user = User.createByOauth("google-sub-" + id, name, email, "google-sub-" + id, SocialType.GOOGLE);
		user.setId(id);
		return user;
	}
}
