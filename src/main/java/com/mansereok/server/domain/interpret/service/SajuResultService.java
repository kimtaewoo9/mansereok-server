package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SajuResultService {

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// ==========================================
	// 1. 기본/무료 사주용 DB 메서드
	// ==========================================

	@Transactional
	public Result updateInitialStatus(Long paymentId, String name,
		ManseryeokCalculationResponse response, String ilgan) {
		Result result = resultRepository.findByPaymentId(paymentId)
			.orElseThrow(EntityNotFoundException::new);

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

	@Transactional
	public Result saveFinalResult(Long resultId, String fullAnalysis, String summary) {
		Result result = resultRepository.findById(resultId)
			.orElseThrow(() -> new EntityNotFoundException("Result not found: " + resultId));
		result.completeInterpretation(fullAnalysis, summary);
		return resultRepository.save(result);
	}

	@Transactional
	public void rollbackStatus(Long resultId) {
		if (resultId == null) {
			return;
		}
		Result result = resultRepository.findById(resultId).orElse(null);
		if (result != null && result.getStatus() == ResultStatus.PROCESSING) {
			result.setStatus(ResultStatus.INPUT_REQUIRED);
			resultRepository.save(result);
		}
	}

	// ==========================================
	// 2. 궁합 분석용 DB 메서드 (추가됨)
	// ==========================================

	@Transactional
	public CompatibilityResult updateCompatibilityInitialStatus(Long paymentId, String p1Name,
		String p1Ilgan, String p2Name, String p2Ilgan) {
		CompatibilityResult result = compatibilityResultRepository.findByPaymentId(paymentId)
			.orElseThrow(EntityNotFoundException::new);

		result.updatePersonsInformation(p1Name, p1Ilgan, p2Name, p2Ilgan);
		return compatibilityResultRepository.save(result);
	}

	@Transactional
	public CompatibilityResult saveCompatibilityFinalResult(Long resultId, String interpretation,
		Integer score, String summary) {
		CompatibilityResult result = compatibilityResultRepository.findById(resultId)
			.orElseThrow(
				() -> new EntityNotFoundException("CompatibilityResult not found: " + resultId));

		result.completeInterpretation(interpretation, score, summary);
		return compatibilityResultRepository.save(result);
	}

	@Transactional
	public void rollbackCompatibilityStatus(Long resultId) {
		if (resultId == null) {
			return;
		}
		CompatibilityResult result = compatibilityResultRepository.findById(resultId).orElse(null);
		if (result != null && result.getStatus() == ResultStatus.PROCESSING) {
			result.setStatus(ResultStatus.INPUT_REQUIRED);
			compatibilityResultRepository.save(result);
		}
	}
}
