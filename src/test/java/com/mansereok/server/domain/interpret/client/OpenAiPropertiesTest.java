package com.mansereok.server.domain.interpret.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OpenAiPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(TestConfig.class);

	@Test
	@DisplayName("키만 있으면 나머지는 기본값으로 채워진다")
	void fillsDefaults() {
		OpenAiProperties properties = new OpenAiProperties("sk-test", null, 0, 0, 0, 0L, 0.0,
			null, null, null);

		assertThat(properties.baseUrl()).isEqualTo("https://api.openai.com");
		assertThat(properties.connectTimeoutMs()).isEqualTo(10_000);
		assertThat(properties.readTimeoutMs()).isEqualTo(180_000);
		assertThat(properties.maxAttempts()).isEqualTo(3);
		assertThat(properties.backoffDelayMs()).isEqualTo(2_000L);
		assertThat(properties.backoffMultiplier()).isEqualTo(2.0);
	}

	@Test
	@DisplayName("key 가 없거나 비어 있으면 기동에 실패한다")
	void keyIsRequired() {
		assertThatThrownBy(
			() -> new OpenAiProperties(null, null, 0, 0, 0, 0L, 0.0, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("openai.api.key");

		assertThatThrownBy(
			() -> new OpenAiProperties("  ", null, 0, 0, 0, 0L, 0.0, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("openai.api.key");
	}

	@Test
	@DisplayName("모델 티어 기본값은 primary / light / fallback 세 가지다")
	void tierDefaults() {
		OpenAiProperties properties = new OpenAiProperties("sk-test", null, 0, 0, 0, 0L, 0.0,
			null, null, null);

		assertThat(properties.primary())
			.isEqualTo(new ModelTier("gpt-5.4", 32_768, "high", "high"));
		assertThat(properties.light())
			.isEqualTo(new ModelTier("gpt-5-mini", 8_192, "medium", "medium"));
		assertThat(properties.fallback())
			.isEqualTo(new ModelTier("gpt-5.2", 32_768, "medium", "medium"));
	}

	@Test
	@DisplayName("티어 일부만 지정해도 나머지 값은 채워진다")
	void tierFillsPartialValues() {
		ModelTier tier = new ModelTier("gpt-5.4", 0, null, " ");

		assertThat(tier.maxOutputTokens()).isEqualTo(32_768);
		assertThat(tier.reasoningEffort()).isEqualTo("medium");
		assertThat(tier.verbosity()).isEqualTo("medium");
	}

	@Test
	@DisplayName("yml 에 key 외의 openai.api 키가 하나도 없어도 바인딩된다")
	void bindsWithOnlyKeyPresent() {
		runner.withPropertyValues("openai.api.key=sk-test")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenAiProperties.class);
				OpenAiProperties properties = context.getBean(OpenAiProperties.class);
				assertThat(properties.key()).isEqualTo("sk-test");
				assertThat(properties.baseUrl()).isEqualTo("https://api.openai.com");
				assertThat(properties.readTimeoutMs()).isEqualTo(180_000);
				assertThat(properties.primary().model()).isEqualTo("gpt-5.4");
				assertThat(properties.light().model()).isEqualTo("gpt-5-mini");
				assertThat(properties.fallback().model()).isEqualTo("gpt-5.2");
			});
	}

	@Test
	@DisplayName("yml 에 지정한 값은 기본값을 덮어쓴다")
	void bindsOverrides() {
		runner.withPropertyValues(
				"openai.api.key=sk-test",
				"openai.api.base-url=https://proxy.example.com",
				"openai.api.read-timeout-ms=60000",
				"openai.api.max-attempts=5",
				"openai.api.light.model=gpt-5-nano",
				"openai.api.light.max-output-tokens=4096")
			.run(context -> {
				OpenAiProperties properties = context.getBean(OpenAiProperties.class);
				assertThat(properties.baseUrl()).isEqualTo("https://proxy.example.com");
				assertThat(properties.readTimeoutMs()).isEqualTo(60_000);
				assertThat(properties.maxAttempts()).isEqualTo(5);
				assertThat(properties.light().model()).isEqualTo("gpt-5-nano");
				assertThat(properties.light().maxOutputTokens()).isEqualTo(4_096);
				assertThat(properties.light().reasoningEffort()).isEqualTo("medium");
			});
	}

	@Test
	@DisplayName("openai.api.key 가 없으면 key 필수 IllegalArgumentException 으로 기동이 실패한다")
	void contextFailsWithoutKey() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			// hasFailed() 만 보면 전혀 다른 이유로 깨져도 초록이라, 원인 타입과 메시지까지 고정한다.
			assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("openai.api.key");
		});
	}

	@Configuration
	@EnableConfigurationProperties(OpenAiProperties.class)
	static class TestConfig {

	}
}
