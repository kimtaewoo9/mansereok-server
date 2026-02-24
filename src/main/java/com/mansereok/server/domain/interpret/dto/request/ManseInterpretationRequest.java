package com.mansereok.server.domain.interpret.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManseInterpretationRequest {

	private String name;
	private LocalDate solarDate;
	private LocalTime solarTime;
	private String gender;
	private Boolean isLunar; // 양력인지 음력인지 입력 .
	private Boolean leapMonth; // 음력인 경우 윤달 여부

	private String sourceTitle; // 애니명

	private Long paymentId;
}
