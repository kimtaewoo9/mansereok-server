package com.mansereok.server.domain.interpret.scheduler;

import com.mansereok.server.domain.interpret.client.OpenAiProperties;
import com.mansereok.server.domain.interpret.client.OpenAiResponsesRestClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 오래 멈춘 해석 결과를 되돌리는 작업의 설정을 등록하고, 기동할 때 그 설정이 OpenAI 호출 설정과 맞는지 확인한다.
 *
 * <p>멈춘 것으로 보는 시간(staleAfter)이 OpenAI 호출 한 건이 가장 오래 걸리는 시간보다 짧으면, 아직 정상으로 도는 해석을 되돌린다.
 * 그 해석은 끝나도 결과를 쓰지 않으므로(SajuResultService 가 해석을 시작한 시각을 비교한다) GPT 비용이 버려지고, 사용자는 해석을
 * 다시 시작해야 한다. 그래서 그런 설정이면 애플리케이션을 띄우지 않는다.
 *
 * <p>이 검사는 되돌린 뒤의 늦은 저장을 막는 장치가 아니라 헛된 되돌리기를 줄이는 장치다. 대기열에서 기다린 시간과 GPT 호출 앞의
 * Discord 알림(제한 시간이 없는 동기 호출)은 셈에 넣지 않았으므로 staleAfter 는 이 값보다 넉넉히 잡는다(기본 60분).
 */
@Configuration
@EnableConfigurationProperties(StaleProcessingProperties.class)
public class StaleProcessingConfig {

	public StaleProcessingConfig(StaleProcessingProperties staleProcessing, OpenAiProperties openAi) {
		Duration longestCall = OpenAiResponsesRestClient.longestCall(openAi);
		if (staleProcessing.staleAfter().compareTo(longestCall) <= 0) {
			throw new IllegalStateException(String.format(
				"interpret.stale-processing.stale-after(%s)가 OpenAI 호출 한 건이 가장 오래 걸리는 시간(%s)보다 길지 않다. "
					+ "이대로면 아직 도는 해석을 되돌린다. stale-after 를 늘리거나 openai.api 의 제한 시간·시도 횟수를 줄인다.",
				staleProcessing.staleAfter(), longestCall));
		}
	}
}
