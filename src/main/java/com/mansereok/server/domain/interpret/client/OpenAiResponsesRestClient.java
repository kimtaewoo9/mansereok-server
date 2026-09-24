package com.mansereok.server.domain.interpret.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import com.mansereok.server.global.exception.OpenAiRefusalException;
import com.mansereok.server.global.exception.OpenAiRequestException;
import com.mansereok.server.global.exception.OpenAiUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI Responses API 호출 구현.
 * <p>
 * 재시도를 {@code @Retryable} 대신 여기서 직접 돌린다. 애너테이션은 "어떤 예외 타입을 재시도할지"만 말할 수 있는데,
 * 우리가 필요한 정책은 같은 {@code HttpClientErrorException} 안에서도 429 는 재시도하고 400·401·403 은
 * 즉시 실패시키는 것이라 타입만으로는 표현되지 않는다. 429 의 Retry-After 를 백오프에 반영하는 것도 마찬가지다.
 */
@Slf4j
@Component
public class OpenAiResponsesRestClient implements OpenAiResponsesClient {

	private static final String RESPONSES_PATH = "/responses";
	/** 백오프에 더하는 지터 비율. 동시에 실패한 요청들이 같은 시점에 몰리지 않게 한다. */
	private static final double JITTER_RATIO = 0.2;

	private final RestClient restClient;
	private final OpenAiProperties properties;
	private final ObjectMapper objectMapper;
	private final BackoffSleeper sleeper;
	private final DoubleSupplier jitterSource;

	@Autowired
	public OpenAiResponsesRestClient(RestClient.Builder builder, OpenAiProperties properties,
		ObjectMapper objectMapper) {
		this(builder.requestFactory(createRequestFactory(properties)), properties, objectMapper,
			BackoffSleeper.threadSleep(), () -> ThreadLocalRandom.current().nextDouble());
	}

	/**
	 * 테스트용 생성자. requestFactory 를 여기서 건드리지 않으므로 MockRestServiceServer 가 심어 둔
	 * 요청 팩토리가 그대로 살아 있고, 백오프 대기와 지터도 결정적으로 주입할 수 있다.
	 */
	OpenAiResponsesRestClient(RestClient.Builder builder, OpenAiProperties properties,
		ObjectMapper objectMapper, BackoffSleeper sleeper, DoubleSupplier jitterSource) {
		this.restClient = builder
			.baseUrl(properties.baseUrl() + "/v1")
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.key())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.build();
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.sleeper = sleeper;
		this.jitterSource = jitterSource;
	}

	private static ClientHttpRequestFactory createRequestFactory(OpenAiProperties properties) {
		ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
			.withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
			.withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
		return ClientHttpRequestFactoryBuilder.detect().build(settings);
	}

	@Override
	public String createResponse(Gpt5Request request) {
		int maxAttempts = properties.maxAttempts();
		TransientFailure lastFailure = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return extractOutputText(post(request), request.getModel());
			} catch (TransientFailure e) {
				lastFailure = e;
				log.warn("OpenAI 호출 실패 (시도 {}/{}, model: {}): {}",
					attempt, maxAttempts, request.getModel(), e.getMessage());
				if (attempt < maxAttempts) {
					sleepBeforeRetry(attempt, e.retryAfterMillis());
				}
			}
		}

		return callFallback(request, lastFailure);
	}

	/**
	 * 재시도를 모두 쓰고도 실패하면 fallback 티어로 요청 객체를 새로 만들어 딱 한 번 더 호출한다.
	 * 직렬화된 본문에 문자열 치환을 하지 않는다. 그 방식은 프롬프트 본문에 모델명이 섞여 있으면 그것까지 바꾸고,
	 * 애초에 다른 모델을 쓰던 경로에서는 아무것도 바꾸지 못한 채 같은 모델로 재호출한다.
	 */
	private String callFallback(Gpt5Request request, TransientFailure lastFailure) {
		ModelTier fallback = properties.fallback();
		log.warn("OpenAI {}회 재시도 최종 실패(마지막 원인: {}). fallback 모델 {} 로 1회 더 호출합니다.",
			properties.maxAttempts(),
			lastFailure == null ? "알 수 없음" : lastFailure.getMessage(),
			fallback.model());

		Gpt5Request fallbackRequest = withTier(request, fallback);
		try {
			return extractOutputText(post(fallbackRequest), fallback.model());
		} catch (TransientFailure e) {
			throw new OpenAiUnavailableException(
				"OpenAI 호출이 최종 실패했습니다 (재시도 " + properties.maxAttempts()
					+ "회 + fallback 1회). 마지막 원인: " + e.getMessage(), e.getCause());
		}
	}

	private Gpt5Request withTier(Gpt5Request request, ModelTier tier) {
		Map<String, Object> outputFormat =
			request.getText() == null ? null : request.getText().getFormat();
		return new Gpt5Request(
			tier.model(),
			request.getInput(),
			tier.maxOutputTokens(),
			tier.reasoningEffort(),
			tier.verbosity(),
			outputFormat
		);
	}

	private String post(Gpt5Request request) {
		long startedAt = System.currentTimeMillis();
		try {
			String body = restClient.post()
				.uri(RESPONSES_PATH)
				.body(request)
				.retrieve()
				.body(String.class);

			log.info("OpenAI 응답 수신 - model: {}, 소요시간: {}ms, 본문 길이: {}",
				request.getModel(), System.currentTimeMillis() - startedAt,
				body == null ? 0 : body.length());
			return body;

		} catch (HttpClientErrorException e) {
			if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
				throw new TransientFailure("요청 한도 초과 (429)", e, parseRetryAfterMillis(e));
			}
			// 429 를 뺀 4xx 는 같은 요청을 다시 보내도 같은 답이 온다. 백오프를 쓰지 않고 즉시 실패시킨다.
			throw new OpenAiRequestException(
				"OpenAI 요청이 거절되었습니다. status: " + e.getStatusCode().value(), e);

		} catch (HttpServerErrorException e) {
			throw new TransientFailure("서버 오류 (" + e.getStatusCode().value() + ")", e, null);

		} catch (ResourceAccessException e) {
			throw new TransientFailure("연결·읽기 실패: " + e.getMessage(), e, null);

		} catch (RestClientException e) {
			throw new TransientFailure("호출 실패: " + e.getMessage(), e, null);
		}
	}

	private void sleepBeforeRetry(int attempt, Long retryAfterMillis) {
		long delay = retryAfterMillis != null ? retryAfterMillis : backoffMillis(attempt);
		log.info("OpenAI 재시도 대기 {}ms (시도 {} 이후)", delay, attempt);
		try {
			sleeper.sleep(delay);
		} catch (InterruptedException e) {
			// 인터럽트를 삼키지 않는다. 상태를 복원하고 중단한다.
			Thread.currentThread().interrupt();
			throw new OpenAiUnavailableException("OpenAI 재시도 대기 중 인터럽트되었습니다.", e);
		}
	}

	private long backoffMillis(int attempt) {
		double base = properties.backoffDelayMs()
			* Math.pow(properties.backoffMultiplier(), attempt - 1);
		double jitter = base * JITTER_RATIO * jitterSource.getAsDouble();
		return (long) (base + jitter);
	}

	/**
	 * 429 의 Retry-After 를 밀리초로 바꾼다. 초 단위 숫자 형태만 해석하고,
	 * HTTP-date 형태면 null 을 돌려 지수 백오프로 넘긴다.
	 */
	private Long parseRetryAfterMillis(HttpStatusCodeException e) {
		HttpHeaders headers = e.getResponseHeaders();
		if (headers == null) {
			return null;
		}
		String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (retryAfter == null || retryAfter.isBlank()) {
			return null;
		}
		try {
			double seconds = Double.parseDouble(retryAfter.trim());
			if (seconds < 0) {
				return null;
			}
			return (long) (seconds * 1000);
		} catch (NumberFormatException ignored) {
			log.warn("Retry-After 를 해석하지 못해 지수 백오프를 씁니다. 값: {}", retryAfter);
			return null;
		}
	}

	/**
	 * 응답 봉투를 열어 output_text 만 돌려준다.
	 * 응답 본문 전문은 사용자의 사주 해석문을 통째로 담고 있으므로 어떤 레벨로도 로그에 남기지 않는다.
	 */
	private String extractOutputText(String body, String model) {
		if (body == null || body.isBlank()) {
			throw new OpenAiUnavailableException("OpenAI 응답 본문이 비어 있습니다.");
		}

		JsonNode root;
		try {
			root = objectMapper.readTree(body);
		} catch (JsonProcessingException e) {
			throw new OpenAiUnavailableException(
				"OpenAI 응답을 JSON 으로 읽지 못했습니다. 길이: " + body.length(), e);
		}

		if (root.path("error").isObject()) {
			String message = root.path("error").path("message").asText("알 수 없는 오류");
			throw new OpenAiUnavailableException("OpenAI 가 오류를 반환했습니다: " + message);
		}

		logUsage(root, model);

		String status = root.path("status").asText("");
		if ("incomplete".equals(status)) {
			String reason = root.path("incomplete_details").path("reason").asText("unknown");
			throw new OpenAiIncompleteResponseException(reason,
				"OpenAI 응답이 완성되지 않았습니다. reason: " + reason);
		}

		String refusal = findRefusal(root);
		if (refusal != null) {
			throw new OpenAiRefusalException(refusal, "OpenAI 가 응답을 거부했습니다: " + refusal);
		}

		String outputText = findOutputText(root);
		if (outputText == null) {
			throw new OpenAiUnavailableException(
				"OpenAI 응답에서 output_text 를 찾지 못했습니다. status: " + status
					+ ", 본문 길이: " + body.length());
		}

		log.info("OpenAI output_text 추출 완료 - status: {}, 길이: {}", status, outputText.length());
		return outputText;
	}

	private void logUsage(JsonNode root, String model) {
		JsonNode usage = root.path("usage");
		if (!usage.isObject()) {
			return;
		}
		log.info(
			"OpenAI usage - model: {}, input_tokens: {}, output_tokens: {}, reasoning_tokens: {}, total_tokens: {}",
			root.path("model").asText(model),
			usage.path("input_tokens").asInt(),
			usage.path("output_tokens").asInt(),
			usage.path("output_tokens_details").path("reasoning_tokens").asInt(),
			usage.path("total_tokens").asInt());
	}

	private String findRefusal(JsonNode root) {
		for (JsonNode content : messageContents(root)) {
			if ("refusal".equals(content.path("type").asText())) {
				return content.path("refusal").asText("사유 없음");
			}
		}
		return null;
	}

	private String findOutputText(JsonNode root) {
		for (JsonNode content : messageContents(root)) {
			if ("output_text".equals(content.path("type").asText())
				&& content.path("text").isTextual()) {
				return content.path("text").asText();
			}
		}
		return null;
	}

	private List<JsonNode> messageContents(JsonNode root) {
		List<JsonNode> contents = new ArrayList<>();
		JsonNode output = root.path("output");
		if (!output.isArray()) {
			return contents;
		}
		for (JsonNode item : output) {
			if (!"message".equals(item.path("type").asText())) {
				continue;
			}
			JsonNode contentArray = item.path("content");
			if (!contentArray.isArray()) {
				continue;
			}
			contentArray.forEach(contents::add);
		}
		return contents;
	}

	/**
	 * 재시도 대상 실패를 감싸는 내부 신호. 이 클래스 밖으로 새어 나가지 않는다.
	 */
	private static final class TransientFailure extends RuntimeException {

		private final Long retryAfterMillis;

		private TransientFailure(String message, Throwable cause, Long retryAfterMillis) {
			super(message, cause);
			this.retryAfterMillis = retryAfterMillis;
		}

		private Long retryAfterMillis() {
			return retryAfterMillis;
		}
	}
}
