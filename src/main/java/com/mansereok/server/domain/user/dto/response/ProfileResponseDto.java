package com.mansereok.server.domain.user.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.user.entity.RegistrationType;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;

@Getter
public class ProfileResponseDto {

	private final String name;
	private final String email;
	private final LocalDate birthDate;
	@JsonFormat(pattern = "HH:mm")
	private final LocalTime birthTime;
	private final String birthPlace;
	private final String gender;
	private final boolean marketingAgreed;

	/**
	 * 이름·생년월일·성별 중 비어 있는 것이 있어 추가 정보를 받아야 하는가. 프론트와 맞춘 JSON 키는 예전 그대로 newUser 다.
	 *
	 * <p>소셜 로그인 응답의 isNewUser(이번 요청에서 가입했는가)와는 뜻이 다르다. 예전에 가입했지만 생년월일을 넣지 않은 회원은 로그인
	 * 응답에서는 false, 이 값은 true 다.
	 */
	@JsonProperty("newUser")
	private final boolean profileIncomplete;

	private final RegistrationType registrationType;

	public ProfileResponseDto(User user) {
		this.name = user.getName();
		this.email = user.getEmail();
		this.birthDate = user.getBirthDate();
		this.birthTime = user.getBirthTime();
		this.birthPlace = user.getBirthPlace();
		this.gender = user.getGender() != null ? user.getGender().name() : null;
		this.marketingAgreed = user.isMarketingAgreed();
		this.profileIncomplete = user.isProfileIncomplete();
		this.registrationType = determineRegistrationType(user.getSocialType());
	}

	private static RegistrationType determineRegistrationType(SocialType socialType) {
		if (socialType == null) {
			return RegistrationType.GENERAL; // 일반 회원
		} else {
			return RegistrationType.SOCIAL;
		}
	}
}
