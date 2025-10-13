package com.mansereok.server.service.request;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileUpdateRequestDto {

	private String name;
	private LocalDate birthDate;
	private String gender; // "MALE" or "FEMALE"
}
