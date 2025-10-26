package com.mansereok.server.service.request;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;

@Data
public class ManseCompatibilityAnalysisRequest {

	private PersonInfo person1;

	private PersonInfo person2;

	private Long paymentId;

	@Data
	public static class PersonInfo {

		private String name;
		private LocalDate solarDate;
		private LocalTime solarTime;
		private String gender;
		private Boolean isLunar;
	}
}
