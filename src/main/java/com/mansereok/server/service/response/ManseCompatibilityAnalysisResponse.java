package com.mansereok.server.service.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManseCompatibilityAnalysisResponse {

	private Long resultId;

	private String person1Name;

	private String person1Ilgan;

	private String person2Name;

	private String person2Ilgan;

	private String interpretation;

	// TODO: 궁합 점수 카테고리 세분화.
	private Integer compatibilityScore; // 궁합 점수(0-100)
}
