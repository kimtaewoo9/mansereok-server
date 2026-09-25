package com.mansereok.server.domain.interpret.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManseInterpretationRequest {

	// 이름과 작품명은 그대로 LLM 프롬프트에 들어가므로 컨트롤러 입구에서 길이부터 막는다.
	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = 30, message = "이름은 30자를 넘을 수 없습니다.")
	private String name;

	@NotNull(message = "생년월일은 필수입니다.")
	private LocalDate solarDate;

	private LocalTime solarTime;

	@NotNull(message = "성별은 필수입니다.")
	private String gender;

	private Boolean isLunar; // 양력인지 음력인지 입력 .
	private Boolean leapMonth; // 음력인 경우 윤달 여부

	@Size(max = 60, message = "작품명은 60자를 넘을 수 없습니다.")
	private String sourceTitle; // 애니명

	private Long paymentId;
}
