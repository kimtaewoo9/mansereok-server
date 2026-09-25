package com.mansereok.server.domain.user.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 가입·탈퇴 이벤트를 받았을 때 Discord·Slack 에 무엇을 보내는지, 한 채널이 실패해도 어떻게 되는지 확인한다.
 *
 * <p>리스너 메서드를 직접 부른다. 커밋 뒤에만 불리는지는 스프링의 {@code @TransactionalEventListener} 가 정하는 일이라
 * WithdrawalMySqlTest 가 실제 트랜잭션으로 본다. Discord·Slack 은 바깥 시스템이라 목으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class UserNotificationListenerTest {

	private static final LocalDateTime SIGNED_UP_AT = LocalDateTime.of(2026, 9, 26, 10, 30);

	@Mock
	private DiscordNotificationService discordNotificationService;
	@Mock
	private SlackNotificationService slackNotificationService;

	@InjectMocks
	private UserNotificationListener listener;

	@Nested
	@DisplayName("가입 이벤트를 받으면")
	class WhenUserRegistered {

		private final UserRegisteredEvent event = new UserRegisteredEvent(3L, "카카오회원",
			"kakao@example.com", "KAKAO OAuth", SIGNED_UP_AT);

		@Test
		@DisplayName("디스코드와 슬랙에 같은 이름·이메일·id·가입 경로·가입 시각을 보낸다")
		void sendsSameSignupToDiscordAndSlack() {
			// when
			listener.onUserRegistered(event);

			// then
			then(discordNotificationService).should().sendUserCreatedNotification(
				"카카오회원", "kakao@example.com", 3L, "KAKAO OAuth", SIGNED_UP_AT);
			then(slackNotificationService).should().sendUserCreatedNotification(
				"카카오회원", "kakao@example.com", 3L, "KAKAO OAuth", SIGNED_UP_AT);
		}

		@Test
		@DisplayName("디스코드 알림이 예외를 던져도 슬랙 알림은 보내고 예외를 밖으로 던지지 않는다")
		void sendsSlackEvenIfDiscordFails() {
			// given
			willThrow(new IllegalStateException("Discord 응답 없음"))
				.given(discordNotificationService).sendUserCreatedNotification(
					"카카오회원", "kakao@example.com", 3L, "KAKAO OAuth", SIGNED_UP_AT);

			// when & then
			assertThatCode(() -> listener.onUserRegistered(event)).doesNotThrowAnyException();
			then(slackNotificationService).should().sendUserCreatedNotification(
				"카카오회원", "kakao@example.com", 3L, "KAKAO OAuth", SIGNED_UP_AT);
		}

		@Test
		@DisplayName("슬랙 알림이 예외를 던져도 예외를 밖으로 던지지 않는다")
		void doesNotThrowIfSlackFails() {
			// given
			willThrow(new IllegalStateException("Slack 응답 없음"))
				.given(slackNotificationService).sendUserCreatedNotification(
					"카카오회원", "kakao@example.com", 3L, "KAKAO OAuth", SIGNED_UP_AT);

			// when & then
			assertThatCode(() -> listener.onUserRegistered(event)).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("탈퇴 이벤트를 받으면")
	class WhenUserWithdrawn {

		private final UserWithdrawnEvent event = new UserWithdrawnEvent(7L, "탈퇴회원", "leave@example.com");

		@Test
		@DisplayName("디스코드에 탈퇴한 회원의 이름과 이메일을 보낸다")
		void sendsWithdrawalToDiscord() {
			// when
			listener.onUserWithdrawn(event);

			// then
			then(discordNotificationService).should().sendUserWithdrawnNotification("탈퇴회원", "leave@example.com");
		}

		@Test
		@DisplayName("디스코드 알림이 예외를 던져도 예외를 밖으로 던지지 않는다")
		void doesNotThrowIfDiscordFails() {
			// given
			willThrow(new IllegalStateException("Discord 응답 없음"))
				.given(discordNotificationService).sendUserWithdrawnNotification("탈퇴회원", "leave@example.com");

			// when & then
			assertThatCode(() -> listener.onUserWithdrawn(event)).doesNotThrowAnyException();
		}
	}
}
