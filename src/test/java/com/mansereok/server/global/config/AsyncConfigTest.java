package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
	@DisplayName("세 풀의 거부 핸들러는 모두 RejectedExecutionException 을 던진다")
	void everyPoolRejectionHandlerThrows() {
		List<ThreadPoolTaskExecutor> executors = List.of(
			register(asyncConfig.gptTaskExecutor()),
			register(asyncConfig.threadPoolTaskExecutor()),
			register(asyncConfig.gptFreeTaskExecutor())
		);

		for (ThreadPoolTaskExecutor executor : executors) {
			ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
			Runnable task = () -> {
			};

			assertThatThrownBy(() -> pool.getRejectedExecutionHandler()
				.rejectedExecution(task, pool))
				.isInstanceOf(RejectedExecutionException.class);
		}
	}
}
