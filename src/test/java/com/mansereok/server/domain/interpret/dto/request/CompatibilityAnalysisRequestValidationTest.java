package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 무료 궁합 엔드포인트가 받는 DTO. 이름이 그대로 프롬프트에 들어가므로
 * 유료 경로와 같은 규칙이 컨트롤러 입구에서 걸려야 한다.
 * 여기서 막히지 않으면 비동기 처리 중 정화기가 예외를 던져 202 뒤에 조용히 실패한다.
 *
 * <p>생년월일과 성별은 만세력 계산에 꼭 필요하다. 비어 있거나 생년월일 형식이 틀리면 계산에 들어가기 전에 입구에서 400 으로 끝나야 한다.
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
	@DisplayName("이름이 공백이면 person1.name 위반이 잡힌다")
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

	@ParameterizedTest(name = "[{index}] 생년월일 \"{0}\"")
	@DisplayName("생년월일을 YYYY/MM/DD 나 YYYY-MM-DD 로 적으면 위반이 없다")
	@ValueSource(strings = {"1995/05/05", "1995-05-05"})
	void acceptsBirthdayFormats(String birthday) {
		// given
		ManseryeokCreateRequest person2 = person("이영희");
		person2.setBirthday(birthday);

		// when & then
		assertThat(violatedFields(request(person("김태우"), person2))).isEmpty();
	}

	@ParameterizedTest(name = "[{index}] 생년월일 \"{0}\"")
	@DisplayName("생년월일의 월일이 한 자리거나 구분자가 다르면 person2.birthday 위반이 잡힌다")
	@ValueSource(strings = {"1995/5/5", "1995/05-05", "19950505", "1995.05.05", "95/05/05", "1995/05/05 12:00"})
	void rejectsOtherBirthdayFormats(String birthday) {
		// given
		ManseryeokCreateRequest person2 = person("이영희");
		person2.setBirthday(birthday);

		// when & then
		assertThat(violatedFields(request(person("김태우"), person2))).containsExactly("person2.birthday");
	}

	@ParameterizedTest(name = "[{index}] 생년월일 \"{0}\"")
	@DisplayName("생년월일이 없거나 비어 있으면 person1.birthday 위반이 잡힌다")
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	void shouldRequireBirthday(String birthday) {
		// given
		ManseryeokCreateRequest person1 = person("김태우");
		person1.setBirthday(birthday);

		// when & then
		assertThat(violatedFields(request(person1, person("이영희")))).containsExactly("person1.birthday");
	}

	@ParameterizedTest(name = "[{index}] 성별 \"{0}\"")
	@DisplayName("성별이 없거나 비어 있으면 person2.gender 위반이 잡힌다")
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	void shouldRequireGender(String gender) {
		// given
		ManseryeokCreateRequest person2 = person("이영희");
		person2.setGender(gender);

		// when & then
		assertThat(violatedFields(request(person("김태우"), person2))).containsExactly("person2.gender");
	}

	@Nested
	@DisplayName("화면이 서버가 읽지 않는 필드까지 담아 보내면")
	class WhenRequestHasFieldsTheServerDoesNotRead {

		// 화면은 서버가 읽지 않는 필드(궁합 유형, 연·월·일·시·분 숫자, 장소, 시간 모름·자정 보정)도 보낼 수 있다.
		private static final String REQUEST_BODY = """
			{
			  "compatibilityType": "MARRIAGE",
			  "person1": {
			    "name": "김태우", "gender": "MALE", "calendar": "S", "leapMonth": false,
			    "birthday": "1995/05/05", "birthtime": "12:00",
			    "year": 1995, "month": 5, "day": 5, "hour": 12, "min": 0,
			    "hmUnsure": false, "midnightAdjust": true,
			    "locationId": 1835847, "locationName": "서울특별시, 대한민국"
			  },
			  "person2": {
			    "name": "이영희", "gender": "FEMALE", "calendar": "L", "leapMonth": true,
			    "birthday": "1996-02-29", "birthtime": "",
			    "year": 1996, "month": 2, "day": 29, "hour": 0, "min": 0,
			    "hmUnsure": true, "midnightAdjust": false,
			    "locationId": 1838524, "locationName": "부산광역시, 대한민국"
			  }
			}
			""";

		/**
		 * 운영은 Dockerfile 과 docker-compose.prod.yml 이 켜는 prod 프로필로 돈다. 프로필별 yml 에서 spring.jackson 설정을 바꿔
		 * 모르는 필드를 거절하게 되면 그 프로필 줄이 실패하도록, src/main/resources 의 세 설정(기본, dev, prod)을 모두 돌린다.
		 */
		@ParameterizedTest(name = "[{index}] 프로필 {0}")
		@DisplayName("애플리케이션 설정의 ObjectMapper 는 모르는 필드를 무시하고 남은 필드를 그대로 읽는다")
		@ValueSource(strings = {"default", "dev", "prod"})
		void ignoresUnreadFieldsAndKeepsTheRest(String profile) {
			// given: 그 프로필의 yml 에 있는 spring.jackson 설정까지 반영한, 컨트롤러가 쓰는 것과 같은 ObjectMapper
			ApplicationContextRunner applicationJackson = new ApplicationContextRunner()
				.withPropertyValues("spring.profiles.active=" + profile)
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

			applicationJackson.run(context -> {
				// when
				CompatibilityAnalysisRequest request = context.getBean(ObjectMapper.class)
					.readValue(REQUEST_BODY, CompatibilityAnalysisRequest.class);

				// then
				assertThat(request.getPerson1())
					.extracting(ManseryeokCreateRequest::getName, ManseryeokCreateRequest::getGender,
						ManseryeokCreateRequest::getCalendar, ManseryeokCreateRequest::getLeapMonth,
						ManseryeokCreateRequest::getBirthday, ManseryeokCreateRequest::getBirthtime)
					.containsExactly("김태우", "MALE", "S", false, "1995/05/05", "12:00");
				assertThat(request.getPerson2())
					.extracting(ManseryeokCreateRequest::getName, ManseryeokCreateRequest::getGender,
						ManseryeokCreateRequest::getCalendar, ManseryeokCreateRequest::getLeapMonth,
						ManseryeokCreateRequest::getBirthday, ManseryeokCreateRequest::getBirthtime)
					.containsExactly("이영희", "FEMALE", "L", true, "1996-02-29", "");
			});
		}
	}
}
