package com.mansereok.server.domain.review.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 리뷰 작성 요청의 본문(content) 검증을 경계값 표로 확인한다. 컨트롤러의 @Valid 가 이 규칙으로 400 을 내므로, 여기서 막히는 값은
 * 서비스까지 가지 않는다.
 *
 * <p>예전에는 @Size(min = 20) 만 있어서 본문이 빠진 요청(null)이 검증을 통과해 서비스에서 NPE 로 500 이 났고, 공백 20칸도
 * 통과했으며, 최대 길이가 없었다.
 */
class ReviewCreateRequestValidationTest {

	private static final String NOT_BLANK_MESSAGE = "리뷰 내용을 입력해주세요.";
	private static final String LENGTH_MESSAGE = "리뷰 내용은 20자 이상 2000자 이하로 입력해주세요.";

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	static Stream<Arguments> contents() {
		return Stream.of(
			Arguments.of("본문 없음(null)", null, List.of(NOT_BLANK_MESSAGE)),
			Arguments.of("빈 문자열", "", List.of(NOT_BLANK_MESSAGE, LENGTH_MESSAGE)),
			Arguments.of("공백 20칸", " ".repeat(20), List.of(NOT_BLANK_MESSAGE)),
			Arguments.of("19자", "가".repeat(19), List.of(LENGTH_MESSAGE)),
			Arguments.of("20자", "가".repeat(20), List.of()),
			Arguments.of("2000자", "가".repeat(2000), List.of()),
			Arguments.of("2001자", "가".repeat(2001), List.of(LENGTH_MESSAGE))
		);
	}

	@ParameterizedTest(name = "[{index}] {0} → 위반 {2}")
	@MethodSource("contents")
	@DisplayName("본문이 비었거나 공백뿐이거나 20자 미만, 2000자 초과이면 content 필드의 위반으로 막는다")
	void contentIsValidated(String situation, String content, List<String> expectedMessages) {
		// given
		ReviewCreateRequest request = new ReviewCreateRequest();
		request.setSubCategoryId(3L);
		request.setOrderId(100L);
		request.setContent(content);

		// when
		Set<ConstraintViolation<ReviewCreateRequest>> violations = validator.validate(request);

		// then
		assertThat(violations)
			.as("위반은 모두 content 필드에서 나야 한다")
			.allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("content"));
		assertThat(violations)
			.extracting(ConstraintViolation::getMessage)
			.containsExactlyInAnyOrderElementsOf(expectedMessages);
	}
}
