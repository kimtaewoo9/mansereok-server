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
	private static final int KEY_VISIBLE_PREFIX_LENGTH = 3;

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
	 * record 가 자동으로 만드는 toString 은 key 를 그대로 찍으므로, key 는 앞 3글자와 길이만 남긴다.
	 * 나머지 값은 설정 확인에 필요하므로 그대로 보여 준다.
	 */
	@Override
	public String toString() {
		return "OpenAiProperties["
			+ "key=" + maskedKey()
			+ ", baseUrl=" + baseUrl
			+ ", connectTimeoutMs=" + connectTimeoutMs
			+ ", readTimeoutMs=" + readTimeoutMs
			+ ", maxAttempts=" + maxAttempts
			+ ", backoffDelayMs=" + backoffDelayMs
			+ ", backoffMultiplier=" + backoffMultiplier
			+ ", primary=" + primary
			+ ", light=" + light
			+ ", fallback=" + fallback
			+ "]";
	}

	private String maskedKey() {
		// 3글자 이하인 key 는 앞 3글자만 보여 줘도 전부 드러나므로 길이만 남긴다.
		String visiblePrefix = key.length() > KEY_VISIBLE_PREFIX_LENGTH
			? key.substring(0, KEY_VISIBLE_PREFIX_LENGTH)
			: "";
		return visiblePrefix + "***(" + key.length() + "자)";
	}

	/**
	 * 모델 티어 하나의 설정. 유료 단일·유료 궁합은 primary, 무료 단일은 light,
	 * 재시도 소진 뒤 마지막 한 번은 fallback 을 쓴다.
	 * reasoning-effort 와 verbosity 는 enum 이라, 목록에 없는 값을 적으면 기동할 때 실패한다. 적지 않으면 medium 이다.
	 */
	public record ModelTier(
		String model,
		int maxOutputTokens,
		ReasoningEffort reasoningEffort,
		Verbosity verbosity
	) {

		private static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;

		public ModelTier {
			if (model == null || model.isBlank()) {
				throw new IllegalArgumentException("모델 티어의 model 은 비어 있을 수 없습니다.");
			}
			if (maxOutputTokens <= 0) {
				maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
			}
			if (reasoningEffort == null) {
				reasoningEffort = ReasoningEffort.MEDIUM;
			}
			if (verbosity == null) {
				verbosity = Verbosity.MEDIUM;
			}
		}

		public static ModelTier defaultPrimary() {
			return new ModelTier("gpt-5.4", 32_768, ReasoningEffort.HIGH, Verbosity.HIGH);
		}

		public static ModelTier defaultLight() {
			return new ModelTier("gpt-5-mini", 8_192, ReasoningEffort.MEDIUM, Verbosity.MEDIUM);
		}

		public static ModelTier defaultFallback() {
			return new ModelTier("gpt-5.2", 32_768, ReasoningEffort.MEDIUM, Verbosity.MEDIUM);
		}
	}
}
