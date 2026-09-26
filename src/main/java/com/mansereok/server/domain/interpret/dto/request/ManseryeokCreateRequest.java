package com.mansereok.server.domain.interpret.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManseryeokCreateRequest {

	// 무료 궁합 경로도 이 이름을 그대로 LLM 프롬프트에 넣는다.
	// 유료 경로(ManseCompatibilityAnalysisRequest.PersonInfo)와 같은 규칙을 걸어
	// 빈 이름이 비동기 처리 중 예외가 아니라 컨트롤러 입구에서 400 으로 끝나게 한다.
	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = 30, message = "이름은 30자를 넘을 수 없습니다.")
	private String name;            //
	// 생년월일과 성별은 만세력 계산에 꼭 필요하므로 비어 있거나 형식이 틀리면 컨트롤러 입구에서 400 으로 끝낸다.
	@NotBlank(message = "성별은 필수입니다.")
	private String gender;          // "MALE" or "FEMALE" (M/F also accepted)
	private String calendar;        // "S" (S=양력, L=음력)
	private Boolean leapMonth;      // 음력 윤달 여부 (true=윤달, false=평달)
	@NotBlank(message = "생년월일은 필수입니다.")
	@Pattern(regexp = "\\d{4}(/\\d{2}/\\d{2}|-\\d{2}-\\d{2})", message = "생년월일은 YYYY/MM/DD 형식이어야 합니다.")
	private String birthday;        // "YYYY/MM/DD"
	private String birthtime;       // "12:00"
}
