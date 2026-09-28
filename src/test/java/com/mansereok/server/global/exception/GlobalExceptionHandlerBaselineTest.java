package com.mansereok.server.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * GlobalExceptionHandler 가 지금 돌려주는 상태 코드·errorCode·message 를 고정한다.
 *
 * <p>RequestErrorExceptionHandler 를 앞에 두면서 기존 응답이 바뀌지 않았는지 보려고 만든 표다. 그래서 두 처리기를 운영과 같게
 * 함께 등록한다. 여기 적힌 값은 지금 동작을 그대로 옮긴 것이고, 바람직한 동작이라는 뜻은 아니다. 예를 들어
 * IllegalArgumentException 은 예외 메시지를 그대로 응답에 담고, DuplicateEmailException 은 ErrorResponse 가 아닌
 * {"error": 메시지} 본문을 쓴다. 이를 바꾸는 PR 은 이 표도 함께 고친다.
 *
 * <p>catch-all 500 이 예외 원문을 숨기는지는 RequestErrorExceptionHandlerTest 가 본다. GptApiFailedException 처리기는 던지는 곳이
 * 없고 해석 스택에서 지워지므로 넣지 않는다. 결제·해석 스택에는 GlobalExceptionHandlerTest 가 따로 있어 이름을 달리했다.
 */
class GlobalExceptionHandlerBaselineTest {

	static Stream<Arguments> exceptionsAndResponses() {
		return Stream.of(
			Arguments.of(new PaymentException("쿠폰이 만료되었습니다."),
				400, "PAYMENT_ERROR", "쿠폰이 만료되었습니다."),
			Arguments.of(new IllegalArgumentException("생년월일 형식이 잘못되었습니다."),
				400, "INVALID_INPUT", "생년월일 형식이 잘못되었습니다."),
			Arguments.of(new BadCredentialsException("password mismatch"),
				401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 일치하지 않습니다. 소셜로 가입하셨다면 소셜 로그인을 이용해주세요."),
			Arguments.of(new InsufficientAuthenticationException("token missing"),
				401, "AUTHENTICATION_FAILED", "인증에 실패했습니다. 다시 로그인해주세요."),
			Arguments.of(new InvalidRefreshTokenException("리프레시 토큰이 만료되었습니다."),
				401, "INVALID_REFRESH_TOKEN", "리프레시 토큰이 만료되었습니다."),
			Arguments.of(new AccessDeniedException("user 3 is not admin"),
				403, "FORBIDDEN", "이 리소스에 접근할 권한이 없습니다."),
			Arguments.of(new EntityNotFoundException("Result 42 not found"),
				404, "NOT_FOUND", "요청하신 리소스를 찾을 수 없습니다.")
		);
	}

	@ParameterizedTest(name = "[{index}] {0} → {1} {2}")
	@MethodSource("exceptionsAndResponses")
	@DisplayName("예외 종류마다 정해진 상태 코드와 ErrorResponse 를 돌려준다")
	void errorResponseForException(Exception exception, int status, String errorCode,
		String message) throws Exception {
		mockMvcThrowing(exception).perform(get("/test/throw"))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.errorCode").value(errorCode))
			.andExpect(jsonPath("$.message").value(message));
	}

	@Test
	@DisplayName("요청 본문 검증에 실패하면 400 VALIDATION_ERROR 와 필드별 메시지를 돌려준다")
	void validationError() throws Exception {
		mockMvcThrowing(new IllegalStateException("부르지 않음"))
			.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
			.andExpect(jsonPath("$.errors.name").value("이름은 필수입니다."));
	}

	@Test
	@DisplayName("정적 리소스가 없으면 본문 없이 404 를 돌려준다")
	void noResourceFound() throws Exception {
		mockMvcThrowing(new NoResourceFoundException(HttpMethod.GET, "favicon.ico"))
			.perform(get("/test/throw"))
			.andExpect(status().isNotFound())
			.andExpect(content().string(""));
	}

	@Test
	@DisplayName("이메일이 이미 있으면 409 와 {\"error\": 메시지} 본문을 돌려준다")
	void duplicateEmail() throws Exception {
		mockMvcThrowing(new DuplicateEmailException("이미 존재하는 이메일입니다."))
			.perform(get("/test/throw"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("이미 존재하는 이메일입니다."))
			.andExpect(jsonPath("$.errorCode").doesNotExist());
	}

	private static MockMvc mockMvcThrowing(Exception exception) {
		return MockMvcBuilders.standaloneSetup(new ThrowingController(exception))
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();
	}

	/**
	 * 테스트가 넘긴 예외를 그대로 던지는 컨트롤러.
	 */
	@RestController
	static class ThrowingController {

		private final Exception exception;

		ThrowingController(Exception exception) {
			this.exception = exception;
		}

		@GetMapping("/test/throw")
		public String throwGivenException() throws Exception {
			throw exception;
		}

		@PostMapping("/test/validated")
		public String validated(@Valid @RequestBody NameRequest request) {
			return "ok";
		}
	}

	record NameRequest(@NotBlank(message = "이름은 필수입니다.") String name) {

	}
}
