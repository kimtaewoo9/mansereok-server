package com.mansereok.server.domain.interpret.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
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

	public LocalTime getSolarTime() {
		return solarTime != null ? solarTime : LocalTime.of(12, 0);
	}

	public static ManseryeokCalculationRequest from(ManseryeokCreateRequest request) {
		// 1. 날짜 포맷 처리 (YYYY/MM/DD -> YYYY-MM-DD)
		String cleanDate = request.getBirthday().replace("/", "-");

		// 2. 음력 여부 변환 ("L"이면 true, "S"면 false)
		boolean isLunar = "L".equalsIgnoreCase(request.getCalendar());

		return new ManseryeokCalculationRequest(
			request.getName(),
			LocalDate.parse(cleanDate), // 필요시 DateTimeFormatter 지정 가능
			LocalTime.parse(request.getBirthtime()),
			request.getGender(),
			isLunar
		);
	}
}
