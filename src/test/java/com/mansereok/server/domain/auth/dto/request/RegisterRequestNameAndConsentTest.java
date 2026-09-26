package com.mansereok.server.domain.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 가입 요청의 이름 규칙, 개인정보 처리방침 동의, role 을 받지 않는 것을 확인한다.
 *
 * <p>AuthController.register 는 {@code @Valid} 로 이 검사를 부르고, 어긋나면 GlobalExceptionHandler 가 400 VALIDATION_ERROR 로
 * 답한다. 이름은 프로필 수정과 같은 규칙(비어 있지 않고 20자 이하)이라 "이훈" 같은 두 글자 이름으로도 가입할 수 있다.
 */
class RegisterRequestNameAndConsentTest {

	private static final String REQUIRED = "이름은 필수입니다.";
	private static final String TOO_LONG = "이름은 20자까지 입력할 수 있습니다.";

	private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
	private final Validator validator = validatorFactory.getValidator();

	@AfterEach
	void closeValidatorFactory() {
		validatorFactory.close();
	}

	@ParameterizedTest(name = "[{index}] {0} → 위반 {2}")
	@MethodSource("names")
	@DisplayName("가입 이름이 비었거나 20자를 넘으면 규칙 위반이고, 한두 글자 이름은 통과한다")
	void checksNameLength(String description, String name, List<String> expectedMessages) {
		// given
		RegisterRequest request = validRequest();
		request.setName(name);

		// when
		List<String> messages = messagesOf(request, "name");

		// then
		assertThat(messages).containsExactlyInAnyOrderElementsOf(expectedMessages);
	}

	static Stream<Arguments> names() {
		return Stream.of(
			Arguments.of("없음(null)", null, List.of(REQUIRED)),
			Arguments.of("빈 문자열", "", List.of(REQUIRED)),
			Arguments.of("공백뿐", "   ", List.of(REQUIRED)),
			Arguments.of("한 글자", "훈", List.of()),
			Arguments.of("두 글자", "이훈", List.of()),
			Arguments.of("20자", "가".repeat(20), List.of()),
			Arguments.of("21자", "가".repeat(21), List.of(TOO_LONG)));
	}

	@ParameterizedTest(name = "[{index}] 동의 {0} → 위반 {1}건")
	@CsvSource(textBlock = """
		# 개인정보 처리방침 동의, 위반 수
		true,  0
		false, 1
		""")
	@DisplayName("개인정보 처리방침에 동의하지 않은 가입 요청은 규칙 위반이다")
	void requiresPrivacyPolicyConsent(boolean agreed, int expectedViolations) {
		// given
		RegisterRequest request = validRequest();
		request.setPrivacyPolicyAgreed(agreed);

		// when
		List<String> messages = messagesOf(request, "privacyPolicyAgreed");

		// then
		assertThat(messages).hasSize(expectedViolations)
			.allMatch("개인정보 처리방침에 동의해야 가입할 수 있습니다."::equals);
	}

	@Test
	@DisplayName("요청 본문에 role 이 있어도 오류 없이 읽고, 읽은 요청에는 role 이 남지 않는다")
	void ignoresRoleInRequestBody() throws Exception {
		// given: 운영의 스프링 부트와 같은 기본값(모르는 속성은 무시)의 ObjectMapper
		ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
		String body = """
			{"name": "이훈", "email": "member@example.com", "password": "password1",
			 "privacyPolicyAgreed": true, "role": "ADMIN"}
			""";

		// when
		RegisterRequest request = objectMapper.readValue(body, RegisterRequest.class);

		// then
		assertThat(request.getName()).isEqualTo("이훈");
		assertThat(objectMapper.valueToTree(request).has("role")).as("요청 객체에 role 속성이 없다").isFalse();
		assertThat(validator.validate(request)).isEmpty();
	}

	private List<String> messagesOf(RegisterRequest request, String property) {
		return validator.validate(request).stream()
			.filter(violation -> violation.getPropertyPath().toString().equals(property))
			.map(ConstraintViolation::getMessage)
			.toList();
	}

	private static RegisterRequest validRequest() {
		RegisterRequest request = new RegisterRequest();
		request.setName("가입회원");
		request.setEmail("member@example.com");
		request.setPassword("password1");
		request.setPrivacyPolicyAgreed(true);
		return request;
	}
}
