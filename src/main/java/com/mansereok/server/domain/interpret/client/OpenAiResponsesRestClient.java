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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

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
	/**
	 * 재시도 한 번의 대기 상한. Retry-After 를 그대로 믿으면 상대 서버가 보낸 값(예: 3600)이
	 * gptTaskExecutor 스레드 점유 시간을 정하게 된다. 우리 스레드를 얼마나 묶을지는 우리가 정한다.
	 * 지수 백오프에도 같은 상한을 씌워 maxAttempts 를 키웠을 때의 폭주를 함께 막는다.
	 * 테스트가 이 상한을 매직값으로 베껴 두지 않도록 package-private 으로 열어 둔다.
	 */
	static final long MAX_RETRY_DELAY_MS = 30_000L;

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
		// 후속 판단 거리: fallback 티어가 하나뿐이라 무료(light = gpt-5-mini / 8192) 경로도
		// 5xx 가 이어지면 gpt-5.2 / 32768 토큰으로 올라간다. 기존 코드는 문자열 치환 버그 때문에
		// 이 경로의 fallback 이 아무 일도 하지 않았으므로, 버그를 고친 결과로 새로 생기는 비용 노출이다.
		// 티어별 fallback 을 두거나 light 는 fallback 없이 실패시키는 선택은 다음 PR 로 남긴다.
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
			request.getInstructions(),
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

		} catch (RestClientResponseException e) {
			// HttpClientErrorException·HttpServerErrorException 은 물론, HttpStatus.resolve() 가
			// 실패하는 비표준 상태코드(프록시·CDN 이 내는 430·499 등)로 생기는
			// UnknownHttpStatusCodeException 까지 여기서 상태코드 기준으로 갈린다.
			throw classify(e.getStatusCode().value(), e, e.getResponseHeaders());

		} catch (UnknownContentTypeException e) {
			// 에러 페이지가 text/html 로 오면 상태코드는 멀쩡한데 본문 변환에서 터진다. 분류는 같다.
			throw classify(e.getStatusCode().value(), e, e.getResponseHeaders());

		} catch (ResourceAccessException e) {
			throw new TransientFailure("연결·읽기 실패: " + e.getMessage(), e, null);

		} catch (RestClientException e) {
			throw new TransientFailure("호출 실패: " + e.getMessage(), e, null);
		}
	}

	/**
	 * 상태코드로 재시도 대상과 즉시 실패를 가른다.
	 * 429 와 5xx 만 재시도하고, 429 를 뺀 4xx 는 같은 요청을 다시 보내도 같은 답이 오므로 즉시 실패시킨다.
	 */
	private RuntimeException classify(int status, RestClientException cause, HttpHeaders headers) {
		if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
			return new TransientFailure("요청 한도 초과 (429)", cause, parseRetryAfterMillis(headers));
		}
		// HttpStatus.valueOf 는 비표준 코드에서 예외를 던지므로 숫자 범위로 본다.
		if (status >= 400 && status < 500) {
			return new OpenAiRequestException(
				"OpenAI 요청이 거절되었습니다. status: " + status, cause);
		}
		return new TransientFailure("서버 오류 (" + status + ")", cause, null);
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
		return Math.min((long) (base + jitter), MAX_RETRY_DELAY_MS);
	}

	/**
	 * 429 의 Retry-After 를 밀리초로 바꾼다. 초 단위 숫자 형태만 해석하고,
	 * HTTP-date 형태면 null 을 돌려 지수 백오프로 넘긴다.
	 * Double.parseDouble 은 "Infinity"·"NaN" 도 받아들이므로 유한한 값인지 먼저 확인하고,
	 * 유한하더라도 MAX_RETRY_DELAY_MS 로 잘라 상대 서버가 우리 스레드 점유 시간을 정하지 못하게 한다.
	 */
	private Long parseRetryAfterMillis(HttpHeaders headers) {
		if (headers == null) {
			return null;
		}
		String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (retryAfter == null || retryAfter.isBlank()) {
			return null;
		}
		try {
			double seconds = Double.parseDouble(retryAfter.trim());
			if (!Double.isFinite(seconds) || seconds < 0) {
				log.warn("Retry-After 값이 유한한 초가 아니라 지수 백오프를 씁니다. 값: {}", retryAfter);
				return null;
			}
			long millis = (long) (seconds * 1000);
			if (millis > MAX_RETRY_DELAY_MS) {
				log.warn("Retry-After {}초가 상한 {}ms 를 넘어 상한까지만 기다립니다.",
					retryAfter, MAX_RETRY_DELAY_MS);
				return MAX_RETRY_DELAY_MS;
			}
			return millis;
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
			throw new OpenAiIncompleteResponseException(reason);
		}

		String outputText = extractFromContents(root);
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

	/**
	 * content 배열을 한 번만 훑으면서 refusal 우선 규칙까지 한자리에서 본다.
	 * refusal 은 뒤에 있어도 그 자리에서 던지므로 output_text 보다 항상 먼저 처리된다.
	 */
	private String extractFromContents(JsonNode root) {
		String outputText = null;
		for (JsonNode content : messageContents(root)) {
			String type = content.path("type").asText();
			if ("refusal".equals(type)) {
				throw new OpenAiRefusalException(content.path("refusal").asText("사유 없음"));
			}
			if (outputText == null && "output_text".equals(type)
				&& content.path("text").isTextual()) {
				outputText = content.path("text").asText();
			}
		}
		return outputText;
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
