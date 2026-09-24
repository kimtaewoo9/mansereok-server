package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

	@Test
	@DisplayName("풀이 포화되면 RejectedExecutionException 을 던진다 (컨트롤러에서 503 으로 매핑)")
	void rejectsWhenSaturated() {
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
