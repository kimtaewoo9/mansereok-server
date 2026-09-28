package com.mansereok.server.domain.interpret.client;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Responses API 의 reasoning.effort 값. 설정(yml)에서는 high·medium 처럼 소문자로 적는다.
 * 목록에 없는 값을 적으면 애플리케이션이 뜰 때 바인딩 오류로 바로 멈춘다. 자유 문자열로 받으면 오타가 모든 호출의 400 으로만
 * 드러나기 때문이다.
 *
 * <p>받는 값은 모델마다 다르다. OpenAI 모델 문서 기준으로 gpt-5.4·gpt-5.2 는 none·low·medium·high·xhigh 를,
 * gpt-5 는 minimal·low·medium·high 를 받는다. 이 enum 은 두 목록을 합친 값을 담아 오타만 막는다.
 * 모델 문서에 없는 값(예: gpt-5.4 에 minimal)을 고르면 기동은 되지만 호출에서 거절될 수 있으니, 티어의 모델이나
 * 추론 강도를 바꿀 때 그 모델 문서의 목록과 맞춰 본다.
 */
public enum ReasoningEffort {
	NONE,
	MINIMAL,
	LOW,
	MEDIUM,
	HIGH,
	XHIGH;

	/** 요청 본문에는 OpenAI 가 받는 소문자 이름으로 싣는다. */
	@JsonValue
	public String apiValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}
