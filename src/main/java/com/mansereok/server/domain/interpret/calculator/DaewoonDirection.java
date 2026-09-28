package com.mansereok.server.domain.interpret.calculator;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 대운이 흐르는 방향. 순행은 월주 다음 간지부터 60갑자를 앞으로, 역행은 뒤로 센다. 대운수도 순행이면 출생 뒤 첫 절입까지, 역행이면
 * 출생 전 마지막 절입부터 날짜를 센다.
 *
 * <p>만세력 계산이 성별과 년간 음양으로 한 번 정하고(양남음녀 순행, 음남양녀 역행), 프롬프트는 그 값을 그대로 읽는다. 응답 JSON 과
 * 프롬프트에는 {@link #label()} 이 나간다.
 */
public enum DaewoonDirection {
	FORWARD("순행"),
	BACKWARD("역행");

	private final String label;

	DaewoonDirection(String label) {
		this.label = label;
	}

	/** 화면과 프롬프트에 쓰는 이름. */
	@JsonValue
	public String label() {
		return label;
	}
}
