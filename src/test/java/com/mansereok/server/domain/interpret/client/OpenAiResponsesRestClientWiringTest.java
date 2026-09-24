package com.mansereok.server.domain.interpret.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 스프링 빈 조립 확인. 실제 요청 팩토리(ClientHttpRequestFactoryBuilder.detect())까지 만들어진다.
 */
class OpenAiResponsesRestClientWiringTest {

	@Test
	@DisplayName("RestClient.Builder 와 ObjectMapper 빈만으로 클라이언트가 조립된다")
	void wiresUpFromSpringBeans() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
				JacksonAutoConfiguration.class,
				HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class))
			.withUserConfiguration(TestConfig.class)
			.withPropertyValues("openai.api.key=sk-test")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OpenAiResponsesRestClient.class);
				assertThat(context.getBean(OpenAiResponsesClient.class))
					.isInstanceOf(OpenAiResponsesRestClient.class);
			});
	}

	@Configuration
	@EnableConfigurationProperties(OpenAiProperties.class)
	@Import(OpenAiResponsesRestClient.class)
	static class TestConfig {

	}
}
