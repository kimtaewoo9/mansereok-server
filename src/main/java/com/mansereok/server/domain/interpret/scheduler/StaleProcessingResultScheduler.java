package com.mansereok.server.domain.interpret.scheduler;

import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해석 중(PROCESSING)에 오래 멈춘 사주·궁합 결과를 정보 입력 대기(INPUT_REQUIRED)로 되돌린다.
 *
 * <p>해석을 시작하면 결과는 PROCESSING 이 되고, 같은 결제로 다시 시작하는 요청은 409 로 막힌다. 그런데 배포로 JVM 이 내려가 진행
 * 중이던 해석과 대기열의 해석이 버려지면, 그 결과를 되돌릴 코드가 돌지 않아 PROCESSING 이 풀리지 않는다. 사용자는 재시도도 환불도
 * 못 한다. 이 작업이 그런 결과를 주기적으로 찾아 되돌린다.
 *
 * <p>되돌리기는 표마다 조건부 UPDATE 한 번이다. 같은 순간에 해석이 끝나 완료(COMPLETED)가 된 결과는 상태 조건에 걸려 그대로
 * 남는다. 되돌린 뒤에 늦게 끝난 해석은 결과 엔티티가 저장을 거부해 버려진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleProcessingResultScheduler {

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final StaleProcessingProperties properties;
	private final Clock clock;

	/**
	 * 마지막 변경이 지금부터 staleAfter 보다 오래된 PROCESSING 결과를 되돌린다. 앞 실행이 끝난 뒤 check-interval 만큼 쉬고 다시
	 * 돈다. 한 표에서 실패해도 다른 표는 되돌리고, 실패는 다음 실행에서 다시 시도한다.
	 */
	@Scheduled(
		fixedDelayString = "${interpret.stale-processing.check-interval:PT5M}",
		initialDelayString = "${interpret.stale-processing.check-interval:PT5M}")
	public void revertStaleProcessingResults() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime staleBefore = now.minus(properties.staleAfter());

		revertAndLog("사주 결과", staleBefore,
			() -> resultRepository.revertProcessingUpdatedBefore(staleBefore, now));
		revertAndLog("궁합 결과", staleBefore,
			() -> compatibilityResultRepository.revertProcessingUpdatedBefore(staleBefore, now));
	}

	private void revertAndLog(String kind, LocalDateTime staleBefore, IntSupplier revert) {
		try {
			int reverted = revert.getAsInt();
			if (reverted > 0) {
				// 정상이라면 0 이다. 0 이 아니면 배포로 잘렸거나 해석이 멈춘 것이라 알아차릴 수 있게 WARN 으로 남긴다.
				log.warn("해석 중에 멈춘 {} {}건을 정보 입력 대기로 되돌렸다. 마지막 변경이 {} 보다 전이다.", kind, reverted,
					staleBefore);
			}
		} catch (RuntimeException e) {
			log.error("해석 중에 멈춘 {} 되돌리기 실패. 다음 실행에서 다시 시도한다.", kind, e);
		}
	}
}
