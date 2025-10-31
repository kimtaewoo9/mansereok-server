package com.mansereok.server.service.response;

import com.mansereok.server.entity.Result;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class InterpretationResultResponse {

	private Long id;
	private String name;
	private LocalDate solarDate;
	private String gender;
	private Boolean isLunar;
	private String ilgan;
	private String interpretation;
	private String summary;
	private LocalDateTime createdAt;

	public static InterpretationResultResponse create(Result result) {
		InterpretationResultResponse response = new InterpretationResultResponse();
		response.id = result.getId();
		response.name = result.getName();
		response.solarDate = result.getSolarDate();
		response.gender = result.getGender();
		response.isLunar = result.getIsLunar();
		response.ilgan = result.getIlgan();
		response.interpretation = result.getInterpretation();
		response.createdAt = result.getCreatedAt();
		response.summary = result.getSummary();

		return response;
	}
}
