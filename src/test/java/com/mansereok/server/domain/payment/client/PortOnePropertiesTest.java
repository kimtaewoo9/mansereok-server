package com.mansereok.server.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PortOnePropertiesTest {

	@Test
	@DisplayName("secret 만 주면 baseUrl 과 connect/read 타임아웃은 기본값이 적용된다")
	void defaults_appliedWhenOptionalValuesMissing() {
		PortOneProperties properties = new PortOneProperties("test-secret", null, null, null);

		assertThat(properties.secret()).isEqualTo("test-secret");
		assertThat(properties.baseUrl()).isEqualTo("https://api.portone.io");
		assertThat(properties.connectTimeoutMs()).isEqualTo(3000);
		assertThat(properties.readTimeoutMs()).isEqualTo(10000);
	}

	@Test
	@DisplayName("secret 이 없거나 비어 있으면 생성에 실패한다 (기동 시 fail-fast)")
	void secret_isRequired() {
		assertThatThrownBy(() -> new PortOneProperties(null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PortOneProperties("   ", null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("portone.api 아래에 record 가 모르는 하위 키(webhook)가 있어도 바인딩이 실패하지 않는다")
	void binding_ignoresUnknownNestedKeys() {
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
				assertThat(properties.baseUrl()).isEqualTo(PortOneProperties.DEFAULT_BASE_URL);
				assertThat(properties.connectTimeoutMs())
					.isEqualTo(PortOneProperties.DEFAULT_CONNECT_TIMEOUT_MS);
				assertThat(properties.readTimeoutMs()).isEqualTo(5000);
			});
	}

	@EnableConfigurationProperties(PortOneProperties.class)
	static class TestConfig {

	}
}
