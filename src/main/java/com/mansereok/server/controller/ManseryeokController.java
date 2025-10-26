package com.mansereok.server.controller;

import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Result;
import com.mansereok.server.entity.ResultStatus;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.service.ManseCalculationService;
import com.mansereok.server.service.ManseInterpretationService;
import com.mansereok.server.service.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.service.request.ManseInterpretationRequest;
import com.mansereok.server.service.request.ManseryeokCalculationRequest;
import com.mansereok.server.service.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ManseInterpretationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ManseryeokController {

	private final ManseCalculationService manseCalculationService;
	private final ManseInterpretationService manseInterpretationService;

	private final ResultRepository resultRepository; // 직접 주입
	private final CompatibilityResultRepository compatibilityResultRepository; // 직접 주입

	@PostMapping("/api/v1/manseryeok/calculate")
	public ResponseEntity<ManseryeokCalculationResponse> calculate(
		@Valid @RequestBody ManseryeokCalculationRequest request
	) {
		log.info("만세력 계산 요청: solarDate={}, gender={}, isLunar={}",
			request.getSolarDate(), request.getGender(), request.getIsLunar());
		ManseryeokCalculationResponse response = manseCalculationService.calculate(request);
		log.info("만세력 계산 완료: daySky={}", response.getSaju().getDaySky().getChinese());

		return ResponseEntity.ok(response);
	}

	@PostMapping("/api/v1/manseryeok/interpret/{subcategoryId}")
	public ResponseEntity<ManseInterpretationResponse> interpret(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseInterpretationRequest request,
		@AuthenticationPrincipal String username
	) {
		log.info("만세력 해석 요청 username: " + username);

		// 상태 업데이트 로직
		try {
			updateResultStatusToProcessing(request.getPaymentId());
		} catch (EntityNotFoundException e) {
			log.error("해석 시작 전 상태 업데이트 실패: {}", e.getMessage());
			return ResponseEntity.status(404).body(null); // 예시 응답
		}

		// 1. 만세력 데이터 계산
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				request.getName(),
				request.getSolarDate(),
				request.getSolarTime(),
				request.getGender(),
				request.getIsLunar()
			)
		);

		// 2. 계산된 만세력으로 해석 시작.
		ManseInterpretationResponse response = manseInterpretationService.interpret(
			request.getName(),
			manse,
			username,
			subcategoryId,
			request.getPaymentId()
		);

		return ResponseEntity.ok(response);
	}

	@PostMapping("/api/v1/manseryeok/interpret/compatibility/{subcategoryId}")
	public ResponseEntity<ManseCompatibilityAnalysisResponse> analyzeCompatibility(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseCompatibilityAnalysisRequest request,
		@AuthenticationPrincipal String username
	) {
		// 상태 업데이트 로직
		try {
			updateCompatibilityResultStatusToProcessing(request.getPaymentId());
		} catch (EntityNotFoundException e) {
			log.error("궁합 분석 시작 전 상태 업데이트 실패: {}", e.getMessage());
			return ResponseEntity.status(404).body(null); // 예시 응답
		}

		ManseCompatibilityAnalysisRequest.PersonInfo person1 = request.getPerson1();
		ManseCompatibilityAnalysisRequest.PersonInfo person2 = request.getPerson2();

		// 1. 첫 번째 사람의 만세력 데이터 계산
		ManseryeokCalculationResponse person1Response = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				person1.getName(),
				person1.getSolarDate(),
				person1.getSolarTime(),
				person1.getGender(),
				person1.getIsLunar()
			)
		);

		// 2. 두 번째 사람의 만세력 데이터 계산
		ManseryeokCalculationResponse person2Response = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				person2.getName(),
				person2.getSolarDate(),
				person2.getSolarTime(),
				person2.getGender(),
				person2.getIsLunar()
			)
		);

		// 3. 계산된 두 개의 만세력 데이터로 궁합 분석 서비스 호출
		ManseCompatibilityAnalysisResponse response = manseInterpretationService.analyzeCompatibilityWithSubcategory(
			person1.getName(), person1Response,
			person2.getName(), person2Response,
			username,
			subcategoryId,
			request.getPaymentId()
		);

		return ResponseEntity.ok(response);
	}

	@Transactional
	protected void updateResultStatusToProcessing(Long paymentId) {
		Result result = resultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> {
				log.error("Payment ID {}에 해당하는 Result를 찾을 수 없습니다.", paymentId);
				return new EntityNotFoundException("결과 정보를 찾을 수 없습니다.");
			});
		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) { // INPUT_REQUIRED 상태일 때만 변경
			result.setStatus(ResultStatus.PROCESSING);
			resultRepository.save(result);
			log.info("Result 상태 PROCESSING으로 변경 완료: paymentId={}", paymentId);
		} else {
			log.warn(
				"Result 상태가 INPUT_REQUIRED가 아니므로 PROCESSING으로 변경하지 않음: paymentId={}, currentStatus={}",
				paymentId, result.getStatus());
		}
	}

	@Transactional
	protected void updateCompatibilityResultStatusToProcessing(Long paymentId) {
		CompatibilityResult result = compatibilityResultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> {
				log.error("Payment ID {}에 해당하는 CompatibilityResult를 찾을 수 없습니다.", paymentId);
				return new EntityNotFoundException("궁합 결과 정보를 찾을 수 없습니다.");
			});
		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) { // INPUT_REQUIRED 상태일 때만 변경
			result.setStatus(ResultStatus.PROCESSING);
			compatibilityResultRepository.save(result);
			log.info("CompatibilityResult 상태 PROCESSING으로 변경 완료: paymentId={}", paymentId);
		} else {
			log.warn(
				"CompatibilityResult 상태가 INPUT_REQUIRED가 아니므로 PROCESSING으로 변경하지 않음: paymentId={}, currentStatus={}",
				paymentId, result.getStatus());
		}
	}
}
