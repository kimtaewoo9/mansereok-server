package com.mansereok.server.domain.auth.controller;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.dto.request.PasswordResetConfirmDto;
import com.mansereok.server.domain.auth.dto.request.PasswordResetRequestDto;
import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 비밀번호 재설정 두 API 가 요청 본문을 입구에서 검사하는지 확인한다. 새 비밀번호는 가입과 같은 규칙(비어 있지 않고 6자 이상)을
 * 따르고, 규칙을 어긴 요청은 400 VALIDATION_ERROR 로 끝나 UserService 까지 가지 않는다.
 *
 * <p>운영과 같게 두 예외 처리기를 등록한 standalone MockMvc 로 부른다. UserService 는 목이다.
 */
class PasswordResetInputValidationTest {

	private static final String REQUEST_URL = "/api/auth/password-reset/request";
	private static final String CONFIRM_URL = "/api/auth/password-reset/confirm";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final UserService userService = mock(UserService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		AuthController authController = new AuthController(mock(AuthenticationManager.class), userService,
			mock(JwtUtil.class), mock(RefreshTokenService.class));
		mockMvc = MockMvcBuilders.standaloneSetup(authController)
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("새 비밀번호로 재설정할 때")
	class WhenConfirming {

		@ParameterizedTest(name = "[{index}] 새 비밀번호 [{0}] → 400")
		@NullAndEmptySource
		@ValueSource(strings = {"      ", "1", "12345"})
		@DisplayName("새 비밀번호가 없거나, 공백뿐이거나, 6자보다 짧으면 400 VALIDATION_ERROR 이고 서비스를 부르지 않는다")
		void rejectsWeakPassword(String newPassword) throws Exception {
			confirm(new PasswordResetConfirmDto("reset-token", newPassword))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.newPassword").exists());
			verifyNoInteractions(userService);
		}

		@ParameterizedTest(name = "[{index}] 새 비밀번호 [{0}] → 200")
		@ValueSource(strings = {"123456", "1234567"})
		@DisplayName("새 비밀번호가 6자 이상이면 200 이고 토큰과 새 비밀번호를 그대로 서비스에 넘긴다")
		void acceptsPasswordOfSixOrMoreCharacters(String newPassword) throws Exception {
			confirm(new PasswordResetConfirmDto("reset-token", newPassword))
				.andExpect(status().isOk());
			then(userService).should().resetPassword("reset-token", newPassword);
		}

		@ParameterizedTest(name = "[{index}] 토큰 [{0}] → 400")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("재설정 토큰이 없거나 공백뿐이면 400 VALIDATION_ERROR 이고 서비스를 부르지 않는다")
		void rejectsBlankToken(String token) throws Exception {
			confirm(new PasswordResetConfirmDto(token, "new-password"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.token").exists());
			verifyNoInteractions(userService);
		}

		private ResultActions confirm(PasswordResetConfirmDto body) throws Exception {
			return mockMvc.perform(post(CONFIRM_URL).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
		}
	}

	@Nested
	@DisplayName("재설정 메일을 요청할 때")
	class WhenRequestingMail {

		@ParameterizedTest(name = "[{index}] 이메일 [{0}] → 400")
		@NullSource
		@ValueSource(strings = {"", "   ", "not-an-email", "user@@example.com"})
		@DisplayName("이메일이 없거나 형식이 틀리면 400 VALIDATION_ERROR 이고 서비스를 부르지 않는다")
		void rejectsInvalidEmail(String email) throws Exception {
			request(new PasswordResetRequestDto(email))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.email").exists());
			verifyNoInteractions(userService);
		}

		@Test
		@DisplayName("이메일 형식이 맞으면 200 이고 그 이메일을 서비스에 넘긴다")
		void acceptsValidEmail() throws Exception {
			request(new PasswordResetRequestDto("member@example.com"))
				.andExpect(status().isOk());
			then(userService).should().requestPasswordReset("member@example.com");
		}

		private ResultActions request(PasswordResetRequestDto body) throws Exception {
			return mockMvc.perform(post(REQUEST_URL).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
		}
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {REQUEST_URL, CONFIRM_URL})
	@DisplayName("본문이 빈 객체({})면 두 API 모두 400 VALIDATION_ERROR 이고 서비스를 부르지 않는다")
	void rejectsEmptyObject(String url) throws Exception {
		mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
		verifyNoInteractions(userService);
	}
}
