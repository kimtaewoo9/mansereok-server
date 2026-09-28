package com.mansereok.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
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

	@Test
	@DisplayName("결제 이상 알림은 요약 아래에 항목을 넣은 순서대로 한 줄씩 적어 결제 채널 웹훅으로 보낸다")
	void sendPaymentAnomalyNotification_postsSummaryAndDetailsToPaymentChannel() {
		// given: Discord 는 바깥 시스템이라 RestTemplate 이 보내는 요청을 가로채 내용을 본다
		DiscordNotificationService service = new DiscordNotificationService(new RestTemplateBuilder());
		ReflectionTestUtils.setField(service, "paymentWebhookUrl", "http://discord.test/payment");
		RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
		MockRestServiceServer discord = MockRestServiceServer.bindTo(restTemplate).build();
		discord.expect(requestTo("http://discord.test/payment"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.username").value("결제 Bot"))
			.andExpect(jsonPath("$.embeds[0].title").value("🚨 결제 이상 확인 필요"))
			.andExpect(jsonPath("$.embeds[0].description")
				.value("쿠폰이 두 주문에 쓰였습니다.\n\n**주문 번호:** order_late_001\n**쿠폰 ID:** 7"))
			.andRespond(withSuccess());
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", "order_late_001");
		details.put("쿠폰 ID", "7");

		// when
		service.sendPaymentAnomalyNotification("쿠폰이 두 주문에 쓰였습니다.", details);

		// then
		discord.verify();
	}
}
