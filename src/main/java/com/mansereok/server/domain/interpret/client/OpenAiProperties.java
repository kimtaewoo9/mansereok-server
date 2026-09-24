package com.mansereok.server.domain.interpret.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 호출 설정. 모델 티어까지 여기에 모아 두어, 호출부에 모델명·토큰 상한이 리터럴로 흩어지지 않게 한다.
 * yml 에 키가 없어도 기본값으로 동작한다(필수는 openai.api.key 하나뿐이다).
 */
@ConfigurationProperties(prefix = "openai.api")
public record OpenAiProperties(
	String key,
	String baseUrl,
	int connectTimeoutMs,
	int readTimeoutMs,
	int maxAttempts,
	long backoffDelayMs,
	double backoffMultiplier,
	ModelTier primary,
	ModelTier light,
	ModelTier fallback
) {

	private static final String DEFAULT_BASE_URL = "https://api.openai.com";
	private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
	private static final int DEFAULT_READ_TIMEOUT_MS = 180_000;
	private static final int DEFAULT_MAX_ATTEMPTS = 3;
	private static final long DEFAULT_BACKOFF_DELAY_MS = 2_000L;
	private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

	public OpenAiProperties {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("openai.api.key 는 필수입니다.");
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = DEFAULT_BASE_URL;
		}
		if (connectTimeoutMs <= 0) {
			connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
		}
		if (readTimeoutMs <= 0) {
			readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
		}
		if (maxAttempts <= 0) {
			maxAttempts = DEFAULT_MAX_ATTEMPTS;
		}
		if (backoffDelayMs <= 0) {
			backoffDelayMs = DEFAULT_BACKOFF_DELAY_MS;
		}
		if (backoffMultiplier < 1.0) {
			backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
		}
		if (primary == null) {
			primary = ModelTier.defaultPrimary();
		}
		if (light == null) {
			light = ModelTier.defaultLight();
		}
		if (fallback == null) {
			fallback = ModelTier.defaultFallback();
		}
	}

	/**
	 * 모델 티어 하나의 설정. 유료 단일·유료 궁합은 primary, 무료 단일은 light,
	 * 재시도 소진 뒤 마지막 한 번은 fallback 을 쓴다.
	 */
	public record ModelTier(
		String model,
		int maxOutputTokens,
		String reasoningEffort,
		String verbosity
	) {

		private static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;
		private static final String DEFAULT_LEVEL = "medium";

		public ModelTier {
			if (model == null || model.isBlank()) {
				throw new IllegalArgumentException("모델 티어의 model 은 비어 있을 수 없습니다.");
			}
			if (maxOutputTokens <= 0) {
				maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
			}
			if (reasoningEffort == null || reasoningEffort.isBlank()) {
				reasoningEffort = DEFAULT_LEVEL;
			}
			if (verbosity == null || verbosity.isBlank()) {
				verbosity = DEFAULT_LEVEL;
			}
		}

		public static ModelTier defaultPrimary() {
			return new ModelTier("gpt-5.4", 32_768, "high", "high");
		}

		public static ModelTier defaultLight() {
			return new ModelTier("gpt-5-mini", 8_192, "medium", "medium");
		}

		public static ModelTier defaultFallback() {
			return new ModelTier("gpt-5.2", 32_768, "medium", "medium");
		}
	}
}
