package com.mansereok.server.domain.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Gender {
	MALE("M", "남자"),
	FEMALE("F", "여자");

	private final String code;
	private final String description;

	public static Gender fromCode(String code) {
		for (Gender gender : values()) {
			if (gender.getCode().equals(code)) {
				return gender;
			}
		}
		throw new IllegalArgumentException("Unknown gender: " + code);
	}
}
