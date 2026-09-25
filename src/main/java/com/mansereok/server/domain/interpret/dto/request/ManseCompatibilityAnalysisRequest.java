package com.mansereok.server.domain.interpret.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;

@Data
public class ManseCompatibilityAnalysisRequest {

	@NotNull(message = "첫 번째 사람 정보는 필수입니다.")
	@Valid
	private PersonInfo person1;

	@NotNull(message = "두 번째 사람 정보는 필수입니다.")
	@Valid
	private PersonInfo person2;

	private Long paymentId;

	@Data
	public static class PersonInfo {

		// 이름과 작품명은 그대로 LLM 프롬프트에 들어가므로 컨트롤러 입구에서 길이부터 막는다.
		@NotBlank(message = "이름은 필수입니다.")
		@Size(max = 30, message = "이름은 30자를 넘을 수 없습니다.")
		private String name;

		@NotNull(message = "생년월일은 필수입니다.")
		private LocalDate solarDate;

		private LocalTime solarTime;

		@NotNull(message = "성별은 필수입니다.")
		private String gender;

		private Boolean isLunar;
		private Boolean leapMonth;

		@Size(max = 60, message = "작품명은 60자를 넘을 수 없습니다.")
		private String sourceTitle;
	}
}
