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

		// 2. 음력 여부 변환
		boolean isLunar = "L".equalsIgnoreCase(request.getCalendar());

		// 3. 시간 파싱 및 [자정 보정 로직 추가]
		String safeTime = request.getBirthtime();
		if ("00:00".equals(safeTime)) {
			safeTime = "00:01";
		}

		return new ManseryeokCalculationRequest(
			request.getName(),
			LocalDate.parse(cleanDate),
			LocalTime.parse(safeTime), // 수정된 safeTime 사용
			request.getGender(),
			isLunar
		);
	}
}
