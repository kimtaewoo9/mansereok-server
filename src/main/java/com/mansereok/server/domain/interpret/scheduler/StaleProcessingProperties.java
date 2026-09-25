package com.mansereok.server.domain.interpret.scheduler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 해석 중(PROCESSING)에 오래 멈춘 결과를 정보 입력 대기로 되돌리는 작업의 설정. yml 에 키가 없어도 기본값으로 동작한다.
 *
 * <p>값은 5m, 60m 같은 짧은 형식이나 PT5M 같은 ISO-8601 형식으로 적는다. 되돌리기 작업의 실행 간격은 스케줄러가 같은 키
 * (interpret.stale-processing.check-interval)를 직접 읽는다.
 *
 * @param staleAfter    결과가 마지막으로 바뀐 뒤 이 시간이 지나도록 해석 중이면 멈춘 것으로 보고 되돌린다. 기본 60분. OpenAI 호출
 *                      한 건이 가장 오래 걸리는 시간보다 길어야 하며, 짧으면 StaleProcessingConfig 가 기동을 멈춘다.
 * @param checkInterval 되돌리기 작업을 도는 간격. 기본 5분.
 */
@ConfigurationProperties(prefix = "interpret.stale-processing")
public record StaleProcessingProperties(
	Duration staleAfter,
	Duration checkInterval
) {

	private static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(60);
	private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofMinutes(5);

	public StaleProcessingProperties {
		if (staleAfter == null) {
			staleAfter = DEFAULT_STALE_AFTER;
		}
		if (checkInterval == null) {
			checkInterval = DEFAULT_CHECK_INTERVAL;
		}
		if (!staleAfter.isPositive()) {
			throw new IllegalArgumentException("interpret.stale-processing.stale-after 는 0 보다 커야 합니다: " + staleAfter);
		}
		if (!checkInterval.isPositive()) {
			throw new IllegalArgumentException(
				"interpret.stale-processing.check-interval 은 0 보다 커야 합니다: " + checkInterval);
		}
	}
}
