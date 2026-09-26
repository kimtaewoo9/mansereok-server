package com.mansereok.server.domain.interpret.client;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Responses API 의 reasoning.effort 에 넣을 수 있는 값. 설정(yml)에서는 high·medium 처럼 소문자로 적는다.
 * 목록에 없는 값을 적으면 애플리케이션이 뜰 때 바인딩 오류로 바로 멈춘다. 자유 문자열로 받으면 오타가 모든 호출의 400 으로만
 * 드러나기 때문이다.
 */
public enum ReasoningEffort {
	MINIMAL,
	LOW,
	MEDIUM,
	HIGH;

	/** 요청 본문에는 OpenAI 가 받는 소문자 이름으로 싣는다. */
	@JsonValue
	public String apiValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}
