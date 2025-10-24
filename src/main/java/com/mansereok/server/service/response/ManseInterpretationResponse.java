package com.mansereok.server.service.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManseInterpretationResponse {

	private Long resultId;

	private String name;

	@Schema(description = "사주의 핵심, 본인을 나타내는 글자 (일간)", example = "임수(壬水)")
	private String ilgan;

	private String interpretation;
}
