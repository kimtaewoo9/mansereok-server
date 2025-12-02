package com.mansereok.server.domain.user.dto.response;

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

	private final boolean isNewUser; // 추가 정보를 받았는가

	public ProfileResponseDto(User user) {
		this.name = user.getName();
		this.email = user.getEmail();
		this.birthDate = user.getBirthDate();
		this.gender = user.getGender() != null ? user.getGender().name() : null;
		this.marketingAgreed = user.isMarketingAgreed();

		// getBirthDate 나 Gender가 없으면 true를 반환
		this.isNewUser = (user.getBirthDate() == null || user.getGender() == null);
	}
}
