package com.mansereok.server.global.config;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 결제 알림 전용 풀(AsyncConfig#notificationTaskExecutor)이 지키는 약속을 확인한다.
 *
 * <ol>
 *   <li>스레드 4개와 대기열 200건이 모두 차도 알림을 거부하지 않고, 알림을 넘긴 스레드에서 바로 실행한다. 거부 예외가 나면 커밋 뒤
 *   콜백에서 그 예외가 결제 완료 API 까지 올라가 "DB 는 PAID 인데 응답은 500" 이 된다.</li>
 *   <li>서버가 종료될 때 실행 중인 알림과 대기열에 남은 알림을 버리지 않고 보낸 뒤 닫는다.</li>
 *   <li>웹 서버의 우아한 종료 중에 마무리된 결제 요청들이 넘긴 알림도 컨텍스트가 닫히기 전에 모두 보낸다.</li>
 * </ol>
 *
 * <p>알림 한 건은 Discord 호출처럼 시간이 걸리는 작업으로 흉내 낸다. 풀을 채울 때는 래치로 붙잡아 두고, 종료를 볼 때는 200ms 동안
 * 잠드는 작업을 쓴다(종료 직전의 스레드 수와 대기열을 확인할 때는 래치로 붙잡아 두었다가 풀어 준 뒤 잠든다). 기다리거나 잠자는 도중
 * 인터럽트되면 보내지 못한 알림으로 센다.
 *
 * <p>종료 설정 두 줄(setWaitForTasksToCompleteOnShutdown·setAwaitTerminationSeconds)이 빠지는 회귀는 shutdown() 을 직접 부르는
 * 테스트가 매번 잡는다. 컨텍스트 전체를 닫는 테스트는 운영과 같은 종료 순서를 확인하지만, 두 줄이 빠진 코드에서 실패할지는 알림
 * 스레드가 풀의 stop 단계보다 먼저 시작하는지(스레드 타이밍)에 달려 있다.
 */
class NotificationExecutorTest {

	// Discord 호출 한 번이 걸리는 시간으로 둔다. 종료가 이 시간을 기다리지 않으면 알림이 사라진다.
	private static final Duration SEND_TIME = Duration.ofMillis(200);

	@Nested
	@DisplayName("스레드 4개와 대기열 200건이 모두 차 있으면")
	class WhenPoolIsFull {

		// 스레드 4개가 붙잡고 있는 알림과 대기열 200건을 합친 수
		private static final int NOTIFICATIONS_TO_FILL_POOL = 204;

		private final ThreadPoolTaskExecutor executor = notificationExecutor();
		private final CountDownLatch releaseBusyNotifications = new CountDownLatch(1);

		@AfterEach
		void releaseAndClose() {
			releaseBusyNotifications.countDown();
			executor.shutdown();
		}

		@Test
		@DisplayName("다음 알림은 거부되지 않고 알림을 넘긴 스레드에서 바로 실행된다")
		void runsNextNotificationOnCallerThread() {
			// given
			submitTimes(NOTIFICATIONS_TO_FILL_POOL, () -> awaitRelease(releaseBusyNotifications));
			assertThat(executor.getPoolSize()).as("알림을 보내는 스레드 수").isEqualTo(4);
			assertThat(executor.getQueueSize()).as("대기열에 쌓인 알림 수").isEqualTo(200);
			AtomicReference<Thread> ranOn = new AtomicReference<>();

			// when
			executor.execute(() -> ranOn.set(Thread.currentThread()));

			// then
			assertThat(ranOn.get()).as("다음 알림을 실행한 스레드").isSameAs(Thread.currentThread());
		}

		private void submitTimes(int count, Runnable notification) {
			IntStream.range(0, count).forEach(i -> executor.execute(notification));
		}
	}

	@Nested
	@DisplayName("서버가 종료되면")
	class WhenServerShutsDown {

		@Test
		@DisplayName("실행 중인 알림 2건과 대기열의 알림 4건을 모두 보낸 뒤 풀을 닫는다")
		void sendsRunningAndQueuedNotificationsBeforeClosing() {
			// given: 스레드 2개가 한 건씩 붙잡고 있고 4건이 대기열에서 기다린다
			ThreadPoolTaskExecutor executor = notificationExecutor();
			CountDownLatch startSending = new CountDownLatch(1);
			AtomicInteger sent = new AtomicInteger();
			IntStream.range(0, 6).forEach(i -> executor.execute(slowNotificationAfter(startSending, sent)));
			assertThat(executor.getPoolSize()).as("알림을 보내는 스레드 수").isEqualTo(2);
			assertThat(executor.getQueueSize()).as("대기열에 쌓인 알림 수").isEqualTo(4);

			// when: 알림이 보내지기 시작하자마자 스프링이 빈을 없앨 때(destroy) 부르는 종료
			startSending.countDown();
			executor.shutdown();

			// then
			assertThat(sent.get()).as("보낸 알림 수").isEqualTo(6);
			assertThat(executor.getThreadPoolExecutor().isTerminated()).as("풀이 닫혔는지").isTrue();
		}

		@Test
		@DisplayName("우아한 종료 중에 마무리된 결제 요청들이 넘긴 알림 6건을 컨텍스트가 닫히기 전에 모두 보낸다")
		void sendsAllNotificationsSubmittedDuringGracefulShutdown() {
			// given: 알림 풀보다 먼저 멈추는 웹 서버의 우아한 종료 자리에서, 마무리되는 결제 요청들이 알림 6건을 넘긴다.
			// 스레드는 2개라 4건은 대기열에서 기다린다.
			AtomicInteger sent = new AtomicInteger();
			AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
			context.register(AsyncConfig.class);
			context.registerBean(PaymentsFinishingDuringGracefulShutdown.class,
				() -> new PaymentsFinishingDuringGracefulShutdown(
					context.getBean("notificationTaskExecutor", Executor.class), 6, sent));
			context.refresh();

			// when: 웹 서버의 우아한 종료 → 알림 풀의 stop 단계 → 빈 제거 순서로 닫힌다
			context.close();

			// then
			assertThat(sent.get()).as("우아한 종료 중에 넘긴 알림 중 보낸 수").isEqualTo(6);
		}
	}

	/**
	 * 웹 서버의 우아한 종료(WebServerGracefulShutdownLifecycle)와 같은 순서에서 멈추며, 그때 끝나는 결제 요청들처럼 알림을 넘긴다.
	 * 운영에서는 우아한 종료가 처리 중인 요청을 기다리는 동안 결제가 커밋되고, 커밋 뒤 리스너가 알림 풀에 알림을 넘긴다.
	 */
	static class PaymentsFinishingDuringGracefulShutdown implements SmartLifecycle {

		private final Executor notificationExecutor;
		private final int notificationCount;
		private final AtomicInteger sent;
		private volatile boolean running;

		PaymentsFinishingDuringGracefulShutdown(Executor notificationExecutor, int notificationCount,
			AtomicInteger sent) {
			this.notificationExecutor = notificationExecutor;
			this.notificationCount = notificationCount;
			this.sent = sent;
		}

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			IntStream.range(0, notificationCount).forEach(i -> notificationExecutor.execute(slowNotification(sent)));
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE;
		}
	}

	private static ThreadPoolTaskExecutor notificationExecutor() {
		return (ThreadPoolTaskExecutor) new AsyncConfig().notificationTaskExecutor();
	}

	/**
	 * Discord 호출처럼 {@link #SEND_TIME} 동안 걸리는 알림. 끝까지 마친 알림만 보낸 것으로 센다.
	 */
	private static Runnable slowNotification(AtomicInteger sent) {
		return () -> {
			try {
				Thread.sleep(SEND_TIME.toMillis());
				sent.incrementAndGet();
			} catch (InterruptedException e) {
				// 종료가 기다리지 않고 끊은 알림이다. 보내지 못한 것으로 두고 인터럽트 상태만 되살린다.
				Thread.currentThread().interrupt();
			}
		};
	}

	/**
	 * startSending 이 열릴 때까지 스레드를 붙잡고 있다가 {@link #SEND_TIME} 동안 걸려 보내는 알림. 붙잡고 있는 동안에는 풀의 스레드 수와
	 * 대기열 크기가 바뀌지 않으므로 종료 직전의 상태를 확인할 수 있다. 끝까지 마친 알림만 보낸 것으로 센다.
	 */
	private static Runnable slowNotificationAfter(CountDownLatch startSending, AtomicInteger sent) {
		return () -> {
			try {
				startSending.await(30, SECONDS);
				Thread.sleep(SEND_TIME.toMillis());
				sent.incrementAndGet();
			} catch (InterruptedException e) {
				// 종료가 기다리지 않고 끊은 알림이다. 보내지 못한 것으로 두고 인터럽트 상태만 되살린다.
				Thread.currentThread().interrupt();
			}
		};
	}

	/**
	 * 풀의 스레드를 붙잡아 두는 알림. 뒤 정리(@AfterEach)가 늘 풀어 주고, 풀어 주지 못해도 30초 뒤에는 스레드가 끝난다.
	 */
	private static void awaitRelease(CountDownLatch release) {
		try {
			release.await(30, SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
