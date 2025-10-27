package com.mansereok.server.controller;

import com.mansereok.server.service.PostellerService;
import com.mansereok.server.service.request.ManseryeokCreateRequest;
import com.mansereok.server.service.response.ChartCreateResponse;
import com.mansereok.server.service.response.DaeunCreateResponse;
import com.mansereok.server.service.response.OhaengCreateResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Posteller 만세력 API", description = "사주팔자 분석 및 해석 API")
public class PostellerController {

	private final PostellerService postellerService;

	@PostMapping("/api/v1/manseryeok/daeun")
	public ResponseEntity<DaeunCreateResponse> getManseryeok(
		@Parameter(description = "사주 분석 요청 정보", required = true)
		@RequestBody ManseryeokCreateRequest request
	) {
		DaeunCreateResponse daeunResponse = postellerService.getDaeun(request);
		return ResponseEntity.ok(daeunResponse);
	}

	@PostMapping("/api/v1/manseryeok/chart")
	public ResponseEntity<ChartCreateResponse> getChart(
		@Parameter(description = "사주 분석 요청 정보", required = true)
		@RequestBody ManseryeokCreateRequest request
	) {
		ChartCreateResponse chartCreateResponse = postellerService.getChart(request);
		return ResponseEntity.ok(chartCreateResponse);
	}

	@PostMapping("/api/v1/manseryeok/points")
	public ResponseEntity<OhaengCreateResponse> getOhaeng(
		@Parameter(description = "사주 분석 요청 정보", required = true)
		@RequestBody ManseryeokCreateRequest request
	) {
		OhaengCreateResponse ohaengResponse = postellerService.getOhaeng(request);
		return ResponseEntity.ok(ohaengResponse);
	}
}
