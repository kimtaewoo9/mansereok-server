package com.mansereok.server.domain.payment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class PaymentAnomalyNotificationListenerTest {

	private static final String SUMMARY = "만료 뒤 결제된 주문의 쿠폰을 다른 주문이 이미 쓰고 있습니다. 결제는 확정했습니다.";

	@InjectMocks
	private PaymentAnomalyNotificationListener listener;

	@Mock
	private DiscordNotificationService discordNotificationService;

	private static Map<String, String> details() {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("주문 번호", "order_late_001");
		details.put("쿠폰 ID", "7");
		return details;
	}

	@Test
	@DisplayName("이벤트의 요약과 항목을 그대로 Discord 결제 이상 알림으로 보낸다")
	void on_sendsSummaryAndDetailsToDiscord() {
		// when
		listener.on(new PaymentAnomalyEvent(SUMMARY, details()));

		// then
		verify(discordNotificationService).sendPaymentAnomalyNotification(SUMMARY, details());
	}

	@Test
	@DisplayName("Discord 전송 중 예외가 나도 error 로그로 삼키고 밖으로 던지지 않는다")
	void on_discordFailure_isSwallowed() {
		// given
		willThrow(new RuntimeException("discord down")).given(discordNotificationService)
			.sendPaymentAnomalyNotification(SUMMARY, details());

		// when & then
		assertThatCode(() -> listener.on(new PaymentAnomalyEvent(SUMMARY, details())))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("리스너는 커밋 뒤(AFTER_COMMIT) 알림 전용 풀(notificationTaskExecutor)에서 돌고, 트랜잭션 밖에서 발행돼도 버리지 않는다")
	void on_isDeclaredAsAsyncAfterCommitListenerWithFallback() throws NoSuchMethodException {
		// when
		Method method = PaymentAnomalyNotificationListener.class.getMethod("on", PaymentAnomalyEvent.class);
		TransactionalEventListener txListener = method.getAnnotation(TransactionalEventListener.class);
		Async async = method.getAnnotation(Async.class);

		// then
		assertThat(txListener).isNotNull();
		assertThat(txListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
		assertThat(txListener.fallbackExecution()).as("트랜잭션 밖 발행도 보낸다").isTrue();
		assertThat(async).isNotNull();
		assertThat(async.value()).isEqualTo("notificationTaskExecutor");
	}
}
