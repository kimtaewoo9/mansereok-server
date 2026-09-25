package com.mansereok.server.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 스레드 풀 설정.
 *
 * <p>세 풀 모두 포화 시 RejectedExecutionException 을 던진다. @Async 메서드의 제출은
 * 호출 스레드(= 요청 스레드)에서 일어나므로 이 예외는 컨트롤러까지 올라오고,
 * GlobalExceptionHandler 가 503 으로 내려 준다("잠시 후 다시 시도").
 * 실제로 올라오는 타입은 ThreadPoolTaskExecutor 가 감싼 TaskRejectedException 인데,
 * RejectedExecutionException 의 하위 타입이라 같은 핸들러가 받는다.
 * 컨트롤러는 거부를 잡아 PROCESSING 으로 바꿔 둔 결과 상태를 되돌린 뒤 예외를 다시 던진다.
 */
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

	/**
	 * 무료 사주 전용 스레드 풀.
	 *
	 * <p>예전 설정은 core 50 / max 200 / queue 1000 이었다. 그런데 ThreadPoolExecutor 는
	 * 큐가 꽉 찬 뒤에야 core 를 넘겨 스레드를 만들기 때문에, 큐 1000 이 다 차기 전에는
	 * 동시 실행이 절대 50 을 넘지 않는다. max 200 은 사실상 죽은 설정이었고, 대신 큐에서만
	 * 1000 건이 쌓여 대기 시간이 길어진다(OpenAI 호출은 건당 수십 초다).
	 *
	 * <p>그래서 실제로 돌아가던 값인 50 을 동시 호출 수로 못박고(core = max = 50),
	 * 큐는 200 으로 줄여 감당 못 할 양은 빨리 거부하게 했다. 늘리고 싶으면 core 와 max 를
	 * 같이 올려야 한다. max 만 올리는 것은 효과가 없다.
	 */
	@Bean(name = "gptFreeTaskExecutor")
	public Executor gptFreeTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(50);
		executor.setMaxPoolSize(50);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("GptFree-");

		// 이 풀은 무료 단일과 무료 궁합을 함께 받는다. 배포로 잘린 해석의 결과 행이
		// PROCESSING 에 남지 않도록 유료 풀과 같은 우아한 종료를 건다.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);

		executor.setRejectedExecutionHandler((r, executor1) -> {
			log.error("🚨 [무료 사주 요청 거부됨] active={}, queue={}",
				executor1.getActiveCount(), executor1.getQueue().size());

			throw new RejectedExecutionException("무료 사주 요청이 폭주하고 있습니다. 잠시 후 다시 시도해주세요.");
		});

		executor.initialize();
		return executor;
	}
}
