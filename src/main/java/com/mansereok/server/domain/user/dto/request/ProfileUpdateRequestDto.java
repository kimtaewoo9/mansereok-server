package com.mansereok.server.domain.user.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileUpdateRequestDto {

	private String name;
	private LocalDate birthDate;
	@JsonFormat(pattern = "HH:mm")
	private LocalTime birthTime;
	private String birthPlace;
	private String gender; // "MALE" or "FEMALE"

	private Boolean marketingAgreed;
}
