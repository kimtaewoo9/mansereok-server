package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 해석 요청 세 가지를 만세력 계산 입력으로 바꾸는 변환과, /calculate 요청 본문 검증을 확인한다.
 *
 * <p>계산 입력의 isLunar 와 leapMonth 는 둘 다 Boolean 이라 자리가 바뀌어도 컴파일된다. 그래서 변환마다 두 값을 서로 다르게 넣고
 * 제자리에 들어갔는지 본다.
 */
@DisplayName("ManseryeokCalculationRequest - 계산 입력 변환")
class ManseryeokCalculationRequestTest {

	@Nested
	@DisplayName("무료 궁합 입력(ManseryeokCreateRequest)을 바꾸면")
	class FromFreeCompatibilityPerson {

		@ParameterizedTest(name = "[{index}] {0} / 달력 {1} / 윤달 {2} / 시각 \"{3}\" / 성별 {4}")
		@DisplayName("생년월일·달력·윤달·출생시간·성별을 계산 입력 값으로 바꾼다")
		@CsvSource(textBlock = """
			# 생년월일,   달력, 윤달,  출생시간, 성별,   → 양력 날짜,  음력,  윤달,  출생시간, 성별
			1995/05/05, S,    ,      12:00,   MALE,   1995-05-05, false, ,      12:00,   MALE
			1995-05-05, s,    ,      09:30,   M,      1995-05-05, false, ,      09:30,   MALE
			1995/05/05, ,     ,      12:00,   f,      1995-05-05, false, ,      12:00,   FEMALE
			1995/05/05, L,    true,  12:00,   FEMALE, 1995-05-05, true,  true,  12:00,   FEMALE
			1995/05/05, l,    false, ,        m,      1995-05-05, true,  false, ,        MALE
			1995/05/05, S,    ,      '',      MALE,   1995-05-05, false, ,      ,        MALE
			1995/05/05, S,    ,      '  ',    MALE,   1995-05-05, false, ,      ,        MALE
			""")
		void convertsValidInput(String birthday, String calendar, Boolean leapMonth, String birthtime,
			String gender, LocalDate expectedDate, boolean expectedLunar, Boolean expectedLeapMonth,
			LocalTime expectedTime, String expectedGender) {
			// given
			ManseryeokCreateRequest person = person(birthday, calendar, birthtime, gender);
			person.setLeapMonth(leapMonth);

			// when
			ManseryeokCalculationRequest converted = ManseryeokCalculationRequest.from(person);

			// then
			assertThat(converted.getName()).isEqualTo("김태우");
			assertThat(converted.getSolarDate()).as("양력 날짜").isEqualTo(expectedDate);
			assertThat(converted.getIsLunar()).as("음력 여부").isEqualTo(expectedLunar);
			assertThat(converted.getLeapMonth()).as("윤달 여부").isEqualTo(expectedLeapMonth);
			assertThat(converted.getSolarTime()).as("출생시간").isEqualTo(expectedTime);
			assertThat(converted.getGender()).as("성별").isEqualTo(expectedGender);
		}

		@ParameterizedTest(name = "[{index}] {0} / 달력 {1} / 시각 {2} / 성별 {3}")
		@DisplayName("입력이 잘못되면 400 으로 나가는 IllegalArgumentException 을 던진다")
		@CsvSource(textBlock = """
			# 생년월일,   달력,  출생시간, 성별, 메시지
			,           S,     12:00,   MALE, 생년월일(birthday)은 필수입니다.
			'  ',       S,     12:00,   MALE, 생년월일(birthday)은 필수입니다.
			1995/5/5,   S,     12:00,   MALE, 생년월일은 YYYY/MM/DD 형식의 있는 날짜여야 합니다.
			1990/13/01, S,     12:00,   MALE, 생년월일은 YYYY/MM/DD 형식의 있는 날짜여야 합니다.
			1990/02/30, S,     12:00,   MALE, 생년월일은 YYYY/MM/DD 형식의 있는 날짜여야 합니다.
			1995/05/05, LUNAR, 12:00,   MALE, 달력(calendar)은 S(양력) 또는 L(음력)이어야 합니다: LUNAR
			1995/05/05, SOLAR, 12:00,   MALE, 달력(calendar)은 S(양력) 또는 L(음력)이어야 합니다: SOLAR
			1995/05/05, S,     25:00,   MALE, 지원하지 않는 출생시간 형식입니다: 25:00
			1995/05/05, S,     12:00,   X,    지원하지 않는 성별 값입니다: X
			1995/05/05, S,     12:00,   ,     성별(gender)은 필수입니다.
			""")
		void rejectsInvalidInput(String birthday, String calendar, String birthtime, String gender,
			String expectedMessage) {
			// given
			ManseryeokCreateRequest person = person(birthday, calendar, birthtime, gender);

			// when & then
			assertThatThrownBy(() -> ManseryeokCalculationRequest.from(person))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(expectedMessage);
		}

		@Test
		@DisplayName("없는 날짜는 원인 예외를 담고, 메시지에는 입력한 생년월일을 넣지 않는다")
		void keepsParseFailureAsCauseWithoutBirthdayInMessage() {
			// given
			ManseryeokCreateRequest person = person("1990/13/01", "S", "12:00", "MALE");

			// when & then
			assertThatThrownBy(() -> ManseryeokCalculationRequest.from(person))
				.isInstanceOf(IllegalArgumentException.class)
				.hasCauseInstanceOf(DateTimeParseException.class)
				.message().doesNotContain("1990");
		}

		private ManseryeokCreateRequest person(String birthday, String calendar, String birthtime, String gender) {
			ManseryeokCreateRequest person = new ManseryeokCreateRequest();
			person.setName("김태우");
			person.setBirthday(birthday);
			person.setCalendar(calendar);
			person.setBirthtime(birthtime);
			person.setGender(gender);
			return person;
		}
	}

	@Nested
	@DisplayName("단일 해석 요청(ManseInterpretationRequest)을 바꾸면")
	class FromSingleRequest {

		@ParameterizedTest(name = "[{index}] 음력 {0}, 윤달 {1}")
		@DisplayName("이름·날짜·시각·성별을 그대로 옮기고 음력 여부와 윤달 여부를 제자리에 둔다")
		@CsvSource(textBlock = """
			# 음력,  윤달
			true,  false
			false, true
			""")
		void keepsEachFieldInPlace(boolean isLunar, boolean leapMonth) {
			// given
			ManseInterpretationRequest request = new ManseInterpretationRequest("김태우", LocalDate.of(1995, 5, 5),
				LocalTime.of(9, 30), "FEMALE", isLunar, leapMonth, "슬램덩크", 1L);

			// when
			ManseryeokCalculationRequest converted = ManseryeokCalculationRequest.from(request);

			// then
			assertThat(converted.getName()).isEqualTo("김태우");
			assertThat(converted.getSolarDate()).isEqualTo(LocalDate.of(1995, 5, 5));
			assertThat(converted.getSolarTime()).isEqualTo(LocalTime.of(9, 30));
			assertThat(converted.getGender()).isEqualTo("FEMALE");
			assertThat(converted.getIsLunar()).as("음력 여부").isEqualTo(isLunar);
			assertThat(converted.getLeapMonth()).as("윤달 여부").isEqualTo(leapMonth);
		}
	}

	@Nested
	@DisplayName("유료 궁합 요청의 한 사람(PersonInfo)을 바꾸면")
	class FromPaidCompatibilityPerson {

		@ParameterizedTest(name = "[{index}] 음력 {0}, 윤달 {1}")
		@DisplayName("이름·날짜·시각·성별을 그대로 옮기고 음력 여부와 윤달 여부를 제자리에 둔다")
		@CsvSource(textBlock = """
			# 음력,  윤달
			true,  false
			false, true
			""")
		void keepsEachFieldInPlace(boolean isLunar, boolean leapMonth) {
			// given
			ManseCompatibilityAnalysisRequest.PersonInfo person = new ManseCompatibilityAnalysisRequest.PersonInfo();
			person.setName("이영희");
			person.setSolarDate(LocalDate.of(1992, 11, 3));
			person.setSolarTime(LocalTime.of(21, 15));
			person.setGender("MALE");
			person.setIsLunar(isLunar);
			person.setLeapMonth(leapMonth);
			person.setSourceTitle("원피스");

			// when
			ManseryeokCalculationRequest converted = ManseryeokCalculationRequest.from(person);

			// then
			assertThat(converted.getName()).isEqualTo("이영희");
			assertThat(converted.getSolarDate()).isEqualTo(LocalDate.of(1992, 11, 3));
			assertThat(converted.getSolarTime()).isEqualTo(LocalTime.of(21, 15));
			assertThat(converted.getGender()).isEqualTo("MALE");
			assertThat(converted.getIsLunar()).as("음력 여부").isEqualTo(isLunar);
			assertThat(converted.getLeapMonth()).as("윤달 여부").isEqualTo(leapMonth);
		}
	}

	@Nested
	@DisplayName("/calculate 요청 본문으로 받으면")
	class AsCalculateRequestBody {

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

		@Test
		@DisplayName("생년월일·성별·양력음력 여부가 있으면 위반이 없다")
		void acceptsCompleteRequest() {
			// given
			ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
				.solarDate(LocalDate.of(1995, 5, 5)).gender("MALE").isLunar(false)
				.build();

			// when & then
			assertThat(violatedFields(request)).isEmpty();
		}

		@Test
		@DisplayName("생년월일·성별·양력음력 여부가 없으면 세 칸 모두 위반이 잡힌다")
		void rejectsMissingRequiredFields() {
			// given
			ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
				.name("김태우").solarTime(LocalTime.NOON)
				.build();

			// when & then
			assertThat(violatedFields(request)).containsExactlyInAnyOrder("solarDate", "gender", "isLunar");
		}

		private Set<String> violatedFields(ManseryeokCalculationRequest request) {
			return validator.validate(request).stream()
				.map(ConstraintViolation::getPropertyPath)
				.map(Object::toString)
				.collect(Collectors.toSet());
		}
	}
}
