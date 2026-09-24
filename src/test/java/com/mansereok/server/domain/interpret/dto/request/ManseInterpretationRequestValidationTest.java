package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ManseInterpretationRequest - 요청 검증")
class ManseInterpretationRequestValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void openValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	private ManseInterpretationRequest request(String name, String sourceTitle) {
		return new ManseInterpretationRequest(
			name, LocalDate.of(1995, 5, 5), LocalTime.of(12, 0), "MALE",
			false, false, sourceTitle, 1L);
	}

	private Set<String> violatedFields(ManseInterpretationRequest request) {
		return validator.validate(request).stream()
			.map(ConstraintViolation::getPropertyPath)
			.map(Object::toString)
			.collect(Collectors.toSet());
	}

	@Test
	@DisplayName("정상 요청은 위반이 없다")
	void shouldPassValidRequest() {
		assertThat(violatedFields(request("김태우", "슬램덩크"))).isEmpty();
	}

	@Test
	@DisplayName("작품명은 없어도 된다")
	void shouldAllowNullSourceTitle() {
		assertThat(violatedFields(request("김태우", null))).isEmpty();
	}

	@Test
	@DisplayName("이름이 비어 있으면 위반이 잡힌다")
	void shouldRejectBlankName() {
		assertThat(violatedFields(request("   ", "슬램덩크"))).contains("name");
	}

	@Test
	@DisplayName("이름이 null 이면 위반이 잡힌다")
	void shouldRejectNullName() {
		assertThat(violatedFields(request(null, "슬램덩크"))).contains("name");
	}

	@Test
	@DisplayName("이름이 30자를 넘으면 위반이 잡힌다")
	void shouldRejectTooLongName() {
		assertThat(violatedFields(request("가".repeat(31), null))).contains("name");
	}

	@Test
	@DisplayName("작품명이 60자를 넘으면 위반이 잡힌다")
	void shouldRejectTooLongSourceTitle() {
		assertThat(violatedFields(request("김태우", "나".repeat(61)))).contains("sourceTitle");
	}

	@Test
	@DisplayName("생년월일과 성별이 없으면 위반이 잡힌다")
	void shouldRejectMissingSolarDateAndGender() {
		ManseInterpretationRequest request = new ManseInterpretationRequest(
			"김태우", null, LocalTime.of(12, 0), null, false, false, null, 1L);

		assertThat(violatedFields(request)).contains("solarDate", "gender");
	}
}
