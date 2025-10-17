package com.mansereok.server.controller;

import com.mansereok.server.service.InterpretationService;
import com.mansereok.server.service.request.CompatibilityAnalysisRequest;
import com.mansereok.server.service.request.ManseryeokCreateRequest;
import com.mansereok.server.service.response.CompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ManseryeokInterpretationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class InterpretationController {

	private final InterpretationService interpretationService;

	@PostMapping("/api/v1/manseryeok/interpretation")
	public ResponseEntity<ManseryeokInterpretationResponse> getInterpretation(
		@RequestBody ManseryeokCreateRequest request
	) {
		log.info("[ManseryeokController.getInterpretation] name={}]", request.getName());
		ManseryeokInterpretationResponse response = interpretationService.createInterpretation(
			request);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/api/v1/manseryeok/compatibility")
	public ResponseEntity<CompatibilityAnalysisResponse> getCompatibilityAnalysis(
		@RequestBody CompatibilityAnalysisRequest request
	) {
		log.info("[ManseryeokController.getCompatibilityAnalysis] person1={}, person2={}",
			request.getPerson1().getName(), request.getPerson2().getName());
		CompatibilityAnalysisResponse response = interpretationService.createCompatibilityAnalysis(
			request);
		return ResponseEntity.ok(response);
	}
}
