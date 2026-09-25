package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest.PersonInfo;
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

@DisplayName("ManseCompatibilityAnalysisRequest - 요청 검증")
class ManseCompatibilityAnalysisRequestValidationTest {

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

	private PersonInfo person(String name, String sourceTitle) {
		PersonInfo person = new PersonInfo();
		person.setName(name);
		person.setSolarDate(LocalDate.of(1995, 5, 5));
		person.setSolarTime(LocalTime.of(12, 0));
		person.setGender("MALE");
		person.setIsLunar(false);
		person.setLeapMonth(false);
		person.setSourceTitle(sourceTitle);
		return person;
	}

	private ManseCompatibilityAnalysisRequest request(PersonInfo person1, PersonInfo person2) {
		ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
		request.setPerson1(person1);
		request.setPerson2(person2);
		request.setPaymentId(1L);
		return request;
	}

	private Set<String> violatedFields(ManseCompatibilityAnalysisRequest request) {
		return validator.validate(request).stream()
			.map(ConstraintViolation::getPropertyPath)
			.map(Object::toString)
			.collect(Collectors.toSet());
	}

	@Test
	@DisplayName("정상 요청은 위반이 없다")
	void shouldPassValidRequest() {
		assertThat(violatedFields(request(person("김태우", "슬램덩크"), person("이영희", null))))
			.isEmpty();
	}

	@Test
	@DisplayName("중첩 인물의 이름이 비면 person1.name 위반이 잡힌다")
	void shouldValidateNestedPersonName() {
		assertThat(violatedFields(request(person(" ", null), person("이영희", null))))
			.contains("person1.name");
	}

	@Test
	@DisplayName("중첩 인물의 이름 길이 제한도 적용된다")
	void shouldValidateNestedPersonNameLength() {
		assertThat(violatedFields(request(person("김태우", null), person("가".repeat(31), null))))
			.contains("person2.name");
	}

	@Test
	@DisplayName("중첩 인물의 작품명 길이 제한도 적용된다")
	void shouldValidateNestedSourceTitleLength() {
		assertThat(violatedFields(request(person("김태우", "나".repeat(61)), person("이영희", null))))
			.contains("person1.sourceTitle");
	}

	@Test
	@DisplayName("두 인물 정보는 필수다")
	void shouldRequireBothPersons() {
		assertThat(violatedFields(request(null, null))).contains("person1", "person2");
	}

	@Test
	@DisplayName("중첩 인물의 생년월일과 성별이 없으면 위반이 잡힌다")
	void shouldRequireNestedSolarDateAndGender() {
		PersonInfo broken = person("김태우", null);
		broken.setSolarDate(null);
		broken.setGender(null);

		assertThat(violatedFields(request(broken, person("이영희", null))))
			.contains("person1.solarDate", "person1.gender");
	}
}
