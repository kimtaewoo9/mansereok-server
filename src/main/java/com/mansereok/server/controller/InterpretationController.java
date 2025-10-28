package com.mansereok.server.controller;

import com.mansereok.server.service.InterpretationService;
import com.mansereok.server.service.request.CompatibilityAnalysisRequest;
import com.mansereok.server.service.request.ManseryeokCreateRequest;
import com.mansereok.server.service.response.CompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ManseryeokInterpretationResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Posteller에게 만세력 받아와서 해석하는 API")
public class InterpretationController {

	private final InterpretationService interpretationService;

	// Posteller 에게 데이터 가져오는 방식.

	// 단일 인물 요청
	@PostMapping("/api/v1/manseryeok/interpretation/{subcategoryId}/posteller")
	public ResponseEntity<ManseryeokInterpretationResponse> getInterpretation(
		@RequestBody ManseryeokCreateRequest request,
		@PathVariable Long subcategoryId
	) {
		log.info("[ManseryeokController.getInterpretation] name={}]", request.getName());
		ManseryeokInterpretationResponse response = interpretationService.createInterpretation(
			request,
			subcategoryId
		);
		return ResponseEntity.ok(response);
	}

	// 궁합 요청 .
	@PostMapping("/api/v1/manseryeok/compatibility/{subcategoryId}/posteller")
	public ResponseEntity<CompatibilityAnalysisResponse> getCompatibilityAnalysis(
		@PathVariable Long subcategoryId,
		@RequestBody CompatibilityAnalysisRequest request
	) {
		log.info("[ManseryeokController.getCompatibilityAnalysis] person1={}, person2={}",
			request.getPerson1().getName(), request.getPerson2().getName());
		CompatibilityAnalysisResponse response = interpretationService.createCompatibilityAnalysis(
			request,
			subcategoryId
		);
		return ResponseEntity.ok(response);
	}
}
