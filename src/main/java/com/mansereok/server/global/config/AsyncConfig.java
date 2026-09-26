package com.mansereok.server.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 스레드 풀 설정.
 *
 * <p>유료·무료 GPT 풀은 포화 시 RejectedExecutionException 을 던진다. 두 풀에 해석을 넘기는 곳은 요청 스레드(컨트롤러)라
 * 이 예외는 컨트롤러까지 올라오고, GlobalExceptionHandler 가 503 으로 내려 준다("잠시 후 다시 시도").
 * 실제로 올라오는 타입은 ThreadPoolTaskExecutor 가 감싼 TaskRejectedException 인데,
 * RejectedExecutionException 의 하위 타입이라 같은 핸들러가 받는다.
 * 컨트롤러는 거부를 잡아 PROCESSING 으로 바꿔 둔 결과 상태를 되돌린 뒤 예외를 다시 던진다.
 *
 * <p>기본 풀은 실행자 이름 없는 @Async 가 쓰고, 지금은 EmailService 의 메일 발송(비밀번호 재설정, 소셜 로그인 안내 등)뿐이다.
 * 메일은 버리는 것보다 늦게라도 보내는 편이 나아서, 포화되면 거부하지 않고 제출한 스레드(지금은 요청 스레드)에서 바로 보낸다.
 * OG 이미지는 이 풀을 쓰지 않고 해석 작업 스레드에서 결과를 저장한 직후 바로 만든다.
 *
 * <p>종료할 때 GPT 두 풀은 남은 해석을 120초까지, 기본 풀은 남은 메일을 30초까지 기다린다. GPT 풀의 기다림은 최선을 다하는
 * 것일 뿐이라 그 안에 끝나지 못한 해석의 결과는 PROCESSING 에 남는다. 이런 결과는 오래 멈춘 결과를 되돌리는 작업
 * (StaleProcessingResultScheduler)이 정보 입력 대기로 되돌린다.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

	static final String PAID_GPT_POOL_LABEL = "유료 GPT 풀";
	static final String DEFAULT_POOL_LABEL = "기본 비동기 풀(메일)";
	static final String FREE_GPT_POOL_LABEL = "무료 GPT 풀";

	// 사주 해석 전용 스레드 풀
	@Bean(name = "gptTaskExecutor")
	public Executor gptTaskExecutor() {
		ThreadPoolTaskExecutor executor = boundedPool(PAID_GPT_POOL_LABEL, "GptAsync-", 25, 100);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);
		executor.initialize();
		return executor;
	}

	/**
	 * 기본 풀. 실행자 이름 없는 @Async(지금은 메일 발송뿐)가 쓴다.
	 *
	 * <p>가득 차면 거부하지 않고 제출한 스레드에서 바로 실행한다. 종료할 때는 대기 중인 메일까지 30초 기다린다.
	 */
	@Primary
	@Bean(name = "threadPoolTaskExecutor")
	public Executor threadPoolTaskExecutor() {
		ThreadPoolTaskExecutor executor = boundedPool(DEFAULT_POOL_LABEL, "DefaultAsync-", 10, 100);
		executor.setRejectedExecutionHandler(runOnSubmittingThread(DEFAULT_POOL_LABEL));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}

	/**
	 * 무료 사주 전용 스레드 풀. 무료 단일과 무료 궁합을 함께 받는다.
	 *
	 * <p>ThreadPoolExecutor 는 큐가 꽉 찬 뒤에야 core 를 넘겨 스레드를 만든다. 그래서 core 보다 큰 max 는 큐가 다 찰 때까지
	 * 아무 효과가 없고, 그동안 큐에만 쌓여 대기 시간이 길어진다(OpenAI 호출은 건당 수십 초다). 그래서 동시 호출 수를 50 으로
	 * 못박고(core = max = 50), 큐는 200 으로 두어 감당 못 할 양은 빨리 거부한다. 늘리고 싶으면 core 와 max 를 같이 올려야
	 * 한다. max 만 올리는 것은 효과가 없다.
	 *
	 * <p>종료 대기는 유료 풀과 같다. 기다리는 동안 끝나지 못한 해석의 결과는 PROCESSING 에 남고, 오래 멈춘 결과를 되돌리는 작업이
	 * 거둔다.
	 */
	@Bean(name = "gptFreeTaskExecutor")
	public Executor gptFreeTaskExecutor() {
		ThreadPoolTaskExecutor executor = boundedPool(FREE_GPT_POOL_LABEL, "GptFree-", 50, 200);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);
		executor.initialize();
		return executor;
	}

	/**
	 * 스레드 수가 size 로 고정되고(core = max) 큐가 queueCapacity 인 풀을 만든다. 가득 차면 label 을 넣은 로그를 남기고
	 * RejectedExecutionException 을 던진다. 종료 규칙과 initialize 는 부르는 쪽이 정한다.
	 *
	 * @param label        로그와 예외 문구에 넣을 풀 이름
	 * @param threadPrefix 스레드 이름 앞부분
	 */
	private static ThreadPoolTaskExecutor boundedPool(String label, String threadPrefix, int size, int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(size);
		executor.setMaxPoolSize(size);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix(threadPrefix);
		executor.setRejectedExecutionHandler((task, pool) -> {
			log.error("🚨 [{} 포화] 요청 거부됨. activeCount={}, queueSize={}",
				label, pool.getActiveCount(), pool.getQueue().size());

			throw new RejectedExecutionException(label + "이 가득 차 요청을 받지 못했습니다. 잠시 후 다시 시도해주세요.");
		});
		return executor;
	}

	/**
	 * 풀이 가득 차면 제출한 스레드에서 바로 실행한다(CallerRunsPolicy 와 같다). 다만 CallerRunsPolicy 는 종료 중인 풀에 온
	 * 작업을 말없이 버리므로, 그때는 로그를 남기고 RejectedExecutionException 을 던져 제출한 쪽이 알게 한다.
	 */
	private static RejectedExecutionHandler runOnSubmittingThread(String label) {
		return (task, pool) -> {
			if (pool.isShutdown()) {
				log.error("🚨 [{} 종료 중] 요청 거부됨.", label);
				throw new RejectedExecutionException(label + "이 종료 중이라 요청을 받지 못했습니다.");
			}
			log.warn("[{} 포화] 제출한 스레드에서 바로 실행한다. activeCount={}, queueSize={}",
				label, pool.getActiveCount(), pool.getQueue().size());
			task.run();
		};
	}
}
