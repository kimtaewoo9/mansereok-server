package com.mansereok.server.domain.user.dto.response;

import com.mansereok.server.domain.user.entity.RegistrationType;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import java.time.LocalDate;
import lombok.Getter;

@Getter
public class ProfileResponseDto {

	private final String name;
	private final String email;
	private final LocalDate birthDate;
	private final String gender;
	private final boolean marketingAgreed;

	private final boolean isNewUser; // 추가 정보를 받아야하는가.

	private final RegistrationType registrationType;

	public ProfileResponseDto(User user) {
		this.name = user.getName();
		this.email = user.getEmail();
		this.birthDate = user.getBirthDate();
		this.gender = user.getGender() != null ? user.getGender().name() : null;
		this.marketingAgreed = user.isMarketingAgreed();
		
		this.isNewUser = (user.getBirthDate() == null || user.getGender() == null);
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
