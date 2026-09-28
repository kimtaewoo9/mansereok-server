package com.mansereok.server.domain.interpret.calculator;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 사주 강약 판정(신강·중화·신약). 나의 힘 비율(나의 점수 / 전체 점수)로 정하고, 용신 방향과 프롬프트 지침이 모두 이 값 하나로 갈린다.
 *
 * <p>응답 JSON 과 프롬프트에는 {@link #label()} 이 나간다.
 */
public enum Strength {
	STRONG("신강(身强)"),
	BALANCED("중화(中和)"),
	WEAK("신약(身弱)");

	// 나의 힘 비율이 이 값 이상이면 신강
	private static final double STRONG_RATIO_MIN = 0.58;
	// 나의 힘 비율이 이 값 이하면 신약. 두 값 사이는 중화다
	private static final double WEAK_RATIO_MAX = 0.42;

	private final String label;

	Strength(String label) {
		this.label = label;
	}

	/**
	 * 나의 힘 비율로 강약을 정한다. 경계값(0.58, 0.42)은 신강·신약 쪽에 든다.
	 */
	public static Strength of(double ratio) {
		if (ratio >= STRONG_RATIO_MIN) {
			return STRONG;
		}
		if (ratio <= WEAK_RATIO_MAX) {
			return WEAK;
		}
		return BALANCED;
	}

	/** 화면과 프롬프트에 쓰는 이름. JSON 에도 이 값이 나가 enum 으로 바꾸기 전과 응답이 같다. */
	@JsonValue
	public String label() {
		return label;
	}
}
