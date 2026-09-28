package com.mansereok.server.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 가입 요청의 비밀번호가 재설정과 같은 규칙(6자 이상, UTF-8 로 72바이트 이하)으로 검사되는지 확인한다.
 *
 * <p>AuthController.register 는 {@code @Valid} 로 이 검사를 부르고, 어긋나면 GlobalExceptionHandler 가 400 VALIDATION_ERROR 로
 * 답한다. 72바이트를 넘는 비밀번호가 검사를 지나가면 BCrypt 가 영어 문구의 IllegalArgumentException 을 던진다. 재설정 쪽은
 * PasswordResetInputValidationTest 가 API 로 본다.
 */
class RegisterRequestPasswordRuleTest {

	private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
	private final Validator validator = validatorFactory.getValidator();

	@AfterEach
	void closeValidatorFactory() {
		validatorFactory.close();
	}

	@ParameterizedTest(name = "[{index}] {0} → 위반 {2}")
	@MethodSource("passwords")
	@DisplayName("가입 비밀번호가 6자보다 짧거나 UTF-8 로 72바이트를 넘으면 규칙 위반이다")
	void checksPasswordLengthAndBytes(String description, String password, List<String> expectedMessages) {
		// given
		RegisterRequest request = new RegisterRequest();
		request.setName("가입회원");
		request.setEmail("member@example.com");
		request.setPassword(password);

		// when
		List<String> messages = validator.validate(request).stream()
			.filter(violation -> violation.getPropertyPath().toString().equals("password"))
			.map(ConstraintViolation::getMessage)
			.toList();

		// then
		assertThat(messages).containsExactlyInAnyOrderElementsOf(expectedMessages);
	}

	static Stream<Arguments> passwords() {
		String tooShort = "비밀번호는 최소 6자 이상이어야 합니다.";
		String tooLong = "비밀번호는 72바이트(영문·숫자 72자, 한글 24자)까지 입력할 수 있습니다.";
		return Stream.of(
			Arguments.of("영문 5자", "a".repeat(5), List.of(tooShort)),
			Arguments.of("영문 6자", "a".repeat(6), List.of()),
			Arguments.of("영문 72자(72바이트)", "a".repeat(72), List.of()),
			Arguments.of("영문 73자(73바이트)", "a".repeat(73), List.of(tooLong)),
			Arguments.of("한글 24자(72바이트)", "가".repeat(24), List.of()),
			Arguments.of("한글 25자(75바이트)", "가".repeat(25), List.of(tooLong)));
	}
}
