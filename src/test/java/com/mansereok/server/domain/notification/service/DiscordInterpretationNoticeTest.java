package com.mansereok.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 해석 요청 알림이 Discord 웹훅으로 보내는 본문과 전송 완료 로그에 결제 ID·상품·유료 구분만 담기는지 확인한다.
 *
 * <p>Discord 는 우리가 통제하지 못하는 바깥 채널이라, 서비스가 쓰는 RestTemplate 에 {@link MockRestServiceServer} 를 붙여 실제로
 * 나가는 요청 본문을 본다. 본문 설명을 한 글자까지 같은 문자열로 비교하므로, 이름·이메일·생년월일 같은 줄이 하나라도 붙으면 실패한다.
 */
@DisplayName("Discord 해석 요청 알림")
class DiscordInterpretationNoticeTest {

	private static final String WEBHOOK_URL = "http://discord.test/interpretation-request";

	private final DiscordNotificationService service = new DiscordNotificationService();
	private MockRestServiceServer discord;

	private final Logger appLogger = (Logger) LoggerFactory.getLogger("com.mansereok");
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private Level levelBeforeTest;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(service, "interpretationRequestWebhookUrl", WEBHOOK_URL);
		RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
		discord = MockRestServiceServer.bindTo(restTemplate).build();

		// 테스트 JVM 의 로그 설정과 상관없이 운영과 같은 INFO 에서 본다.
		levelBeforeTest = appLogger.getLevel();
		appLogger.setLevel(Level.INFO);
		logs.start();
		appLogger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		appLogger.detachAppender(logs);
		logs.stop();
		appLogger.setLevel(levelBeforeTest);
	}

	@Nested
	@DisplayName("사주 해석 요청 알림은")
	class SajuRequestNotice {

		@Test
		@DisplayName("본문 설명에 결제 ID·상품·유료 구분 세 줄만 담는다")
		void bodyHasOnlyPaymentProductAndPaidFlag() {
			// given
			discord.expect(requestTo(WEBHOOK_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.embeds[0].title").value("🔮 사주 해석 요청"))
				.andExpect(jsonPath("$.embeds[0].description")
					.value("**결제 ID:** 101\n**상품:** LIFE_OVERALL(1)\n**구분:** 유료"))
				.andRespond(withSuccess());

			// when
			service.sendInterpretationRequestNotification(101L, "LIFE_OVERALL(1)", false);

			// then
			discord.verify();
		}

		@Test
		@DisplayName("전송 완료 로그에 결제 ID 만 남긴다")
		void completionLogHasOnlyPaymentId() {
			// given
			discord.expect(requestTo(WEBHOOK_URL)).andRespond(withSuccess());

			// when
			service.sendInterpretationRequestNotification(101L, "LIFE_OVERALL(1)", false);

			// then
			assertThat(logMessages()).containsExactly("Discord 사주 요청 알림 전송 완료: paymentId=101");
		}
	}

	@Nested
	@DisplayName("궁합 해석 요청 알림은")
	class CompatibilityRequestNotice {

		@Test
		@DisplayName("본문 설명에 결제 ID·상품·무료 구분 세 줄만 담고 두 사람의 이름과 생년월일은 담지 않는다")
		void bodyHasOnlyPaymentProductAndFreeFlag() {
			// given
			discord.expect(requestTo(WEBHOOK_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.embeds[0].title").value("💕 궁합 해석 요청"))
				.andExpect(jsonPath("$.embeds[0].description")
					.value("**결제 ID:** 102\n**상품:** REUNION(19)\n**구분:** 무료"))
				.andRespond(withSuccess());

			// when
			service.sendCompatibilityRequestNotification(102L, "REUNION(19)", true);

			// then
			discord.verify();
		}

		@Test
		@DisplayName("전송 완료 로그에 결제 ID 만 남긴다")
		void completionLogHasOnlyPaymentId() {
			// given
			discord.expect(requestTo(WEBHOOK_URL)).andRespond(withSuccess());

			// when
			service.sendCompatibilityRequestNotification(102L, "REUNION(19)", true);

			// then
			assertThat(logMessages()).containsExactly("Discord 궁합 요청 알림 전송 완료: paymentId=102");
		}
	}

	private List<String> logMessages() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}
}
