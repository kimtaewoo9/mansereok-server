package com.mansereok.server.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
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

	// 사주 해석 전용 스레드 풀 ..
	@Bean(name = "gptTaskExecutor")
	public Executor gptTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(3);
		executor.setMaxPoolSize(5); // 알바 스레드 만들어서 최대 5개 스레드까지 만듦 ..
		executor.setQueueCapacity(20);
		executor.setThreadNamePrefix("GptAsync-");

		executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
			@Override
			public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
				log.error("🚨 [GPT 스레드 풀 초과] 요청 거부됨! activeCount={}, queueSize={}",
					executor.getActiveCount(), executor.getQueue().size());
			}
		});

		executor.initialize();
		return executor;
	}

	// 기본 스레드 풀 .
	@Primary
	@Bean(name = "threadPoolTaskExecutor")
	public Executor threadPoolTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);      // 평소 5개
		executor.setMaxPoolSize(10);      // 최대 10개
		executor.setQueueCapacity(100);   // 100개까지 대기
		executor.setThreadNamePrefix("DefaultAsync-");

		executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
			@Override
			public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
				log.warn("⚠️ [기본 스레드 풀 초과] 요청 거부됨! activeCount={}, queueSize={}",
					executor.getActiveCount(), executor.getQueue().size());
			}
		});

		executor.initialize();
		return executor;
	}
}
