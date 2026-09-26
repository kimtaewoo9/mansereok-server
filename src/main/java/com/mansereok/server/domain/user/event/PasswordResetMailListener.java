package com.mansereok.server.domain.user.event;

import com.mansereok.server.domain.user.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 비밀번호 재설정 메일을 재설정 요청 트랜잭션이 커밋된 뒤에 보낸다.
 *
 * <p>예전에는 UserService 가 토큰을 저장한 트랜잭션 안에서 메일 발송을 호출했다. 그러면 커밋이 실패해도 DB 에 없는 토큰의 링크가
 * 메일로 나갔다. {@code AFTER_COMMIT} 이라 커밋된 토큰만 메일로 나간다.
 *
 * <p>EmailService.sendPasswordResetEmail 은 {@code @Async} 라 여기서는 메일 작업을 기본 스레드 풀에 넘기기만 한다. 그 풀이 가득
 * 차면 넘기는 순간 TaskRejectedException 이 난다. 이 catch 가 없어도 재설정 요청은 실패하지 않는다. 스프링은 AFTER_COMMIT 리스너를
 * 커밋이 끝난 뒤(TransactionSynchronization.afterCompletion) 부르고, 거기서 난 예외는 ERROR 로그
 * ('TransactionSynchronization.afterCompletion threw exception')만 남기고 삼킨다. 풀이 가득 찬 것은 알려진 혼잡이라, 회원 id 만
 * 담아 warn 으로 남기려고 잡는다. 로그에는 이메일과 토큰을 남기지 않는다. 사용자는 메일이 오지 않으면 다시 요청한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetMailListener {

	private final EmailService emailService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
		try {
			emailService.sendPasswordResetEmail(event.email(), event.token());
		} catch (TaskRejectedException e) {
			log.warn("비밀번호 재설정 메일을 보내지 못함(비동기 기본 스레드 풀이 가득 참): userId={}", event.userId(), e);
		}
	}
}
