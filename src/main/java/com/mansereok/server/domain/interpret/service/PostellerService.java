package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.client.PostellerApiClient;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.DaeunCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.OhaengCreateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostellerService {

	private final PostellerApiClient postellerApiClient;

	public DaeunCreateResponse getDaeun(ManseryeokCreateRequest request) {
		log.info("🚀 포스텔러 대운 API 호출, name={}", request.getName());
		return postellerApiClient.getDaeun(request);
	}

	public ChartCreateResponse getChart(ManseryeokCreateRequest request) {
		log.info("🚀 포스텔러 사주 기본 차트(일간/사주팔자) API 호출, name={}", request.getName());
		return postellerApiClient.getChart(request);
	}

	public OhaengCreateResponse getOhaeng(ManseryeokCreateRequest request) {
		log.info("🚀 포스텔러 (오행/십성) API 호출, name={}", request.getName());
		return postellerApiClient.getOhaeng(request);
	}
}
