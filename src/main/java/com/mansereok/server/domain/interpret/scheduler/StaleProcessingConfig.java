package com.mansereok.server.domain.interpret.scheduler;

import com.mansereok.server.domain.interpret.client.OpenAiProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 오래 멈춘 해석 결과를 되돌리는 작업의 설정을 등록하고, 기동할 때 그 설정이 OpenAI 호출 설정과 맞는지 확인한다.
 *
 * <p>멈춘 것으로 보는 시간(staleAfter)이 OpenAI 호출 한 건이 가장 오래 걸리는 시간보다 짧으면, 아직 정상으로 도는 해석을 되돌려
 * 그 해석의 결과를 버리고 사용자가 같은 결제로 해석을 한 번 더 시작할 수 있게 된다. 그래서 그런 설정이면 애플리케이션을 띄우지
 * 않는다. 대기열에서 기다리는 시간은 셈에 넣지 않았으므로 staleAfter 는 이 값보다 넉넉히 잡는다(기본 60분).
 */
@Configuration
@EnableConfigurationProperties(StaleProcessingProperties.class)
public class StaleProcessingConfig {

	/**
	 * 재시도 사이 대기 한 번의 상한. OpenAiResponsesRestClient 가 지수 백오프와 Retry-After 를 모두 이 값(MAX_RETRY_DELAY_MS
	 * 30초)으로 자른다.
	 */
	private static final Duration LONGEST_RETRY_WAIT = Duration.ofSeconds(30);

	public StaleProcessingConfig(StaleProcessingProperties staleProcessing, OpenAiProperties openAi) {
		Duration longestCall = longestOpenAiCall(openAi);
		if (staleProcessing.staleAfter().compareTo(longestCall) <= 0) {
			throw new IllegalStateException(String.format(
				"interpret.stale-processing.stale-after(%s)가 OpenAI 호출 한 건이 가장 오래 걸리는 시간(%s)보다 길지 않다. "
					+ "이대로면 아직 도는 해석을 되돌린다. stale-after 를 늘리거나 openai.api 의 제한 시간·시도 횟수를 줄인다.",
				staleProcessing.staleAfter(), longestCall));
		}
	}

	/**
	 * OpenAI 호출 한 건이 가장 오래 걸리는 시간. 해석 한 건은 createResponse 를 한 번 부르고, 그 안에서 maxAttempts 번 시도한 뒤
	 * fallback 모델로 한 번 더 부른다. 시도마다 연결 제한과 읽기 제한을 모두 쓰고, 재시도 사이(maxAttempts - 1 번)마다 대기 상한만큼
	 * 기다린다고 본다. 기본값이면 (10초 + 180초) × 4 + 30초 × 2 = 820초(13분 40초)다.
	 */
	static Duration longestOpenAiCall(OpenAiProperties openAi) {
		Duration oneAttempt = Duration.ofMillis((long) openAi.connectTimeoutMs() + openAi.readTimeoutMs());
		int attemptsWithFallback = openAi.maxAttempts() + 1;
		int waitsBetweenRetries = openAi.maxAttempts() - 1;
		return oneAttempt.multipliedBy(attemptsWithFallback)
			.plus(LONGEST_RETRY_WAIT.multipliedBy(waitsBetweenRetries));
	}
}
