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
}
