package com.mansereok.server.domain.interpret.dto.response;

import com.mansereok.server.domain.interpret.entity.PersonalInfo;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ManseryeokInterpretationResponse {

	private PersonalInfo personalInfo;
	private final String ilgan; // 일간 ex) 임수
	private String interpretation;
}
