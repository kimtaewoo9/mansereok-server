package com.mansereok.server.domain.interpret.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 만세력 계산 입력. /calculate 요청 본문이면서, 해석 요청 세 가지를 계산 입력으로 바꾼 결과다.
 *
 * <p>isLunar 와 leapMonth 가 둘 다 Boolean 이라 전체 인자 생성자로 만들면 둘의 자리가 바뀌어도 컴파일된다. 그래서 전체 인자
 * 생성자는 닫아 두고, 요청 타입마다 이름 있는 변환 메서드 from 을 둔다. 테스트처럼 값을 직접 채울 때는 builder 를 쓴다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ManseryeokCalculationRequest {

	private String name;

	@NotNull(message = "생년월일은 필수입니다.")
	private LocalDate solarDate;

	private LocalTime solarTime;

	@NotNull(message = "성별은 필수입니다.")
	private String gender;

	// 계산이 이 값으로 양력·음력 조회를 고르므로 비어 있으면 입구에서 400 으로 끝낸다.
	@NotNull(message = "양력·음력 여부는 필수입니다.")
	private Boolean isLunar;

	private Boolean leapMonth;

	/**
	 * 단일 해석 요청(유료·무료)을 계산 입력으로 바꾼다.
	 */
	public static ManseryeokCalculationRequest from(ManseInterpretationRequest request) {
		return ManseryeokCalculationRequest.builder()
			.name(request.getName())
			.solarDate(request.getSolarDate())
			.solarTime(request.getSolarTime())
			.gender(request.getGender())
			.isLunar(request.getIsLunar())
			.leapMonth(request.getLeapMonth())
			.build();
	}

	/**
	 * 유료 궁합 요청의 한 사람을 계산 입력으로 바꾼다.
	 */
	public static ManseryeokCalculationRequest from(ManseCompatibilityAnalysisRequest.PersonInfo person) {
		return ManseryeokCalculationRequest.builder()
			.name(person.getName())
			.solarDate(person.getSolarDate())
			.solarTime(person.getSolarTime())
			.gender(person.getGender())
			.isLunar(person.getIsLunar())
			.leapMonth(person.getLeapMonth())
			.build();
	}

	/**
	 * 무료 궁합 요청의 한 사람을 계산 입력으로 바꾼다. 입력이 잘못되면 IllegalArgumentException 이라 400 으로 나간다.
	 *
	 * <p>컨트롤러 입구의 @Valid 검증(ManseryeokCreateRequest 의 @Pattern)을 거친 값을 받는다. 이 변환은 앞뒤 공백과 섞인 구분자
	 * ("1995/05-05")도 받아 주므로 입구 규칙보다 느슨하다. 입구 검증 없이 부르는 곳을 새로 만들면 입구 규칙을 여기에도 옮겨야 한다.
	 *
	 * <p>오류 메시지에는 입력값을 넣지 않는다. GlobalExceptionHandler 가 메시지를 경고 로그와 응답 본문에 그대로 남기기 때문이다.
	 *
	 * @throws IllegalArgumentException 생년월일이 없거나 형식이 틀렸을 때, 달력 값이 S·L 이 아닐 때, 출생시간 형식이 틀렸을 때,
	 *                                  성별이 없거나 알 수 없는 값일 때
	 */
	public static ManseryeokCalculationRequest from(ManseryeokCreateRequest request) {
		return ManseryeokCalculationRequest.builder()
			.name(request.getName())
			.solarDate(parseBirthday(request.getBirthday()))
			.solarTime(parseBirthTime(request.getBirthtime()))
			.gender(normalizeGender(request.getGender()))
			.isLunar(isLunarCalendar(request.getCalendar()))
			.leapMonth(request.getLeapMonth())
			.build();
	}

	/**
	 * "YYYY/MM/DD" 나 "YYYY-MM-DD" 를 날짜로 바꾼다.
	 */
	private static LocalDate parseBirthday(String birthday) {
		if (birthday == null || birthday.isBlank()) {
			throw new IllegalArgumentException("생년월일(birthday)은 필수입니다.");
		}

		try {
			return LocalDate.parse(birthday.trim().replace("/", "-"));
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("생년월일은 YYYY/MM/DD 형식이어야 하고 실제로 있는 날짜여야 합니다.", e);
		}
	}

	/**
	 * 달력 값 "S"(양력)·"L"(음력)을 음력 여부로 바꾼다. 값이 없으면 양력으로 본다. 그 밖의 값을 양력으로 넘기면 음력 생일이 다른
	 * 사주로 계산되므로 거절한다.
	 */
	private static boolean isLunarCalendar(String calendar) {
		if (calendar == null || "S".equalsIgnoreCase(calendar)) {
			return false;
		}
		if ("L".equalsIgnoreCase(calendar)) {
			return true;
		}
		throw new IllegalArgumentException("달력(calendar)은 S(양력) 또는 L(음력)이어야 합니다: " + calendar);
	}

	private static LocalTime parseBirthTime(String birthtime) {
		if (birthtime == null || birthtime.isBlank()) {
			return null;
		}

		try {
			return LocalTime.parse(birthtime.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("지원하지 않는 출생시간 형식입니다.", e);
		}
	}

	private static String normalizeGender(String gender) {
		if (gender == null || gender.isBlank()) {
			throw new IllegalArgumentException("성별(gender)은 필수입니다.");
		}

		String normalized = gender.trim().toUpperCase();
		return switch (normalized) {
			case "MALE", "M" -> "MALE";
			case "FEMALE", "F" -> "FEMALE";
			default -> throw new IllegalArgumentException("지원하지 않는 성별 값입니다.");
		};
	}
}
