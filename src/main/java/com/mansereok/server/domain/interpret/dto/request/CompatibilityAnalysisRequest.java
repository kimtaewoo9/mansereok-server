package com.mansereok.server.domain.interpret.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompatibilityAnalysisRequest {

	@NotNull(message = "첫 번째 사람의 정보는 필수입니다")
	@Valid
	private ManseryeokCreateRequest person1;

	@NotNull(message = "두 번째 사람의 정보는 필수입니다")
	@Valid
	private ManseryeokCreateRequest person2;
}
