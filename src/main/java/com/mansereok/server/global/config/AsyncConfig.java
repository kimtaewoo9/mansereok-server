package com.mansereok.server.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

	// 사주 해석 전용 스레드 풀
	@Bean(name = "gptTaskExecutor")
	public Executor gptTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(25);
		executor.setMaxPoolSize(25);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("GptAsync-");

		// 우아한 종료 적용
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);

		executor.setRejectedExecutionHandler((r, executor1) -> {
			log.error("🚨 [유료 GPT 스레드 풀 초과] 요청 거부됨. activeCount={}, queueSize={}",
				executor1.getActiveCount(), executor1.getQueue().size());

			throw new RejectedExecutionException("현재 접속자가 많아 처리가 지연되고 있습니다. 잠시 후 다시 시도해주세요.");
		});

		executor.initialize();
		return executor;
	}

	// 기본 스레드 풀 .
	@Primary
	@Bean(name = "threadPoolTaskExecutor")
	public Executor threadPoolTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(10);      // 평소 10개
		executor.setMaxPoolSize(10);
		executor.setQueueCapacity(100);   // 100개까지 대기
		executor.setThreadNamePrefix("DefaultAsync-");

		// blocking queue가 초과 되는 경우 .. abortpolicy 로 요청 거부 ..
		executor.setRejectedExecutionHandler((r, executor1) -> {
			log.error("🚨 [GPT 스레드 풀 초과] 요청 거부됨. activeCount={}, queueSize={}",
				executor1.getActiveCount(), executor1.getQueue().size());

			throw new RejectedExecutionException("GPT 서버 혼잡: 잠시 후 다시 시도해주세요.");
		});

		executor.initialize();
		return executor;
	}

	// 무료 사주 전용 대용량 스레드 풀
	@Bean(name = "gptFreeTaskExecutor")
	public Executor gptFreeTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(50);
		executor.setMaxPoolSize(200);
		executor.setQueueCapacity(1000);
		executor.setThreadNamePrefix("GptFree-");

		executor.setRejectedExecutionHandler((r, executor1) -> {
			log.error("🚨 [무료 사주 요청 거부됨] active={}, queue={}",
				executor1.getActiveCount(), executor1.getQueue().size());

			throw new RejectedExecutionException("무료 사주 요청이 폭주하고 있습니다. 잠시 후 다시 시도해주세요.");
		});

		executor.initialize();
		return executor;
	}

	// 결제 완료 Discord 알림 전용 스레드 풀.
	// 기본 풀(threadPoolTaskExecutor)은 거부 시 예외를 던지는데, AFTER_COMMIT 리스너의 @Async 제출은 커밋 스레드에서
	// 일어나므로 그 예외가 결제 완료 API 호출자까지 올라가 "DB 는 PAID 인데 500" 이 될 수 있다.
	// 그래서 알림은 풀을 분리하고, 포화 시에는 거부 대신 호출 스레드에서 실행(CallerRunsPolicy)한다.
	@Bean(name = "notificationTaskExecutor")
	public Executor notificationTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("Notify-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}
}
