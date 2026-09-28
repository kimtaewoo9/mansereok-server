package com.mansereok.server.domain.user.event;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 가입·탈퇴 알림을 그 트랜잭션이 커밋된 뒤에 Discord·Slack 으로 보낸다.
 *
 * <p>예전에는 UserService 가 저장·삭제와 같은 흐름에서 알림을 동기로 보냈다. 탈퇴는 트랜잭션 안에서 주문·결제 행을 잠근 채 외부
 * 응답을 기다렸고, 커밋이 실패해도 '회원 탈퇴' 알림은 이미 나가 있었다. {@code AFTER_COMMIT} 이라 커밋된 가입·탈퇴에만 알림이
 * 가고, 알림을 보내는 동안에는 잠금이 풀려 있다.
 *
 * <p>이벤트는 트랜잭션 안에서 발행해야 한다. 트랜잭션 밖에서 발행한 이벤트는 이 리스너가 받지 않는다.
 *
 * <p>알림은 아직 커밋한 요청 스레드에서 동기로 보낸다. 행 잠금은 풀렸지만, 응답은 알림이 끝날 때까지 기다리고 DB 커넥션도 그동안
 * 쥔다. AFTER_COMMIT 리스너는 트랜잭션 매니저가 커넥션을 풀에 돌려주기 전에 돌기 때문이다. 가입·탈퇴 알림 안에서 재 보면 풀의 사용
 * 중 커넥션이 1 이고 트랜잭션도 아직 활성이다. open-in-view 가 꺼져 있어 리스너가 끝나면 커넥션을 풀에 돌려준다(가입이 끝난 뒤
 * 재 보면 0). 켜져 있던 때처럼 요청이 끝날 때까지 쥐지는 않지만, 알림(가입은 Discord 두 채널과 Slack)을 기다리는 동안 쥐는 것은
 * 남아 있다. 결제 스택의 알림 전용 스레드 풀(notificationTaskExecutor)이 들어오면 두 메서드에
 * {@code @Async("notificationTaskExecutor")} 를 붙여 이 대기를 없앤다.
 *
 * <p>알림 실패는 이미 끝난 가입·탈퇴를 되돌리지 않으므로 warn 로그만 남기고 삼킨다. 한 채널이 실패해도 다른 채널은 보낸다. 로그에는
 * 이메일과 이름을 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserNotificationListener {

	private final DiscordNotificationService discordNotificationService;
	private final SlackNotificationService slackNotificationService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onUserRegistered(UserRegisteredEvent event) {
		try {
			discordNotificationService.sendUserCreatedNotification(event.name(), event.email(),
				event.userId(), event.signupPath(), event.createdAt());
		} catch (Exception e) {
			log.warn("Discord 가입 알림 전송 실패: userId={}", event.userId(), e);
		}

		try {
			slackNotificationService.sendUserCreatedNotification(event.name(), event.email(),
				event.userId(), event.signupPath(), event.createdAt());
		} catch (Exception e) {
			log.warn("Slack 가입 알림 전송 실패: userId={}", event.userId(), e);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onUserWithdrawn(UserWithdrawnEvent event) {
		try {
			discordNotificationService.sendUserWithdrawnNotification(event.name(), event.email());
		} catch (Exception e) {
			log.warn("Discord 탈퇴 알림 전송 실패: userId={}", event.userId(), e);
		}
	}
}
