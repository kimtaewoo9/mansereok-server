package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.global.exception.GptApiFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
public class GptApiRetryService {

	private final RestClient restClient;

	// 1. 이 "비서"가 API 키와 URL을 직접 받아서 RestClient를 생성합니다.
	public GptApiRetryService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	@Retryable(
		value = {RestClientException.class},
		maxAttempts = 4,
		backoff = @Backoff(delay = 2000, multiplier = 2)
	)
	public String callGptApiWithRetry(String requestBody) {
		log.info("[Retryable] GPT API 호출 시도...");

		return restClient.post()
			.uri("/responses")
			.body(requestBody)
			.retrieve()
			.body(String.class);
	}

	@Recover
	public String recover(RestClientException e, String requestBody) {
		log.error("[Recover] GPT API 4회 재시도 최종 실패. (requestBody 길이: {}) Error: {}",
			requestBody.length(), e.getMessage(), e);

		// "팀장"이 롤백할 수 있도록 GptApiFailedException을 던짐
		throw new GptApiFailedException("GPT API 4회 재시도 최종 실패: " + e.getMessage(), e);
	}
}
