package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 스레드 풀 설정값이 의도한 대로 잡히는지 본다. ThreadPoolExecutor 는 큐가 꽉 찬 뒤에야
 * core 를 넘겨 스레드를 만들기 때문에, core 와 max 가 다르면서 큐가 크면 max 가 죽은 설정이 된다.
 */
@DisplayName("AsyncConfig 스레드 풀")
class AsyncConfigTest {

	private final AsyncConfig asyncConfig = new AsyncConfig();
	private final List<ThreadPoolTaskExecutor> created = new ArrayList<>();

	@AfterEach
	void tearDown() {
		created.forEach(ThreadPoolTaskExecutor::shutdown);
		created.clear();
	}

	private ThreadPoolTaskExecutor register(Executor executor) {
		ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
		created.add(taskExecutor);
		return taskExecutor;
	}

	private int queueCapacityOf(ThreadPoolTaskExecutor executor) {
		// 큐가 비어 있을 때 remainingCapacity 는 설정한 용량과 같다.
		return executor.getThreadPoolExecutor().getQueue().remainingCapacity();
	}

	@Test
	@DisplayName("유료 GPT 풀은 core 25 / max 25 / queue 100 이다")
	void paidPoolSizes() {
		ThreadPoolTaskExecutor executor = register(asyncConfig.gptTaskExecutor());

		assertThat(executor.getCorePoolSize()).isEqualTo(25);
		assertThat(executor.getMaxPoolSize()).isEqualTo(25);
		assertThat(queueCapacityOf(executor)).isEqualTo(100);
	}

	@Test
	@DisplayName("기본 풀은 core 10 / max 10 / queue 100 이다")
	void defaultPoolSizes() {
		ThreadPoolTaskExecutor executor = register(asyncConfig.threadPoolTaskExecutor());

		assertThat(executor.getCorePoolSize()).isEqualTo(10);
		assertThat(executor.getMaxPoolSize()).isEqualTo(10);
		assertThat(queueCapacityOf(executor)).isEqualTo(100);
	}

	@Test
	@DisplayName("무료 GPT 풀은 core 50 / max 50 / queue 200 이다")
	void freePoolSizes() {
		ThreadPoolTaskExecutor executor = register(asyncConfig.gptFreeTaskExecutor());

		assertThat(executor.getCorePoolSize()).isEqualTo(50);
		assertThat(executor.getMaxPoolSize()).isEqualTo(50);
		assertThat(queueCapacityOf(executor)).isEqualTo(200);
	}

	/**
	 * 배포로 애플리케이션이 내려갈 때 두 GPT 풀은 진행 중이던 해석과 대기열의 해석을 120초까지 기다린다. 그 안에 끝나지 못한 해석의
	 * 결과 행은 PROCESSING 에 남고, StaleProcessingResultScheduler 가 staleAfter(기본 60분) 뒤에 정보 입력 대기로 되돌린다.
	 * ThreadPoolTaskExecutor 는 두 값을 읽는 메서드가 없어 필드를 직접 읽는다.
	 */
	@Test
	@DisplayName("유료·무료 GPT 풀은 종료할 때 남은 해석을 120초까지 기다린다")
	void gptPoolsWaitForTasksOnShutdown() {
		ThreadPoolTaskExecutor paid = register(asyncConfig.gptTaskExecutor());
		ThreadPoolTaskExecutor free = register(asyncConfig.gptFreeTaskExecutor());

		assertThat(shutdownSettingsOf(paid)).as("유료 GPT 풀").isEqualTo(new ShutdownSettings(true, 120_000L));
		assertThat(shutdownSettingsOf(free)).as("무료 GPT 풀").isEqualTo(new ShutdownSettings(true, 120_000L));
	}

	private record ShutdownSettings(Object waitForTasksToComplete, Object awaitTerminationMillis) {

	}

	private static ShutdownSettings shutdownSettingsOf(ThreadPoolTaskExecutor executor) {
		return new ShutdownSettings(
			ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"),
			ReflectionTestUtils.getField(executor, "awaitTerminationMillis"));
	}

	@Test
	@DisplayName("세 풀 모두 core 와 max 가 같아 max 가 죽은 설정이 되지 않는다")
	void poolsHaveSameCoreAndMax() {
		List<ThreadPoolTaskExecutor> executors = List.of(
			register(asyncConfig.gptTaskExecutor()),
			register(asyncConfig.threadPoolTaskExecutor()),
			register(asyncConfig.gptFreeTaskExecutor())
		);

		for (ThreadPoolTaskExecutor executor : executors) {
			assertThat(executor.getMaxPoolSize()).isEqualTo(executor.getCorePoolSize());
		}
	}

	/**
	 * 설정값이 아니라 실제로 core 와 큐를 모두 채워 거부까지 가 본다. 제출로 올라오는 타입은
	 * ThreadPoolTaskExecutor 가 감싼 TaskRejectedException 이고, 이것이
	 * RejectedExecutionException 의 하위 타입이라야 GlobalExceptionHandler 의 503 매핑이 걸린다.
	 * 이 PR 의 503 주장이 통째로 그 상속 관계에 얹혀 있으므로 여기서 고정한다.
	 */
	@Test
	@DisplayName("무료 풀은 core 50 + queue 200 이 실제로 차면 TaskRejectedException 으로 거부한다")
	void freePoolRejectsWhenActuallySaturated() throws InterruptedException {
		ThreadPoolTaskExecutor executor = register(asyncConfig.gptFreeTaskExecutor());
		CountDownLatch block = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(50);

		try {
			for (int i = 0; i < 50 + 200; i++) {
				executor.execute(() -> {
					started.countDown();
					try {
						block.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> executor.execute(() -> {
			}))
				.isInstanceOf(TaskRejectedException.class)
				.isInstanceOf(RejectedExecutionException.class);
		} finally {
			block.countDown();
		}
	}

	@Test
	@DisplayName("유료·무료 GPT 풀의 거부 핸들러는 풀 이름을 넣은 RejectedExecutionException 을 던진다")
	void gptPoolRejectionHandlersThrowWithPoolName() {
		ThreadPoolExecutor paid = register(asyncConfig.gptTaskExecutor()).getThreadPoolExecutor();
		ThreadPoolExecutor free = register(asyncConfig.gptFreeTaskExecutor()).getThreadPoolExecutor();

		assertThatThrownBy(() -> paid.getRejectedExecutionHandler().rejectedExecution(() -> {
		}, paid))
			.isInstanceOf(RejectedExecutionException.class)
			.hasMessage("유료 GPT 풀이 가득 차 요청을 받지 못했습니다. 잠시 후 다시 시도해주세요.");
		assertThatThrownBy(() -> free.getRejectedExecutionHandler().rejectedExecution(() -> {
		}, free))
			.isInstanceOf(RejectedExecutionException.class)
			.hasMessage("무료 GPT 풀이 가득 차 요청을 받지 못했습니다. 잠시 후 다시 시도해주세요.");
	}

	/**
	 * 기본 풀은 실행자 이름 없는 @Async(메일 발송)가 쓴다. 메일은 버리는 것보다 늦게라도 보내는 편이 나아 가득 차도 거부하지 않고,
	 * 배포로 내려갈 때는 남은 메일을 30초까지 기다린다.
	 */
	@Nested
	@DisplayName("기본 비동기 풀(메일)")
	class DefaultPool {

		private final Logger configLogger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
		private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
		private Level levelBeforeTest;

		@BeforeEach
		void captureLogs() {
			// 테스트 JVM 의 로그 설정과 상관없이 운영과 같은 INFO 에서 본다.
			levelBeforeTest = configLogger.getLevel();
			configLogger.setLevel(Level.INFO);
			logs.start();
			configLogger.addAppender(logs);
		}

		@AfterEach
		void stopCapturingLogs() {
			configLogger.detachAppender(logs);
			logs.stop();
			configLogger.setLevel(levelBeforeTest);
		}

		@Test
		@DisplayName("종료할 때 남은 메일을 30초까지 기다린다")
		void waitsForTasksOnShutdown() {
			ThreadPoolTaskExecutor executor = register(asyncConfig.threadPoolTaskExecutor());

			assertThat(shutdownSettingsOf(executor)).isEqualTo(new ShutdownSettings(true, 30_000L));
		}

		@Test
		@DisplayName("스레드 10개와 대기열 100개가 차면 거부하지 않고 제출한 스레드에서 바로 실행하며, 로그에는 GPT 가 아닌 이 풀의 이름을 남긴다")
		void runsOnSubmittingThreadWhenSaturated() throws InterruptedException {
			// given
			ThreadPoolTaskExecutor executor = register(asyncConfig.threadPoolTaskExecutor());
			CountDownLatch block = new CountDownLatch(1);
			CountDownLatch started = new CountDownLatch(10);
			AtomicReference<Thread> ranOn = new AtomicReference<>();
			try {
				fillWithBlockedTasks(executor, 10 + 100, started, block);
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

				// when
				executor.execute(() -> ranOn.set(Thread.currentThread()));

				// then
				assertThat(ranOn.get()).as("넘친 작업을 실행한 스레드").isSameAs(Thread.currentThread());
				assertThat(logMessages())
					.anySatisfy(message -> assertThat(message).contains("기본 비동기 풀(메일) 포화"))
					.noneSatisfy(message -> assertThat(message).contains("GPT"));
			} finally {
				block.countDown();
			}
		}

		@Test
		@DisplayName("종료가 시작된 뒤 들어온 작업은 말없이 버리지 않고 RejectedExecutionException 으로 알린다")
		void rejectsTaskAfterShutdownStarted() {
			// given
			ThreadPoolTaskExecutor executor = register(asyncConfig.threadPoolTaskExecutor());
			executor.shutdown();

			// when & then
			assertThatThrownBy(() -> executor.execute(() -> {
			}))
				.isInstanceOf(RejectedExecutionException.class)
				.hasRootCauseMessage("기본 비동기 풀(메일)이 종료 중이라 요청을 받지 못했습니다.");
		}

		/**
		 * 종료를 부른 스레드는 실행 중인 작업이 끝날 때까지 기다리고, 작업은 인터럽트되지 않고 끝까지 돈다. 종료 대기가 꺼져 있으면
		 * shutdown 이 실행 중인 작업을 인터럽트하고 곧바로 돌아와 이 테스트가 실패한다.
		 */
		@Test
		@DisplayName("실행 중인 메일이 있으면 종료는 그 메일이 인터럽트 없이 끝날 때까지 기다린다")
		void shutdownWaitsForRunningTask() throws Exception {
			// given
			ThreadPoolTaskExecutor executor = register(asyncConfig.threadPoolTaskExecutor());
			CountDownLatch taskStarted = new CountDownLatch(1);
			CountDownLatch releaseTask = new CountDownLatch(1);
			AtomicBoolean taskFinished = new AtomicBoolean(false);
			executor.execute(() -> {
				taskStarted.countDown();
				try {
					releaseTask.await();
					taskFinished.set(true);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

			// when: 다른 스레드에서 종료를 부르고, 종료가 시작된 것을 확인한 뒤 작업을 풀어 준다
			CompletableFuture<Boolean> finishedWhenShutdownReturned = CompletableFuture.supplyAsync(() -> {
				executor.shutdown();
				return taskFinished.get();
			});
			await().atMost(Duration.ofSeconds(5))
				.until(() -> executor.getThreadPoolExecutor().isShutdown());
			releaseTask.countDown();

			// then
			assertThat(finishedWhenShutdownReturned.get(10, TimeUnit.SECONDS))
				.as("shutdown 이 돌아온 시점에 작업이 끝까지 실행됐다").isTrue();
		}

		private List<String> logMessages() {
			return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}
	}

	private static void fillWithBlockedTasks(ThreadPoolTaskExecutor executor, int count, CountDownLatch started,
		CountDownLatch block) {
		for (int i = 0; i < count; i++) {
			executor.execute(() -> {
				started.countDown();
				try {
					block.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}
	}
}
