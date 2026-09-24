package com.mansereok.server.domain.interpret.client;

/**
 * 테스트에서 OpenAiProperties 를 짧게 만들기 위한 헬퍼.
 * 티어는 null 로 넘겨 compact 생성자의 기본값(primary/light/fallback)을 그대로 쓴다.
 */
public final class TestOpenAiProperties {

	private TestOpenAiProperties() {
	}

	public static OpenAiProperties defaults() {
		return of(3, 2_000L, 2.0);
	}

	public static OpenAiProperties of(int maxAttempts, long backoffDelayMs,
		double backoffMultiplier) {
		return new OpenAiProperties(
			"test-api-key",
			"https://api.openai.com",
			1_000,
			2_000,
			maxAttempts,
			backoffDelayMs,
			backoffMultiplier,
			null,
			null,
			null
		);
	}
}
