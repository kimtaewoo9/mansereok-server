package com.mansereok.server.domain.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RegistrationType {
	GENERAL("일반 회원가입"),
	SOCIAL("소셜 로그인");

	private final String description;
}
