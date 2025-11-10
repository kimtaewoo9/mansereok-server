package com.mansereok.server.domain.interpret.controller;

import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
	public ResponseEntity<?> interpret(
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
		try {
			manseInterpretationService.interpret(
				request.getName(),
				manse, // 계산된 만세력 데이터 전달
				username,
				subcategoryId,
				request.getPaymentId()
			);
		} catch (Exception e) {
			log.error("비동기 해석 작업 시작 중 예상치 못한 오류 발생: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", "해석 작업을 시작하는 중 서버 오류가 발생했습니다."));
		}

		// 즉시 "요청 접수됨" 응답 반환 (HTTP 202 Accepted)
		log.info("만세력 해석 요청 접수 완료 (비동기 처리 시작): paymentId={}", request.getPaymentId());
		return ResponseEntity.accepted() // HTTP 202 Accepted 상태 코드 사용
			.body(Map.of(
				"message", "해석 요청이 접수되었습니다. 잠시 후 결과를 확인해주세요.",
				"paymentId", request.getPaymentId() // 클라이언트가 결과를 조회할 때 사용할 ID
			));
	}

	@PostMapping("/api/v1/manseryeok/interpret/compatibility/{subcategoryId}")
	public ResponseEntity<?> analyzeCompatibility(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseCompatibilityAnalysisRequest request,
		@AuthenticationPrincipal String username
	) {
		ManseCompatibilityAnalysisRequest.PersonInfo person1 = request.getPerson1();
		ManseCompatibilityAnalysisRequest.PersonInfo person2 = request.getPerson2();

		try {
			updateCompatibilityResultStatusToProcessing(request.getPaymentId());
		} catch (EntityNotFoundException e) {
			log.error("해석 시작 전 상태 업데이트 실패: {}", e.getMessage());
			return ResponseEntity.status(404).body(null); // 예시 응답
		}

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
		try {
			manseInterpretationService.analyzeCompatibilityWithSubcategory(
				person1.getName(), person1Response,
				person2.getName(), person2Response,
				subcategoryId,
				request.getPaymentId(),
				username
			);
		} catch (Exception e) {
			log.error("비동기 궁합 분석 작업 시작 중 예상치 못한 오류 발생: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("message", "궁합 분석 작업을 시작하는 중 서버 오류가 발생했습니다."));
		}

		log.info("궁합 분석 요청 접수 완료 (비동기 처리 시작): paymentId={}", request.getPaymentId());
		return ResponseEntity.accepted()
			.body(Map.of(
				"message", "궁합 분석 요청이 접수되었습니다. 잠시 후 결과를 확인해주세요.",
				"paymentId", request.getPaymentId()
			));
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
