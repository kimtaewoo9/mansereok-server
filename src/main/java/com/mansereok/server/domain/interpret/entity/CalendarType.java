package com.mansereok.server.domain.interpret.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CalendarType {
	SOLAR("S", "양력"),
	LUNAR("L", "음력");

	private final String code;
	private final String description;

	public static CalendarType fromCode(String code) {
		for (CalendarType type : values()) {
			if (type.getCode().equals(code)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown calendar type: " + code);
	}
}
