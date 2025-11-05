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

	public ProfileResponseDto(User user) {
		this.name = user.getName();
		this.email = user.getEmail();
		this.birthDate = user.getBirthDate();
		this.gender = user.getGender() != null ? user.getGender().name() : null;
	}
}
