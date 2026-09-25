package com.mansereok.server.domain.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.auth.service.oauth.GoogleService;
import com.mansereok.server.domain.auth.service.oauth.KakaoService;
import com.mansereok.server.domain.auth.service.oauth.NaverService;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginService;
import com.mansereok.server.domain.auth.service.oauth.XService;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.OauthExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * 소셜 로그인 제공자 호출이 실패했을 때 로그인 API 가 500 이 아니라 401·503 으로 답하는지 확인한다.
 *
 * <p>운영과 같게 세 예외 처리기를 모두 등록하고, OauthExceptionHandler 를 가장 나중에 등록한다. 그래도 먼저 답하는 것은 등록 순서가
 * 아니라 @Order(HIGHEST_PRECEDENCE) 덕분임을 확인하기 위해서다. 인증 컨트롤러도 함께 올려, OauthController 만 맡는 처리기가 다른
 * 컨트롤러의 오류 응답을 바꾸지 않는지 본다.
 *
 * <p>제공자는 MockRestServiceServer 로 흉내 내고 제공자 서비스는 진짜를 쓴다. 계정 찾기·토큰 발급은 이 실패 경로에서 불리지 않아
 * 아무 값도 정하지 않은 목으로 둔다.
 */
class OauthControllerErrorResponseTest {

	private static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
	private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

	private MockRestServiceServer providerServer;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		providerServer = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();

		OauthController oauthController = new OauthController(mock(OauthLoginService.class),
			new GoogleService(restClient), new KakaoService(restClient), new NaverService(restClient),
			new XService(restClient), mock(JwtUtil.class), mock(RefreshTokenService.class));
		AuthController authController = new AuthController(mock(AuthenticationManager.class),
			mock(UserService.class), mock(JwtUtil.class), mock(RefreshTokenService.class));

		mockMvc = MockMvcBuilders.standaloneSetup(oauthController, authController)
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler(),
				new OauthExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("제공자가 인가 코드를 400 으로 거절하면 401 OAUTH_LOGIN_FAILED 로 답하고 제공자 응답 원문은 담지 않는다")
	void providerRejectsCode() throws Exception {
		// given: 같은 인가 코드를 두 번 보내면 카카오는 KOE320 으로 거절한다
		providerServer.expect(once(), requestTo(KAKAO_TOKEN_URL))
			.andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\": \"invalid_grant\", \"error_code\": \"KOE320\"}"));

		// when & then
		mockMvc.perform(post("/member/kakao/doLogin").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"USED-CODE\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.errorCode").value("OAUTH_LOGIN_FAILED"))
			.andExpect(jsonPath("$.message").value("소셜 로그인에 실패했습니다. 처음부터 다시 로그인해주세요."))
			.andExpect(content().string(not(containsString("KOE320"))));
	}

	@Test
	@DisplayName("제공자가 시간 안에 답하지 않으면 503 OAUTH_PROVIDER_UNAVAILABLE 로 답한다")
	void providerTimesOut() throws Exception {
		// given
		providerServer.expect(once(), requestTo(GOOGLE_TOKEN_URL))
			.andRespond(withException(new HttpTimeoutException("request timed out")));

		// when & then
		mockMvc.perform(post("/member/google/doLogin").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"AUTH-CODE\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value(503))
			.andExpect(jsonPath("$.errorCode").value("OAUTH_PROVIDER_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("소셜 로그인 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해주세요."));
	}

	@Test
	@DisplayName("다른 컨트롤러(회원가입)의 잘못된 본문(gender 'M')은 이 처리기와 상관없이 400 INVALID_REQUEST_BODY 로 답한다")
	void otherControllerKeepsRequestErrorResponse() throws Exception {
		mockMvc.perform(post("/api/auth/users").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"홍길동\", \"email\": \"a@example.com\", \"password\": \"password1\","
					+ " \"gender\": \"M\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
			.andExpect(jsonPath("$.message").value("요청 본문의 gender 값 'M' 이 올바른 형식이 아닙니다."));
	}
}
