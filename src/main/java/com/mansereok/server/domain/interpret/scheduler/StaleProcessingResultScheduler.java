package com.mansereok.server.domain.interpret.scheduler;

import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
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
 * <p>되돌리기는 표마다 두 단계다. 오래 멈춘 결과의 ID 를 잠그지 않고 읽은 뒤, 한 행씩 기본 키로 조건부 UPDATE 를 보낸다. 범위
 * UPDATE 한 문장으로 하면 범위 바로 뒤의 아직 도는 해석까지 잠그려 해 그 해석의 결과 저장과 교착한다(자세한 내용은
 * ResultRepository.findIdsProcessingUpdatedBefore). 읽은 뒤 같은 순간에 해석이 끝나 완료(COMPLETED)가 된 결과는 상태 조건에 걸려 그대로
 * 남는다. 되돌리기는 updated_at 을 지금으로 바꾸므로, 되돌린 뒤에 늦게 끝난 해석은 자기가 해석을 시작한 시각이 결과에 남은 값과
 * 달라 결과를 쓰지 않는다(SajuResultService). 그사이 사용자가 같은 결제로 해석을 다시 시작했어도 그 결과를 덮어쓰거나 되돌리지 않는다.
 *
 * <p>한 행씩 자기 트랜잭션으로 되돌리므로, 한 행이 실패해도 앞서 되돌린 행은 이미 커밋돼 있다. 그래서 실패한 행은 결과 ID 를 ERROR 로
 * 남기고 다음 행으로 넘어가며, 실제로 되돌린 건수는 표마다 끝에 WARN 으로 남긴다. 실패한 행은 다음 실행에서 다시 시도한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleProcessingResultScheduler {

	/** 실행 간격. 키가 없으면 StaleProcessingProperties 의 기본값과 같은 문자열을 쓴다. */
	private static final String CHECK_INTERVAL = "${interpret.stale-processing.check-interval:"
		+ StaleProcessingProperties.DEFAULT_CHECK_INTERVAL_TEXT + "}";

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final StaleProcessingProperties properties;
	private final Clock clock;

	/**
	 * 해석을 시작한 지 staleAfter 보다 오래된 PROCESSING 결과를 되돌린다. 앞 실행이 끝난 뒤 check-interval 만큼 쉬고 다시
	 * 돈다. 한 표나 한 행에서 실패해도 나머지는 되돌리고, 실패는 다음 실행에서 다시 시도한다.
	 */
	@Scheduled(
		fixedDelayString = CHECK_INTERVAL,
		initialDelayString = CHECK_INTERVAL)
	public void revertStaleProcessingResults() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime staleBefore = now.minus(properties.staleAfter());

		revertAndLog("사주 결과", staleBefore,
			() -> resultRepository.findIdsProcessingUpdatedBefore(staleBefore),
			id -> resultRepository.revertIfProcessingUpdatedBefore(id, staleBefore, now));
		revertAndLog("궁합 결과", staleBefore,
			() -> compatibilityResultRepository.findIdsProcessingUpdatedBefore(staleBefore),
			id -> compatibilityResultRepository.revertIfProcessingUpdatedBefore(id, staleBefore, now));
	}

	/**
	 * 오래 멈춘 결과의 ID 를 읽어 한 행씩 되돌리고, 실제로 되돌린 건수를 남긴다. ID 를 읽지 못하면 이 표는 이번 실행에서 건너뛴다. 한
	 * 행이 실패하면 그 결과 ID 를 남기고 다음 행을 되돌린다.
	 */
	private void revertAndLog(String kind, LocalDateTime staleBefore, Supplier<List<Long>> findStaleIds,
		ToIntFunction<Long> revertOne) {
		List<Long> staleIds;
		try {
			staleIds = findStaleIds.get();
		} catch (RuntimeException e) {
			log.error("해석 중에 멈춘 {} 조회 실패. 다음 실행에서 다시 시도한다.", kind, e);
			return;
		}

		int reverted = 0;
		for (Long id : staleIds) {
			try {
				reverted += revertOne.applyAsInt(id);
			} catch (RuntimeException e) {
				log.error("해석 중에 멈춘 {} id={} 되돌리기 실패. 다음 실행에서 다시 시도한다.", kind, id, e);
			}
		}
		if (reverted > 0) {
			// 정상이라면 0 이다. 0 이 아니면 배포로 잘렸거나 해석이 멈춘 것이라 알아차릴 수 있게 WARN 으로 남긴다.
			log.warn("해석 중에 멈춘 {} {}건을 정보 입력 대기로 되돌렸다. 마지막 변경이 {} 보다 전이다.", kind, reverted,
				staleBefore);
		}
	}
}
