package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.exception.InterpretationRunOutdatedException;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해석 실행(InterpretationPipeline)이 결과를 쓰는 DB 메서드.
 *
 * <p>모든 메서드가 해석을 시작한 시각(startedAt, ResultService.startProcessing 이 돌려준 값)을 받는다. 행을 잠가 읽은 뒤 그 시각이
 * 결과에 그대로 남아 있는지(isProcessingStartedAt) 보고, 다르면 쓰지 않는다. 오래 멈춰 정보 입력 대기로 되돌려졌거나, 그 뒤 같은
 * 결제로 해석을 다시 시작한 결과가 여기에 걸린다. 잠금은 확인과 쓰기 사이에 되돌리기나 다른 요청의 해석 시작이 끼어들지 못하게 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SajuResultService {

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// ==========================================
	// 1. 기본/무료 사주용 DB 메서드
	// ==========================================

	/**
	 * 이 실행이 시작한 해석이면 입력 정보를 채운다.
	 *
	 * @throws InterpretationRunOutdatedException 해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 다시 시작했을 때
	 */
	@Transactional
	public Result updateInitialStatus(Long paymentId, LocalDateTime startedAt, String name,
		ManseryeokCalculationResponse response, String ilgan) {
		Result result = resultRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(EntityNotFoundException::new);
		requireStartedAt(result, startedAt);

		result.updateInformation(
			name,
			response.getInput().getSolarDate(),
			response.getInput().getSolarTime(),
			response.getInput().getGender(),
			response.getInput().getIsLunar(),
			ilgan
		);
		return resultRepository.save(result);
	}

	/**
	 * 이 실행이 시작한 해석이면 본문과 요약을 넣고 완료로 바꾼다.
	 *
	 * @throws InterpretationRunOutdatedException 해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 다시 시작했을 때. 다시 시작한
	 *                                            결과의 입력 정보에 이 실행의 본문이 붙지 않게 한다.
	 */
	@Transactional
	public Result saveFinalResult(Long resultId, LocalDateTime startedAt, String fullAnalysis, String summary) {
		Result result = resultRepository.findByIdForUpdate(resultId)
			.orElseThrow(() -> new EntityNotFoundException("Result not found: " + resultId));
		requireStartedAt(result, startedAt);

		result.completeInterpretation(fullAnalysis, summary);
		return resultRepository.save(result);
	}

	/**
	 * 이 실행이 시작한 해석이면 정보 입력 대기로 되돌린다. 되돌려졌거나 다른 요청이 다시 시작한 결과, 완료된 결과는 그대로 둔다.
	 * resultId 가 null 이면(결과 ID 를 받기 전에 실패) 아무것도 하지 않는다.
	 */
	@Transactional
	public void rollbackStatus(Long resultId, LocalDateTime startedAt) {
		if (resultId == null) {
			return;
		}
		resultRepository.findByIdForUpdate(resultId)
			.filter(result -> result.isProcessingStartedAt(startedAt))
			.ifPresent(Result::revertToInputRequired);
	}

	// ==========================================
	// 2. 궁합 분석용 DB 메서드 (추가됨)
	// ==========================================

	/**
	 * 이 실행이 시작한 궁합 해석이면 두 사람의 이름과 일간을 채운다.
	 *
	 * @throws InterpretationRunOutdatedException 해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 다시 시작했을 때
	 */
	@Transactional
	public CompatibilityResult updateCompatibilityInitialStatus(Long paymentId, LocalDateTime startedAt, String p1Name,
		String p1Ilgan, String p2Name, String p2Ilgan) {
		CompatibilityResult result = compatibilityResultRepository.findByPaymentIdForUpdate(paymentId)
			.orElseThrow(EntityNotFoundException::new);
		requireStartedAt(result, startedAt);

		result.updatePersonsInformation(p1Name, p1Ilgan, p2Name, p2Ilgan);
		return compatibilityResultRepository.save(result);
	}

	/**
	 * 이 실행이 시작한 궁합 해석이면 본문·점수·요약을 넣고 완료로 바꾼다.
	 *
	 * @throws InterpretationRunOutdatedException 해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 다시 시작했을 때
	 */
	@Transactional
	public CompatibilityResult saveCompatibilityFinalResult(Long resultId, LocalDateTime startedAt,
		String interpretation, Integer score, String summary) {
		CompatibilityResult result = compatibilityResultRepository.findByIdForUpdate(resultId)
			.orElseThrow(
				() -> new EntityNotFoundException("CompatibilityResult not found: " + resultId));
		requireStartedAt(result, startedAt);

		result.completeInterpretation(interpretation, score, summary);
		return compatibilityResultRepository.save(result);
	}

	/** 이 실행이 시작한 궁합 해석이면 정보 입력 대기로 되돌린다. rollbackStatus 와 같은 규칙이다. */
	@Transactional
	public void rollbackCompatibilityStatus(Long resultId, LocalDateTime startedAt) {
		if (resultId == null) {
			return;
		}
		compatibilityResultRepository.findByIdForUpdate(resultId)
			.filter(result -> result.isProcessingStartedAt(startedAt))
			.ifPresent(CompatibilityResult::revertToInputRequired);
	}

	private static void requireStartedAt(Result result, LocalDateTime startedAt) {
		if (!result.isProcessingStartedAt(startedAt)) {
			throw new InterpretationRunOutdatedException(result.getPaymentId(), startedAt, result.getStatus(),
				result.getUpdatedAt());
		}
	}

	private static void requireStartedAt(CompatibilityResult result, LocalDateTime startedAt) {
		if (!result.isProcessingStartedAt(startedAt)) {
			throw new InterpretationRunOutdatedException(result.getPaymentId(), startedAt, result.getStatus(),
				result.getUpdatedAt());
		}
	}
}
