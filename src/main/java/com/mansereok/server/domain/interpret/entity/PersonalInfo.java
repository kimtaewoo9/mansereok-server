package com.mansereok.server.domain.interpret.entity;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Table(name = "personal_info")
@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalInfo {

	// 사용자 정보
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String name;
	@Enumerated(EnumType.STRING)
	private Gender gender;

	// 생년월일시, 양력, 음력 정보
	private String birthDate; // YYYY/MM/DD
	private String birthTime; // HH:mm
	private boolean isTimeUnknown; // 시간 모름 여부
	private CalendarType calendarType;

	// 출생지 정보 (태어난 도시 모를 경우 기본값 서울 특별시)
	private String city;
	private String locationId; // 기본값: 1835847 (서울 특별시)

	// 자정 보정 여부
	private boolean midnightAdjust; // 자정 보정 여부

	// 생성 시간
	private LocalDateTime createdAt;

	public static PersonalInfo from(ManseryeokCreateRequest request) {
		PersonalInfo personalInfo = new PersonalInfo();
		personalInfo.name = request.getName();
		personalInfo.gender = Gender.fromCode(request.getGender());
		personalInfo.birthDate = request.getBirthday();
		personalInfo.birthTime = request.getBirthtime();
		personalInfo.isTimeUnknown = request.isHmUnsure();
		personalInfo.calendarType = CalendarType.fromCode(request.getCalendar());
		personalInfo.city = request.getLocationName().trim(); // 공백 제거
		personalInfo.locationId = String.valueOf(request.getLocationId());
		personalInfo.midnightAdjust = request.isMidnightAdjust();
		personalInfo.createdAt = LocalDateTime.now();
		return personalInfo;
	}
}
