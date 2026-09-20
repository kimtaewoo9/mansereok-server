package com.mansereok.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class DiscordNotificationServiceTest {

	@Test
	@DisplayName("RestTemplate 은 주입받은 RestTemplateBuilder 로 만든다 (new RestTemplate() 을 쓰지 않는다)")
	void restTemplate_isBuiltByInjectedBuilder() {
		List<RestTemplate> built = new ArrayList<>();
		RestTemplateBuilder builder = new RestTemplateBuilder(built::add);

		DiscordNotificationService service = new DiscordNotificationService(builder);

		assertThat(built).hasSize(1);
		assertThat(ReflectionTestUtils.getField(service, "restTemplate")).isSameAs(built.get(0));
	}

	@Test
	@DisplayName("RestTemplate 에 연결 3초·읽기 5초 타임아웃을 준다")
	void restTemplate_hasConnectAndReadTimeout() {
		// 실제 소켓을 기다리지 않고 빌더에 전달된 타임아웃 설정만 가로채 검증한다
		AtomicReference<ClientHttpRequestFactorySettings> captured = new AtomicReference<>();
		RestTemplateBuilder builder = new RestTemplateBuilder().requestFactoryBuilder(settings -> {
			captured.set(settings);
			return new SimpleClientHttpRequestFactory();
		});

		new DiscordNotificationService(builder);

		assertThat(captured.get()).isNotNull();
		assertThat(captured.get().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(captured.get().readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}
}
