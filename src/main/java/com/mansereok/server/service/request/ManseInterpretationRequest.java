package com.mansereok.server.service.request;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data // 이 어노테이션이 getSolarDate() 등을 자동으로 만들어줍니다.
@NoArgsConstructor
@AllArgsConstructor
public class ManseInterpretationRequest {

	private String name;
	private LocalDate solarDate;
	private LocalTime solarTime;
	private String gender;
	private Boolean isLunar;

	private Long paymentId;
}
