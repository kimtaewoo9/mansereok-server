package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 무료 궁합 엔드포인트가 받는 DTO. 이름이 그대로 프롬프트에 들어가므로
 * 유료 경로와 같은 규칙이 컨트롤러 입구에서 걸려야 한다.
 * 여기서 막히지 않으면 비동기 처리 중 정화기가 예외를 던져 202 뒤에 조용히 실패한다.
 */
@DisplayName("CompatibilityAnalysisRequest - 무료 궁합 요청 검증")
class CompatibilityAnalysisRequestValidationTest {

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

	private ManseryeokCreateRequest person(String name) {
		ManseryeokCreateRequest person = new ManseryeokCreateRequest();
		person.setName(name);
		person.setGender("MALE");
		person.setCalendar("S");
		person.setBirthday("1995/05/05");
		person.setBirthtime("12:00");
		person.setYear(1995);
		person.setMonth(5);
		person.setDay(5);
		person.setHour(12);
		person.setMin(0);
		return person;
	}

	private CompatibilityAnalysisRequest request(ManseryeokCreateRequest person1,
		ManseryeokCreateRequest person2) {
		CompatibilityAnalysisRequest request = new CompatibilityAnalysisRequest();
		request.setPerson1(person1);
		request.setPerson2(person2);
		return request;
	}

	private Set<String> violatedFields(CompatibilityAnalysisRequest request) {
		return validator.validate(request).stream()
			.map(ConstraintViolation::getPropertyPath)
			.map(Object::toString)
			.collect(Collectors.toSet());
	}

	@Test
	@DisplayName("정상 요청은 위반이 없다")
	void shouldPassValidRequest() {
		assertThat(violatedFields(request(person("김태우"), person("이영희")))).isEmpty();
	}

	@Test
	@DisplayName("이름이 공백이면 person1.name 위반이 잡혀 400 으로 끝난다")
	void shouldRejectBlankName() {
		assertThat(violatedFields(request(person("  "), person("이영희"))))
			.contains("person1.name");
	}

	@Test
	@DisplayName("이름이 null 이어도 위반이 잡힌다")
	void shouldRejectNullName() {
		assertThat(violatedFields(request(person("김태우"), person(null))))
			.contains("person2.name");
	}

	@Test
	@DisplayName("이름이 30자를 넘으면 위반이 잡힌다")
	void shouldRejectTooLongName() {
		assertThat(violatedFields(request(person("가".repeat(31)), person("이영희"))))
			.contains("person1.name");
	}

	@Test
	@DisplayName("두 인물 정보는 필수다")
	void shouldRequireBothPersons() {
		assertThat(violatedFields(request(null, null))).contains("person1", "person2");
	}
}
