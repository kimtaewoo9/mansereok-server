package com.mansereok.server.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.payment.client.PortOneProperties.Webhook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PortOnePropertiesTest {

	private static final Webhook WEBHOOK = new Webhook("test-webhook-secret");

	@Test
	@DisplayName("secret 과 webhook.secret 만 주면 baseUrl 과 connect/read 타임아웃은 기본값이 적용된다")
	void defaults_appliedWhenOptionalValuesMissing() {
		PortOneProperties properties = new PortOneProperties("test-secret", null, null, null,
			WEBHOOK);

		assertThat(properties.secret()).isEqualTo("test-secret");
		assertThat(properties.baseUrl()).isEqualTo("https://api.portone.io");
		assertThat(properties.connectTimeoutMs()).isEqualTo(3000);
		assertThat(properties.readTimeoutMs()).isEqualTo(10000);
		assertThat(properties.webhook().secret()).isEqualTo("test-webhook-secret");
	}

	@Test
	@DisplayName("secret 이 없거나 비어 있으면 생성에 실패한다 (기동 시 fail-fast)")
	void secret_isRequired() {
		assertThatThrownBy(() -> new PortOneProperties(null, null, null, null, WEBHOOK))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PortOneProperties("   ", null, null, null, WEBHOOK))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("webhook.secret 이 없거나 비어 있으면 생성에 실패한다 (서명 검증기를 만들 수 없다)")
	void webhookSecret_isRequired() {
		assertThatThrownBy(() -> new PortOneProperties("test-secret", null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("portone.api.webhook.secret");
		assertThatThrownBy(
			() -> new PortOneProperties("test-secret", null, null, null, new Webhook(null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("portone.api.webhook.secret");
		assertThatThrownBy(
			() -> new PortOneProperties("test-secret", null, null, null, new Webhook("  ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("portone.api.webhook.secret");
	}

	@Test
	@DisplayName("yml 의 portone.api.webhook.secret 키가 webhook().secret() 으로 바인딩된다")
	void binding_bindsWebhookSecret() {
		new ApplicationContextRunner()
			.withUserConfiguration(TestConfig.class)
			.withPropertyValues(
				"portone.api.secret=test-secret",
				"portone.api.webhook.secret=test-webhook-secret",
				"portone.api.read-timeout-ms=5000")
			.run(context -> {
				assertThat(context).hasSingleBean(PortOneProperties.class);

				PortOneProperties properties = context.getBean(PortOneProperties.class);
				assertThat(properties.secret()).isEqualTo("test-secret");
				assertThat(properties.webhook().secret()).isEqualTo("test-webhook-secret");
				assertThat(properties.baseUrl()).isEqualTo(PortOneProperties.DEFAULT_BASE_URL);
				assertThat(properties.connectTimeoutMs())
					.isEqualTo(PortOneProperties.DEFAULT_CONNECT_TIMEOUT_MS);
				assertThat(properties.readTimeoutMs()).isEqualTo(5000);
			});
	}

	@Test
	@DisplayName("portone.api.webhook.secret 이 없으면 컨텍스트 기동에 실패한다")
	void binding_failsWithoutWebhookSecret() {
		new ApplicationContextRunner()
			.withUserConfiguration(TestConfig.class)
			.withPropertyValues("portone.api.secret=test-secret")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasStackTraceContaining("portone.api.webhook.secret");
			});
	}

	@EnableConfigurationProperties(PortOneProperties.class)
	static class TestConfig {

	}
}
