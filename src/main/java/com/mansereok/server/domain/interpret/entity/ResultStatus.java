package com.mansereok.server.domain.interpret.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResultStatus {

	INPUT_REQUIRED("정보 입력 대기"),
	PROCESSING("사주 해석 진행 중"),
	COMPLETED("해석 완료");

	private final String description;
}
