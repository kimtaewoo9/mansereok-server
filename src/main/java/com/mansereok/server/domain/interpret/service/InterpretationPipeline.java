package com.mansereok.server.domain.interpret.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mansereok.server.domain.interpret.client.BackoffSleeper;
import com.mansereok.server.domain.interpret.exception.InterpretationRunOutdatedException;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 해석 네 갈래(유료/무료 × 단일/궁합)가 공유하는 뼈대.
 *
 * <p>순서는 [초기 상태 갱신 → 알림 → GPT 호출 → 결과 저장 → 후처리] 하나뿐이고,
 * 어느 단계에서 실패해도 초기 상태를 되돌린다. 네 메서드에 같은 try/catch 가 복사돼 있던 것을 여기로 모았다.
 * 해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 다시 시작했으면({@link InterpretationRunOutdatedException})
 * 결과는 이제 이 실행의 것이 아니므로 쓰지도 되돌리지도 않는다.
 *
 * <p>되돌리기는 결과 ID 가 아니라 결제 ID 로 한다({@link ResultStatusHandler#rollbackToInitialStatus()}). 컨트롤러가 이미
 * 결과를 해석 중으로 바꿔 두었으므로, 결과 ID 를 받기 전인 첫 DB 단계(초기 상태 갱신)에서 실패해도 되돌려야 한다.
 * 되돌리기마저 실패하면 원래 오류에 suppressed 로 붙여 원래 오류를 가리지 않고, 결과는 오래 멈춘 결과를 되돌리는 작업
 * (StaleProcessingResultScheduler)이 거둔다.
 *
 * <p>상속(템플릿 메서드)이 아니라 단계를 넘겨받는 쪽을 골랐다. 단일 사주는 {@code Result},
 * 궁합은 {@code CompatibilityResult} 를 저장해서 반환 타입이 갈리는데, 템플릿 메서드로 가면
 * 흐름마다 하위 클래스를 만들어 스프링 빈으로 올려야 하고 흐름 하나를 읽으려고 상위·하위 클래스를
 * 오가야 한다. 지금 방식은 호출부에 단계가 순서대로 나열돼 흐름이 한 화면에 남는다.
 * 저장 타입이 갈리는 부분만 {@link ResultStatusHandler} 로 따로 뺐다.
 */
@Component
@Slf4j
public class InterpretationPipeline {

	/**
	 * 결과 저장이 일시적 DB 오류로 실패했을 때 다시 시도하기 전에 기다리는 시간. 첫 시도를 합쳐 최대 3번 저장을 시도한다.
	 * 이미 비용을 치른 GPT 결과는 메모리에만 있어서, 저장을 한 번에 포기하면 사용자가 같은 비용을 한 번 더 치러야 한다.
	 */
	static final List<Long> SAVE_RETRY_DELAYS_MS = List.of(200L, 400L);

	private final BackoffSleeper sleeper;

	@Autowired
	public InterpretationPipeline() {
		this(BackoffSleeper.threadSleep());
	}

	/** 테스트용 생성자. 저장 재시도 사이에 실제로 기다리지 않도록 대기를 바꿔 끼운다. */
	InterpretationPipeline(BackoffSleeper sleeper) {
		this.sleeper = sleeper;
	}

	/**
	 * 해석 한 건을 끝까지 돌린다. 실패는 로그로 남기고 밖으로 던지지 않는다. 다만 {@link Error} 는 되돌리기를 시도한 뒤 그대로
	 * 던진다.
	 *
	 * @param flowName     로그에 붙일 흐름 이름 (예: "유료 단일 사주 해석")
	 * @param resultStatus 저장 대상 엔티티마다 다른 시작·완료·되돌리기
	 * @param notification 알림 전송. 실패해도 해석은 계속한다
	 * @param gptCall      프롬프트 생성 + GPT 호출 + 응답 JSON 파싱. 정규화는 여기가 아니라 결과 저장 단계에서 한다
	 * @param postSteps    결과 저장 뒤의 곁가지(OG 이미지, 이메일). 실패해도 결과를 되돌리지 않는다
	 */
	public <R, T> void run(
		String flowName,
		ResultStatusHandler<R, T> resultStatus,
		Runnable notification,
		GptCall<R> gptCall,
		List<PostStep<T>> postSteps
	) {
		Long resultId = null;

		try {
			// 1. [DB] 초기 정보 갱신 (커넥션 즉시 반납)
			resultId = resultStatus.markInProgress();
			log.info("[{}] 초기 정보 갱신 완료 - resultId: {}", flowName, resultId);

			// 2. [Non-DB] 알림 전송
			runAndLogFailure(flowName, "알림 전송", notification);

			// 3. GPT 호출 (DB 커넥션 사용 X)
			R gptResult = gptCall.call();

			// 4. [DB] 결과 저장. 일시적 DB 오류면 짧게 기다렸다가 다시 시도한다.
			T saved = saveFinalResultWithRetry(flowName, resultStatus, resultId, gptResult);
			log.info("[{}] 결과 저장 완료 - resultId: {}", flowName, resultId);

			// 5. [Non-DB] 후처리
			for (PostStep<T> step : postSteps) {
				runAndLogFailure(flowName, step.name(), () -> step.action().accept(saved));
			}

		} catch (InterpretationRunOutdatedException e) {
			// 오래 멈춘 결과 되돌리기가 먼저 돌았다. 결과는 사용자나 다시 시작한 해석의 것이라 건드리지 않는다.
			// 정상이라면 일어나지 않으므로, 해석이 stale-after 보다 오래 걸렸다는 신호로 WARN 을 남긴다.
			log.warn("[{}] 해석을 시작한 뒤 결과가 되돌려졌거나 다시 시작돼 결과를 쓰지 않고 끝낸다 - resultId: {}, {}",
				flowName, resultId, e.getMessage());
		} catch (JsonProcessingException e) {
			// 스키마를 강제해도 파싱이 깨졌다면 응답 형식 쪽 문제라 따로 남긴다.
			rollbackQuietly(flowName, resultStatus, e);
			log.error("[{}] GPT 응답 파싱 실패 - resultId: {}", flowName, resultId, e);
		} catch (OpenAiIncompleteResponseException e) {
			// 토큰 상한 도달은 프롬프트·토큰 설정을 손봐야 한다는 신호라 따로 센다.
			// @Async 라 예외가 HTTP 응답으로 나가지 않으므로 운영에서는 이 로그로 본다.
			rollbackQuietly(flowName, resultStatus, e);
			log.error("[{}] 해석 미완성 - reason: {}, resultId: {}", flowName, e.getReason(), resultId, e);
		} catch (Exception e) {
			rollbackQuietly(flowName, resultStatus, e);
			log.error("[{}] 해석 중 오류 발생 - resultId: {}, {}", flowName, resultId, e.getMessage(), e);
		} catch (Error e) {
			// StackOverflowError 같은 Error 도 결과를 해석 중에 남기지 않도록 되돌리기를 시도한다. Error 는 이 메서드가 다룰 수
			// 있는 실패가 아니므로 삼키지 않고 그대로 던진다. @Async 의 미처리 예외 처리기가 로그를 남긴다.
			rollbackQuietly(flowName, resultStatus, e);
			throw e;
		}
	}

	/**
	 * 결과를 저장한다. 일시적 DB 오류(잠금 대기 초과·교착·쿼리 시간 초과 같은 {@link TransientDataAccessException}, 커넥션을 얻지
	 * 못한 {@link CannotCreateTransactionException})면 {@link #SAVE_RETRY_DELAYS_MS} 만큼 기다렸다가 다시 시도한다. 결과가 없거나
	 * 상태가 맞지 않는 오류는 다시 해도 같으므로 곧바로 던진다.
	 *
	 * <p>마지막 실패도 그대로 던져 run 이 상태를 되돌리게 한다. 재시도 로그에는 GPT 본문을 남기지 않는다.
	 */
	private <R, T> T saveFinalResultWithRetry(String flowName, ResultStatusHandler<R, T> resultStatus, Long resultId,
		R gptResult) {
		int maxAttempts = SAVE_RETRY_DELAYS_MS.size() + 1;
		for (int attempt = 1; ; attempt++) {
			try {
				return resultStatus.saveFinalResult(resultId, gptResult);
			} catch (TransientDataAccessException | CannotCreateTransactionException e) {
				if (attempt == maxAttempts) {
					throw e;
				}
				long delayMillis = SAVE_RETRY_DELAYS_MS.get(attempt - 1);
				log.warn("[{}] 결과 저장이 일시적 DB 오류로 실패해 {}ms 뒤 다시 시도한다 (시도 {}/{}) - resultId: {}, {}",
					flowName, delayMillis, attempt, maxAttempts, resultId, e.toString());
				waitBeforeRetry(delayMillis, e);
			}
		}
	}

	/**
	 * 재시도 전에 기다린다. 기다리는 중에 스레드가 중단되면(애플리케이션 종료 등) 더 시도하지 않고 마지막 저장 실패를 던져 상태를
	 * 되돌리게 한다. 중단 표시는 다시 세워 둔다.
	 */
	private void waitBeforeRetry(long delayMillis, NestedRuntimeException lastFailure) {
		try {
			sleeper.sleep(delayMillis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			lastFailure.addSuppressed(interrupted);
			throw lastFailure;
		}
	}

	/**
	 * 실패한 해석의 상태를 되돌린다. 되돌리기가 실패해도 밖으로 던지지 않는다. 이 실행이 실패한 이유는 원래 오류(cause)이므로,
	 * 되돌리기 실패는 cause 에 suppressed 로 붙여 원래 오류와 함께 남기고(Effective Java 아이템 77), 결과가 해석 중으로 남았다는
	 * 사실을 결제 ID 와 함께 error 로그 한 줄로 알린다. 호출한 쪽이 cause 를 로그로 남길 때 되돌리기 실패의 스택도 함께 찍힌다.
	 */
	private void rollbackQuietly(String flowName, ResultStatusHandler<?, ?> resultStatus, Throwable cause) {
		try {
			resultStatus.rollbackToInitialStatus();
		} catch (Exception rollbackFailure) {
			cause.addSuppressed(rollbackFailure);
			log.error("[{}] 상태 되돌리기도 실패해 결과가 해석 중으로 남았다. 오래 멈춘 결과를 되돌리는 작업이 거둘 때까지 다시 시도할 수 "
					+ "없다 - paymentId: {}, 되돌리기 실패: {}, 원래 오류: {}",
				flowName, resultStatus.paymentId(), rollbackFailure, cause.toString());
		}
	}

	/**
	 * 알림·OG 이미지·이메일은 해석 결과와 무관한 곁가지다. 실패해도 해석을 멈추거나 되돌리지 않는다.
	 * 다만 예외를 삼키지는 않고 반드시 남긴다 (Effective Java 아이템 77).
	 */
	private void runAndLogFailure(String flowName, String stepName, Runnable action) {
		try {
			action.run();
		} catch (Exception e) {
			log.error("[{}] {} 실패", flowName, stepName, e);
		}
	}

	/**
	 * 저장 대상 엔티티가 달라서 흐름에서 갈리는 지점.
	 *
	 * @param <R> GPT 응답 DTO
	 * @param <T> 저장된 결과 엔티티
	 */
	public interface ResultStatusHandler<R, T> {

		/** 이 해석의 결제 ID. 되돌리기의 기준이고, 되돌리기가 실패했을 때 사람이 찾아볼 수 있게 로그에 남긴다. */
		Long paymentId();

		/**
		 * 초기 상태를 갱신하고 결과 저장에 쓸 결과 ID 를 돌려준다. 이 실행이 시작한 해석이 아니면
		 * {@link InterpretationRunOutdatedException} 을 던진다.
		 */
		Long markInProgress();

		/**
		 * GPT 응답을 결과로 확정해 저장한다. 사주 경로는 여기서 본문과 요약을 정규화하므로, 정규화에서 난 예외도 이 단계의 실패로
		 * 되돌려진다. 이 실행이 시작한 해석이 아니면 {@link InterpretationRunOutdatedException} 을 던진다.
		 *
		 * <p>일시적 DB 오류면 파이프라인이 같은 인자로 최대 3번 부른다. 그래서 한 번 부를 때마다 트랜잭션 하나로 끝나야 한다.
		 */
		T saveFinalResult(Long resultId, R gptResult);

		/**
		 * 실패 시 결제 ID 로 결과를 찾아 초기 상태로 되돌린다. 결과 ID 를 받기 전(초기 상태 갱신)에 실패해도 되돌릴 수 있다. 이
		 * 실행이 시작한 해석이 아니면 아무것도 바꾸지 않는다.
		 */
		void rollbackToInitialStatus();
	}

	/**
	 * GPT 호출 단계. 이 단계에서 나올 수 있는 체크 예외는 응답 JSON 파싱의
	 * {@link JsonProcessingException} 하나뿐이라 {@code throws Exception} 대신 그것만 선언한다.
	 * 호출자가 무엇을 처리해야 하는지 숨기지 않기 위해서다 (Effective Java 아이템 74).
	 */
	@FunctionalInterface
	public interface GptCall<R> {

		R call() throws JsonProcessingException;
	}

	/** 결과 저장 뒤의 곁가지 한 개. 이름은 실패 로그에 쓴다. */
	public record PostStep<T>(String name, Consumer<T> action) {

	}
}
