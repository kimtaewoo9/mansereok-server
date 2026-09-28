package com.mansereok.server.domain.user.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.mansereok.server.domain.user.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * 재설정 요청 이벤트를 받으면 재설정 메일을 보내고, 메일 작업이 거절돼도 예외를 밖으로 던지지 않는지 확인한다.
 *
 * <p>커밋 뒤에만 메일이 나가는지는 실제 트랜잭션이 필요해 PasswordResetMySqlTest 가 본다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PasswordResetMailListenerTest {

	private static final PasswordResetRequestedEvent EVENT = new PasswordResetRequestedEvent(11L,
		"reset@example.com", "reset-token-value");

	@Mock
	private EmailService emailService;

	private PasswordResetMailListener listener;

	@BeforeEach
	void setUp() {
		listener = new PasswordResetMailListener(emailService);
	}

	@Test
	@DisplayName("재설정 요청 이벤트를 받으면 이벤트의 주소로 이벤트의 토큰을 담은 재설정 메일을 보낸다")
	void sendsResetMailWithEventToken() {
		// when
		listener.onPasswordResetRequested(EVENT);

		// then
		then(emailService).should().sendPasswordResetEmail("reset@example.com", "reset-token-value");
	}

	@Test
	@DisplayName("스레드 풀이 메일 작업을 받지 않아도(TaskRejectedException) 예외를 밖으로 던지지 않고, warn 로그에는 회원 id 만 남긴다")
	void swallowsRejectedMailTaskAndLogsOnlyUserId(CapturedOutput output) {
		// given
		willThrow(new TaskRejectedException("기본 스레드 풀이 가득 참"))
			.given(emailService).sendPasswordResetEmail("reset@example.com", "reset-token-value");

		// when & then: 커밋 뒤라 잡지 않아도 요청은 실패하지 않는다. 스프링의 ERROR 로그 대신 회원 id 만 담은 warn 로그를 남기는지 본다.
		assertThatCode(() -> listener.onPasswordResetRequested(EVENT)).doesNotThrowAnyException();
		assertThat(output.getAll())
			.contains("비밀번호 재설정 메일을 보내지 못함", "userId=11")
			.doesNotContain("reset@example.com", "reset-token-value");
	}
}
