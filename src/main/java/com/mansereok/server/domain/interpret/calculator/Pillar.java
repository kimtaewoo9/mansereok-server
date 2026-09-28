package com.mansereok.server.domain.interpret.calculator;

import java.util.Optional;

/**
 * 사주의 네 기둥(년주·월주·일주·시주). 신살 계산 결과의 기둥 이름과 프롬프트가 그 이름을 읽는 곳이 이 라벨 하나를 쓴다.
 *
 * <p>선언 순서가 년·월·일·시라 EnumMap 이나 values() 를 돌면 늘 이 순서로 나온다.
 */
public enum Pillar {
	YEAR("년주"),
	MONTH("월주"),
	DAY("일주"),
	TIME("시주");

	private final String label;

	Pillar(String label) {
		this.label = label;
	}

	/**
	 * 라벨("년주")로 기둥을 찾는다. 응답의 신살 목록은 이 라벨을 키로 쓰는 Map 이라, 모르는 키가 와도 프롬프트 조립이 멈추지 않게
	 * 예외 대신 빈 값을 돌려준다.
	 */
	public static Optional<Pillar> fromLabel(String label) {
		for (Pillar pillar : values()) {
			if (pillar.label.equals(label)) {
				return Optional.of(pillar);
			}
		}
		return Optional.empty();
	}

	/** 응답 JSON 의 신살 키와 프롬프트에 쓰는 이름. */
	public String label() {
		return label;
	}
}
