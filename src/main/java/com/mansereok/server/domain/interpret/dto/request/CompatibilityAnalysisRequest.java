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

	// 궁합 분석 타입 (연인, 부부, 친구, 사업파트너 등)
	private CompatibilityType compatibilityType = CompatibilityType.ROMANTIC;

	public enum CompatibilityType {
		ROMANTIC("연인"),
		MARRIAGE("결혼"),
		FRIENDSHIP("친구"),
		BUSINESS("사업파트너"),
		FAMILY("가족");

		private final String description;

		CompatibilityType(String description) {
			this.description = description;
		}

		public String getDescription() {
			return description;
		}
	}
}
