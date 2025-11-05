package com.mansereok.server.domain.interpret.client;

import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.DaeunCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.OhaengCreateResponse;
import com.mansereok.server.global.exception.PostellerApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PostellerApiClient {

	private final RestClient restClient = RestClient.create();

	@Value("${posteller.api.base-url:https://api.forceteller.com}")
	private String baseUrl;

	// 대운 API
	public DaeunCreateResponse getDaeun(ManseryeokCreateRequest request) {
		try {
			String url = baseUrl + "/api/pro/profile/saju/daeun";

			return restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", MediaType.APPLICATION_JSON_VALUE)
				.header("User-Agent", "Manseryeok-Client/1.0")
				.header("Referer", "https://pro.forceteller.com")
				.header("Origin", "https://pro.forceteller.com")
				.body(request)
				.retrieve()
				.body(DaeunCreateResponse.class);

		} catch (Exception e) {
			log.error("대운 API 호출 오류: name={}", request.getName(), e);
			throw new PostellerApiException("대운 조회 중 오류가 발생했습니다: " + e.getMessage(), e);
		}
	}

	// 사주 기본 차트 API
	public ChartCreateResponse getChart(ManseryeokCreateRequest request) {
		try {
			String url = baseUrl + "/api/pro/profile/saju/chart";

			return restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", MediaType.APPLICATION_JSON_VALUE)
				.header("User-Agent", "Manseryeok-Client/1.0")
				.header("Referer", "https://pro.forceteller.com")
				.header("Origin", "https://pro.forceteller.com")
				.body(request)
				.retrieve()
				.body(ChartCreateResponse.class);

		} catch (Exception e) {
			log.error("사주 차트 API 호출 오류: name={}", request.getName(), e);
			throw new PostellerApiException("사주 차트 조회 중 오류가 발생했습니다: " + e.getMessage(), e);
		}
	}

	// 오행/십성 분석 API
	public OhaengCreateResponse getOhaeng(ManseryeokCreateRequest request) {
		try {
			String url = baseUrl + "/api/pro/profile/saju/points";

			return restClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Accept", MediaType.APPLICATION_JSON_VALUE)
				.header("User-Agent", "Manseryeok-Client/1.0")
				.header("Referer", "https://pro.forceteller.com")
				.header("Origin", "https://pro.forceteller.com")
				.body(request)
				.retrieve()
				.body(OhaengCreateResponse.class);

		} catch (Exception e) {
			log.error("오행 분석 API 호출 오류: name={}", request.getName(), e);
			throw new PostellerApiException("오행 분석 조회 중 오류가 발생했습니다: " + e.getMessage(), e);
		}
	}
}
