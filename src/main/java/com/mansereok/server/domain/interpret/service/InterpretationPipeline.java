package com.mansereok.server.domain.interpret.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 해석 네 갈래(유료/무료 × 단일/궁합)가 공유하는 뼈대.
 *
 * <p>순서는 [초기 상태 갱신 → 알림 → GPT 호출 → 결과 저장 → 후처리] 하나뿐이고,
 * 실패하면 초기 상태를 되돌린다. 네 메서드에 같은 try/catch 가 복사돼 있던 것을 여기로 모았다.
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
	 * @param flowName    로그에 붙일 흐름 이름 (예: "유료 단일 사주 해석")
	 * @param resultStatus   저장 대상 엔티티마다 다른 시작·완료·롤백
	 * @param notification 알림 전송. 실패해도 해석은 계속한다
	 * @param gptCall     프롬프트 생성 + GPT 호출 + 파싱 + 정규화
	 * @param postSteps   결과 저장 뒤의 곁가지(OG 이미지, 이메일). 실패해도 결과를 되돌리지 않는다
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

			// 4. [DB] 결과 저장
			T saved = resultStatus.saveFinalResult(resultId, gptResult);
			log.info("[{}] 결과 저장 완료 - resultId: {}", flowName, resultId);

			// 5. [Non-DB] 후처리
			for (PostStep<T> step : postSteps) {
				runAndLogFailure(flowName, step.name(), () -> step.action().accept(saved));
			}

		} catch (JsonProcessingException e) {
			// 스키마를 강제해도 파싱이 깨졌다면 응답 형식 쪽 문제라 따로 남긴다.
			log.error("[{}] GPT 응답 파싱 실패 - resultId: {}", flowName, resultId, e);
			resultStatus.rollbackToInitialStatus(resultId);
		} catch (OpenAiIncompleteResponseException e) {
			// 토큰 상한 도달은 프롬프트·토큰 설정을 손봐야 한다는 신호라 따로 센다.
			// @Async 라 예외가 HTTP 응답으로 나가지 않으므로 운영에서는 이 로그로 본다.
			log.error("[{}] 해석 미완성 - reason: {}, resultId: {}", flowName, e.getReason(), resultId);
			resultStatus.rollbackToInitialStatus(resultId);
		} catch (Exception e) {
			log.error("[{}] 해석 중 오류 발생: {}", flowName, e.getMessage(), e);
			resultStatus.rollbackToInitialStatus(resultId);
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
	 * 저장 대상 엔티티가 달라서 흐름에서 갈리는 세 지점.
	 *
	 * @param <R> GPT 응답 DTO
	 * @param <T> 저장된 결과 엔티티
	 */
	public interface ResultStatusHandler<R, T> {

		/** 초기 상태를 갱신하고 롤백에 쓸 결과 ID 를 돌려준다. */
		Long markInProgress();

		/** GPT 응답을 결과로 확정해 저장한다. */
		T saveFinalResult(Long resultId, R gptResult);

		/** 실패 시 초기 상태로 되돌린다. resultId 가 null 이어도 안전해야 한다. */
		void rollbackToInitialStatus(Long resultId);
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
