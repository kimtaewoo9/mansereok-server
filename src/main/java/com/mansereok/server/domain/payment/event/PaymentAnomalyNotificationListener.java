package com.mansereok.server.domain.payment.event;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 이상 Discord 알림. 이벤트를 발행한 트랜잭션이 커밋된 뒤 알림 전용 스레드에서 보낸다.
 *
 * <p>{@code AFTER_COMMIT} 이라 롤백된 결제에는 알림이 가지 않는다. 트랜잭션 밖에서 발행된 이벤트도 버리지 않도록
 * {@code fallbackExecution = true} 로 둔다(그때는 곧바로 보낸다). 실행 스레드는 결제 완료 알림과 같은 {@code notificationTaskExecutor}
 * 다. 이 풀은 가득 차면 호출 스레드에서 실행하므로, 알림 실패가 결제 요청으로 번지지 않게 예외를 error 로그로 삼킨다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentAnomalyNotificationListener {

	private final DiscordNotificationService discordNotificationService;

	@Async("notificationTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(PaymentAnomalyEvent event) {
		try {
			discordNotificationService.sendPaymentAnomalyNotification(event.summary(), event.details());
		} catch (Exception e) {
			log.error("Discord 결제 이상 알림 전송 중 오류 (무시됨): summary={}, details={}", event.summary(),
				event.details(), e);
		}
	}
}
