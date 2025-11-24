package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.global.exception.GptApiFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

	public GptApiRetryService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(10 * 1000);
		requestFactory.setReadTimeout(360 * 1000); // readtimeout 을 360초로 솔정

		this.restClient = RestClient.builder()
			.requestFactory(requestFactory)
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
		long startTime = System.currentTimeMillis();
		log.info("[Retryable] GPT API 호출 시도...");

		try {
			String response = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			// 2. 성공 시 소요 시간 로그 출력
			long duration = System.currentTimeMillis() - startTime;
			log.info("✅ GPT API 응답 수신 완료 (소요시간: {}ms)", duration);

			return response;

		} catch (Exception e) {
			// 3. 실패 시에도 소요 시간 로그 출력 (타임아웃 확인용)
			long duration = System.currentTimeMillis() - startTime;
			log.warn("❌ GPT API 호출 실패 (소요시간: {}ms) - 에러: {}", duration, e.getMessage());
			throw e; // 재시도를 위해 예외를 다시 던짐
		}
	}

	@Recover
	public String recover(RestClientException e, String requestBody) {
		log.error("[Recover] GPT API 4회 재시도 최종 실패. (requestBody 길이: {}) Error: {}",
			requestBody.length(), e.getMessage(), e);
		
		throw new GptApiFailedException("GPT API 4회 재시도 최종 실패: " + e.getMessage(), e);
	}
}
