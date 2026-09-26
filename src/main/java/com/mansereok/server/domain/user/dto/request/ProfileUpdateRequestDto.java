package com.mansereok.server.domain.user.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mansereok.server.domain.auth.dto.request.NameRule;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.ProfileChange;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 프로필 수정 요청. 보내지 않은(null) 항목은 바꾸지 않는다. 필수값과 태어난 장소 규칙은 User.updateProfile 이 검사한다.
 */
@Getter
@Setter
public class ProfileUpdateRequestDto {

	@Size(max = NameRule.MAX_LENGTH, message = NameRule.TOO_LONG_MESSAGE)
	private String name;
	private LocalDate birthDate;
	@JsonFormat(pattern = "HH:mm")
	private LocalTime birthTime;
	private String birthPlace;
	// 열거 상수 이름("MALE", "FEMALE")만 받는다. 다른 값("M", "male")은 본문을 읽을 때 400 INVALID_REQUEST_BODY 가 된다.
	private Gender gender;

	private Boolean marketingAgreed;

	public ProfileChange toProfileChange() {
		return new ProfileChange(name, birthDate, birthTime, birthPlace, gender, marketingAgreed);
	}
}
