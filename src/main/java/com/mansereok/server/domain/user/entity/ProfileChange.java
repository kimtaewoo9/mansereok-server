package com.mansereok.server.domain.user.entity;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 프로필 수정 요청에 담긴 값. null 인 항목은 바꾸지 않는다. 규칙은 {@link User#updateProfile(ProfileChange)} 가 정한다.
 *
 * @param name            이름. 공백만 있으면 바꾸지 않는다.
 * @param birthDate       생년월일
 * @param birthTime       태어난 시각
 * @param birthPlace      태어난 장소. 앞뒤 공백을 떼어 저장하며, 공백만 있으면 거절한다.
 * @param gender          성별
 * @param marketingAgreed 마케팅 수신 동의
 */
public record ProfileChange(
	String name,
	LocalDate birthDate,
	LocalTime birthTime,
	String birthPlace,
	Gender gender,
	Boolean marketingAgreed
) {

}
