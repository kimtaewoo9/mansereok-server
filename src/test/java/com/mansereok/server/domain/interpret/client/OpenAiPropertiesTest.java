package com.mansereok.server.domain.interpret.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

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
			.isEqualTo(new ModelTier("gpt-5.4", 32_768, ReasoningEffort.HIGH, Verbosity.HIGH));
		assertThat(properties.light())
			.isEqualTo(new ModelTier("gpt-5-mini", 8_192, ReasoningEffort.MEDIUM, Verbosity.MEDIUM));
		assertThat(properties.fallback())
			.isEqualTo(new ModelTier("gpt-5.2", 32_768, ReasoningEffort.MEDIUM, Verbosity.MEDIUM));
	}

	@Test
	@DisplayName("티어 일부만 지정해도 나머지 값은 채워진다")
	void tierFillsPartialValues() {
		ModelTier tier = new ModelTier("gpt-5.4", 0, null, null);

		assertThat(tier.maxOutputTokens()).isEqualTo(32_768);
		assertThat(tier.reasoningEffort()).isEqualTo(ReasoningEffort.MEDIUM);
		assertThat(tier.verbosity()).isEqualTo(Verbosity.MEDIUM);
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
				assertThat(properties.light().reasoningEffort()).isEqualTo(ReasoningEffort.MEDIUM);
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

	@Nested
	@DisplayName("toString 으로 설정을 찍을 때")
	class KeyMasking {

		@Test
		@DisplayName("key 는 앞 3글자와 길이만 남기고, 모델명 같은 나머지 설정은 그대로 보여 준다")
		void toStringHidesKey() {
			// given
			OpenAiProperties properties = new OpenAiProperties("sk-test-secret", null, 0, 0, 0, 0L, 0.0,
				null, null, null);

			// when
			String text = properties.toString();

			// then
			assertThat(text)
				// 가린 표시 바로 뒤에 다음 설정이 이어지는지까지 본다. 가린 표시 뒤에 키 나머지를 붙여 찍으면 여기서 실패한다.
				.contains("[key=sk-***(14자), baseUrl=https://api.openai.com, ")
				.doesNotContain("sk-test-secret", "test-secret", "secret")
				.contains("gpt-5.4", "gpt-5-mini", "gpt-5.2");
		}

		@ParameterizedTest(name = "[{index}] key \"{0}\" → {1}")
		@CsvSource(textBlock = """
			# key, toString 에 찍히는 key 부분(바로 뒤 설정까지)
			k12,                      'key=***(3자), baseUrl='
			k123,                     'key=***(4자), baseUrl='
			k1234567,                 'key=***(8자), baseUrl='
			k12345678,                'key=k12***(9자), baseUrl='
			sk-proj-0123456789abcdef, 'key=sk-***(24자), baseUrl='
			""")
		@DisplayName("8글자 이하 key 는 길이만, 9글자부터는 앞 3글자와 길이만 남긴다")
		void masksKeyByLength(String key, String expectedKeyPart) {
			// given
			OpenAiProperties properties = new OpenAiProperties(key, null, 0, 0, 0, 0L, 0.0,
				null, null, null);

			// when
			String text = properties.toString();

			// then
			assertThat(text).contains(expectedKeyPart).doesNotContain(key);
		}
	}

	@Nested
	@DisplayName("모델 티어의 reasoning-effort·verbosity 를 yml 에서 읽을 때")
	class TierLevelBinding {

		@Test
		@DisplayName("소문자 high 로 적으면 HIGH 로 읽힌다")
		void bindsLowercaseValue() {
			runner.withPropertyValues(
					"openai.api.key=sk-test",
					"openai.api.primary.model=gpt-5.4",
					"openai.api.primary.reasoning-effort=high",
					"openai.api.primary.verbosity=low")
				.run(context -> {
					OpenAiProperties properties = context.getBean(OpenAiProperties.class);
					assertThat(properties.primary().reasoningEffort()).isEqualTo(ReasoningEffort.HIGH);
					assertThat(properties.primary().verbosity()).isEqualTo(Verbosity.LOW);
				});
		}

		@ParameterizedTest(name = "[{index}] reasoning-effort={0} → {1}")
		@CsvSource(textBlock = """
			none,    NONE
			minimal, MINIMAL
			low,     LOW
			medium,  MEDIUM
			high,    HIGH
			xhigh,   XHIGH
			""")
		@DisplayName("reasoning-effort 는 none·minimal·low·medium·high·xhigh 를 소문자로 적으면 모두 읽는다")
		void bindsEveryDocumentedEffort(String value, ReasoningEffort expected) {
			runner.withPropertyValues(
					"openai.api.key=sk-test",
					"openai.api.primary.model=gpt-5.4",
					"openai.api.primary.reasoning-effort=" + value)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAiProperties.class).primary().reasoningEffort())
						.isEqualTo(expected);
				});
		}

		@ParameterizedTest(name = "[{index}] 값 \"{0}\"")
		@ValueSource(strings = {"", "   "})
		@DisplayName("빈 값이나 공백만 적으면 적지 않은 것과 같이 medium 으로 읽힌다")
		void readsBlankValueAsMedium(String blank) {
			// withPropertyValues 는 값의 앞뒤 공백을 지운다. 환경 변수처럼 공백이 그대로 들어오는 경우를 보려고
			// PropertySource 를 직접 넣는다.
			runner.withInitializer(context -> context.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("blankTierValues", Map.of(
						"openai.api.key", "sk-test",
						"openai.api.primary.model", "gpt-5.4",
						"openai.api.primary.reasoning-effort", blank,
						"openai.api.primary.verbosity", blank))))
				.run(context -> {
					assertThat(context).hasNotFailed();
					OpenAiProperties properties = context.getBean(OpenAiProperties.class);
					assertThat(properties.primary().reasoningEffort()).isEqualTo(ReasoningEffort.MEDIUM);
					assertThat(properties.primary().verbosity()).isEqualTo(Verbosity.MEDIUM);
				});
		}

		@ParameterizedTest(name = "[{index}] {0}={1}")
		@CsvSource(textBlock = """
			openai.api.primary.reasoning-effort, hihg
			openai.api.primary.verbosity,        minimal
			openai.api.fallback.reasoning-effort, max
			""")
		@DisplayName("목록에 없는 값을 적으면 기동이 실패한다")
		void failsOnUnknownValue(String propertyName, String value) {
			runner.withPropertyValues(
					"openai.api.key=sk-test",
					"openai.api.primary.model=gpt-5.4",
					"openai.api.fallback.model=gpt-5.2",
					propertyName + "=" + value)
				.run(context -> {
					assertThat(context).hasFailed();
					// hasFailed() 만 보면 다른 이유로 깨져도 초록이라, 어느 설정이 어떤 값 때문에 실패했는지까지 본다.
					assertThat(context).getFailure()
						.hasStackTraceContaining("Failed to bind properties under '" + propertyName + "'")
						.hasStackTraceContaining("for value [" + value + "]");
				});
		}
	}

	@Configuration
	@EnableConfigurationProperties(OpenAiProperties.class)
	static class TestConfig {

	}
}
