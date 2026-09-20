package com.mansereok.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
	@DisplayName("응답을 주지 않는 웹훅 서버라도 읽기 타임아웃으로 8초 안에 반환한다 (예외는 삼킨다)")
	void paymentNotification_returnsWithinTimeoutWhenServerHangs() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch release = new CountDownLatch(1);
		server.createContext("/hook", exchange -> {
			try {
				// 테스트가 끝날 때까지 응답을 보내지 않는다
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();

		try {
			DiscordNotificationService service = new DiscordNotificationService(
				new RestTemplateBuilder());
			ReflectionTestUtils.setField(service, "paymentWebhookUrl",
				"http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

			assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
				service.sendPaymentCompletedNotification("홍길동", "user@example.com", 10000L,
					"기본 사주", LocalDateTime.now(), null, null));
		} finally {
			release.countDown();
			server.stop(0);
		}
	}
}
