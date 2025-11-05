package com.mansereok.server.domain.interpret.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ManseryeokCreateRequest {

	private String name;            //
	private String gender;          // "M" (M/F)
	private String calendar;        // "S" (S=양력, L=음력)
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
