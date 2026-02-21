package com.mansereok.server.domain.interpret.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManseryeokCalculationRequest {

	private String name;
	private LocalDate solarDate;
	private LocalTime solarTime;
	private String gender;
	private Boolean isLunar;
	private Boolean leapMonth;

	public LocalTime getSolarTimeOrDefault() {
		return solarTime != null ? solarTime : LocalTime.NOON;
	}

	public static ManseryeokCalculationRequest from(ManseryeokCreateRequest request) {
		// 1. 날짜 포맷 처리 (YYYY/MM/DD -> YYYY-MM-DD)
		String cleanDate = request.getBirthday().replace("/", "-");

		// 2. 음력 여부 변환
		boolean isLunar = "L".equalsIgnoreCase(request.getCalendar());

		return new ManseryeokCalculationRequest(
			request.getName(),
			LocalDate.parse(cleanDate),
			parseBirthTime(request.getBirthtime()),
			normalizeGender(request.getGender()),
			isLunar,
			request.getLeapMonth()
		);
	}

	private static LocalTime parseBirthTime(String birthtime) {
		if (birthtime == null || birthtime.isBlank()) {
			return null;
		}

		try {
			return LocalTime.parse(birthtime.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("지원하지 않는 출생시간 형식입니다: " + birthtime);
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
			default -> throw new IllegalArgumentException("지원하지 않는 성별 값입니다: " + gender);
		};
	}
}
