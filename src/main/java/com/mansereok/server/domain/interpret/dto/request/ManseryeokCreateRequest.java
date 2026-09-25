package com.mansereok.server.domain.interpret.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
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
	private String gender;          // "MALE" or "FEMALE" (M/F also accepted)
	private String calendar;        // "S" (S=양력, L=음력)
	private Boolean leapMonth;      // 음력 윤달 여부 (true=윤달, false=평달)
	private String birthday;        // "YYYY/MM/DD"
	private String birthtime;       // "12:00"
	private boolean hmUnsure;       // 시간 모름 or 야자시/조자시
	private int day;                // 2
	private int hour;                // 12
	private int locationId = 1835847; // 기본값: 서울 특별시, 프론트엔드가 GeoNames ID 를 전달해야함 .
	@JsonProperty("locationName")
	private String locationName;      // " 서울특별시, 대한민국"
	private boolean midnightAdjust;   // 자정 보정 기능
	private int min;                // 분
	private int month;                // 월
	private int year;                // 생년월일

	public static ManseryeokCreateRequest from(ManseryeokCreateRequest request) {
		ManseryeokCreateRequest apiRequest = new ManseryeokCreateRequest();
		apiRequest.name = request.getName();
		apiRequest.gender = request.getGender();
		apiRequest.calendar = request.getCalendar();
		apiRequest.leapMonth = request.getLeapMonth();
		apiRequest.birthday = request.getBirthday();
		apiRequest.birthtime = request.getBirthtime();
		apiRequest.hmUnsure = request.isHmUnsure();
		apiRequest.day = request.getDay();
		apiRequest.hour = request.getHour();
		apiRequest.locationId = request.getLocationId();
		apiRequest.locationName = request.getLocationName();
		apiRequest.midnightAdjust = request.isMidnightAdjust();
		apiRequest.min = request.getMin();
		apiRequest.month = request.getMonth();
		apiRequest.year = request.getYear();
		return apiRequest;
	}
}
