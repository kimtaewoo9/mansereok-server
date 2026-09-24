package com.mansereok.server.domain.interpret.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import com.mansereok.server.global.exception.OpenAiRefusalException;
import com.mansereok.server.global.exception.OpenAiRequestException;
import com.mansereok.server.global.exception.OpenAiUnavailableException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiResponsesRestClientTest {

	private static final String URL = "https://api.openai.com/v1/responses";
	private static final String PROMPT = "당신은 사주 명리학 대가입니다. 임수 일간을 해석하세요.";
	private static final String SYSTEM_INSTRUCTION = "당신은 30년 경력의 전문 사주명리학자입니다.";

	private MockRestServiceServer server;
	private RecordingSleeper sleeper;

	private OpenAiResponsesRestClient clientWith(OpenAiProperties properties) {
		// 지터를 0 으로 고정해 백오프 대기 시간이 결정적으로 나오게 한다.
		return clientWith(properties, () -> 0.0);
	}

	/**
	 * 지터 비율을 직접 정해 클라이언트를 만든다. 0.0 은 백오프 대기를 결정적으로 만들고,
	 * 1.0 은 지터가 실제로 더해지는지와 그 비율 상한이 얼마인지를 결정적으로 드러낸다.
	 */
	private OpenAiResponsesRestClient clientWith(OpenAiProperties properties,
		DoubleSupplier jitterSource) {
		Fixture fixture = newFixture(properties, jitterSource);
		this.server = fixture.server();
		this.sleeper = fixture.sleeper();
		return fixture.client();
	}

	/**
	 * 공유 필드를 건드리지 않는 조립. 한 테스트가 MockRestServiceServer 를 둘 이상 쓸 때 쓴다.
	 * 공유 필드에 덮어쓰면 먼저 만든 서버의 기대가 verify 되지 못한 채 조용히 버려진다.
	 */
	private Fixture newFixture(OpenAiProperties properties, DoubleSupplier jitterSource) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
		RecordingSleeper recordingSleeper = new RecordingSleeper();
		OpenAiResponsesRestClient client = new OpenAiResponsesRestClient(
			builder, properties, new ObjectMapper(), recordingSleeper, jitterSource);
		return new Fixture(client, mockServer, recordingSleeper);
	}

	private Gpt5Request primaryRequest() {
		OpenAiProperties.ModelTier tier = OpenAiProperties.ModelTier.defaultPrimary();
		return new Gpt5Request(tier.model(), PROMPT, tier.maxOutputTokens(),
			tier.reasoningEffort(), tier.verbosity(),
			Map.of("type", "json_schema", "name", "saju"));
	}

	private Gpt5Request instructedRequest() {
		OpenAiProperties.ModelTier tier = OpenAiProperties.ModelTier.defaultPrimary();
		return Gpt5Request.withSystemInstruction(tier.model(), SYSTEM_INSTRUCTION, PROMPT,
			tier.maxOutputTokens(), tier.reasoningEffort(), tier.verbosity(),
			Map.of("type", "json_schema", "name", "saju"));
	}

	private static String completedBody(String outputText) {
		return """
			{
			  "id": "resp_1",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "reasoning", "summary": []},
			    {"type": "message", "content": [{"type": "output_text", "text": "%s"}]}
			  ],
			  "usage": {
			    "input_tokens": 1200,
			    "output_tokens": 3400,
			    "output_tokens_details": {"reasoning_tokens": 800},
			    "total_tokens": 4600
			  }
			}
			""".formatted(outputText);
	}

	/** usage 키가 아예 없는 응답. 집계가 늦게 붙는 모델·프록시에서 실제로 온다. */
	private static String bodyWithoutUsage(String outputText) {
		return """
			{
			  "id": "resp_no_usage",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "content": [{"type": "output_text", "text": "%s"}]}
			  ]
			}
			""".formatted(outputText);
	}

	/** 예외 메시지와 cause 체인 전체를 한 문자열로 펼친다. */
	private static String causeChainText(Throwable throwable) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			text.append(current.getClass().getName()).append(' ')
				.append(current.getMessage()).append('\n');
			if (current.getCause() == current) {
				break;
			}
		}
		return text.toString();
	}

	/** 로그 한 줄의 메시지뿐 아니라 함께 찍히는 예외 체인까지 펼친다. */
	private static String eventText(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null;
			proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(' ').append(proxy.getMessage());
		}
		return text.toString();
	}

	@Test
	@DisplayName("정상 응답에서 output_text 만 뽑아 돌려준다")
	void returnsOutputText() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("해석 결과 JSON"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("해석 결과 JSON");
		server.verify();
	}

	@Test
	@DisplayName("요청 URL·인증 헤더·본문의 model·max_output_tokens·text.format 이 그대로 실린다")
	void sendsExpectedRequest() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-api-key"))
			.andExpect(jsonPath("$.model").value("gpt-5.4"))
			.andExpect(jsonPath("$.input").value(PROMPT))
			.andExpect(jsonPath("$.max_output_tokens").value(32768))
			.andExpect(jsonPath("$.reasoning.effort").value("high"))
			.andExpect(jsonPath("$.text.verbosity").value("high"))
			.andExpect(jsonPath("$.text.format.type").value("json_schema"))
			.andRespond(withSuccess(completedBody("OK"), MediaType.APPLICATION_JSON));

		client.createResponse(primaryRequest());

		server.verify();
	}

	@Test
	@DisplayName("500 이 세 번 나도 네 번째 시도에서 성공하면 결과를 돌려준다")
	void retriesServerErrors() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(4, 2_000L, 2.0));
		server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("늦게 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("늦게 성공");
		assertThat(sleeper.delays).containsExactly(2_000L, 4_000L, 8_000L);
		server.verify();
	}

	@Test
	@DisplayName("재시도를 모두 쓰면 fallback 모델로 새 요청을 만들어 한 번 더 부른다")
	void fallsBackWithNewRequest() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(2, 1_000L, 2.0));
		server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andExpect(jsonPath("$.model").value("gpt-5.2"))
			.andExpect(jsonPath("$.input").value(PROMPT))
			.andExpect(jsonPath("$.max_output_tokens").value(32768))
			.andExpect(jsonPath("$.reasoning.effort").value("medium"))
			.andExpect(jsonPath("$.text.format.type").value("json_schema"))
			.andRespond(withSuccess(completedBody("fallback 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("fallback 성공");
		// fallback 은 재시도하지 않으므로 대기는 primary 재시도 사이의 한 번뿐이다.
		assertThat(sleeper.delays).containsExactly(1_000L);
		server.verify();
	}

	@Test
	@DisplayName("instructions 가 있는 요청은 첫 호출부터 시스템 지시를 input 이 아닌 instructions 로 보낸다")
	void sendsInstructionsSeparatelyOnFirstCall() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andExpect(jsonPath("$.instructions").value(SYSTEM_INSTRUCTION))
			.andExpect(jsonPath("$.input").value(PROMPT))
			.andRespond(withSuccess(completedBody("성공"), MediaType.APPLICATION_JSON));

		assertThat(client.createResponse(instructedRequest())).isEqualTo("성공");
		server.verify();
	}

	@Test
	@DisplayName("fallback 으로 새로 만든 요청도 instructions 의 시스템 지시를 그대로 가져간다")
	void fallbackRequestKeepsInstructions() {
		// fallback 은 요청 객체를 새로 만든다. 여기서 instructions 를 흘리면
		// 재시도 끝에 성공한 호출만 경계 규칙 없이 모델에 닿아 조용히 방어가 뚫린다.
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(2, 1_000L, 2.0));
		server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andExpect(jsonPath("$.model").value("gpt-5.2"))
			.andExpect(jsonPath("$.instructions").value(SYSTEM_INSTRUCTION))
			.andExpect(jsonPath("$.input").value(PROMPT))
			.andRespond(withSuccess(completedBody("fallback 성공"), MediaType.APPLICATION_JSON));

		assertThat(client.createResponse(instructedRequest())).isEqualTo("fallback 성공");
		server.verify();
	}

	@Test
	@DisplayName("fallback 까지 실패하면 OpenAiUnavailableException 으로 끝난다")
	void failsAfterFallback() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(2, 1_000L, 2.0));
		server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiUnavailableException.class)
			.hasMessageContaining("fallback");

		server.verify();
	}

	@Test
	@DisplayName("400 은 재시도 없이 한 번만 호출하고 즉시 OpenAiRequestException 이다")
	void badRequestFailsImmediately() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(4, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withBadRequest());

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiRequestException.class)
			.hasMessageContaining("400");

		assertThat(sleeper.delays).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("401 도 재시도 없이 즉시 실패한다")
	void unauthorizedFailsImmediately() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(4, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiRequestException.class)
			.hasMessageContaining("401");

		assertThat(sleeper.delays).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("429 에 Retry-After 가 있으면 지수 백오프 대신 그 값을 기다린다")
	void honoursRetryAfterHeader() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("한도 회복"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("한도 회복");
		assertThat(sleeper.delays).containsExactly(7_000L);
		server.verify();
	}

	@Test
	@DisplayName("읽기 타임아웃 같은 I/O 오류는 재시도 대상이다")
	void retriesOnTimeout() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withException(new SocketTimeoutException("Read timed out")));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("재시도 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("재시도 성공");
		assertThat(sleeper.delays).containsExactly(2_000L);
		server.verify();
	}

	@Test
	@DisplayName("status=incomplete 는 reason 을 담아 OpenAiIncompleteResponseException 으로 실패한다")
	void incompleteResponseFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_2",
			  "model": "gpt-5.4",
			  "status": "incomplete",
			  "incomplete_details": {"reason": "max_output_tokens"},
			  "output": [
			    {"type": "message", "content": [{"type": "output_text", "text": "{\\"fullAnalysis\\": \\"잘린"}]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiIncompleteResponseException.class)
			.hasMessageContaining("max_output_tokens");

		server.verify();
	}

	@Test
	@DisplayName("refusal 타입 content 는 OpenAiRefusalException 으로 실패한다")
	void refusalFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_3",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "content": [{"type": "refusal", "refusal": "요청을 도와드릴 수 없습니다."}]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiRefusalException.class)
			.hasMessageContaining("요청을 도와드릴 수 없습니다.");

		server.verify();
	}

	@Test
	@DisplayName("error 객체가 담긴 응답은 실패로 처리한다")
	void errorObjectFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{"error": {"type": "server_error", "message": "something went wrong"}}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiUnavailableException.class)
			.hasMessageContaining("something went wrong");

		server.verify();
	}

	@Test
	@DisplayName("output_text 가 없는 응답은 실패로 처리한다")
	void missingOutputTextFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_4",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [{"type": "reasoning", "summary": []}]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiUnavailableException.class)
			.hasMessageContaining("output_text");

		server.verify();
	}

	@Test
	@DisplayName("Retry-After 가 지나치게 크면 재시도 대기 상한까지만 기다린다")
	void capsRetryAfterHeader() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3600"));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("상한 뒤 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("상한 뒤 성공");
		// 상대 서버가 한 시간을 부르더라도 우리 스레드는 우리가 정한 상한까지만 묶인다.
		assertThat(sleeper.delays)
			.containsExactly(OpenAiResponsesRestClient.MAX_RETRY_DELAY_MS);
		server.verify();
	}

	@Test
	@DisplayName("Retry-After 가 Infinity·NaN 이면 무시하고 지수 백오프를 쓴다")
	void ignoresNonFiniteRetryAfter() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "Infinity"));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("백오프 뒤 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("백오프 뒤 성공");
		assertThat(sleeper.delays).containsExactly(2_000L);
		server.verify();
	}

	@Test
	@DisplayName("지수 백오프도 재시도 대기 상한을 넘지 않는다")
	void capsExponentialBackoff() {
		long cap = OpenAiResponsesRestClient.MAX_RETRY_DELAY_MS;
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(4, 20_000L, 10.0));
		server.expect(ExpectedCount.times(4), requestTo(URL)).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("fallback 성공"), MediaType.APPLICATION_JSON));

		client.createResponse(primaryRequest());

		// 20초 -> 200초 -> 2000초 로 뛰려는 것을 두 번째부터 상한이 잘라낸다.
		assertThat(sleeper.delays).containsExactly(20_000L, cap, cap);
		server.verify();
	}

	@Test
	@DisplayName("HttpStatus 로 해석되지 않는 4xx(430)도 재시도 없이 즉시 실패한다")
	void nonStandardClientErrorFailsImmediately() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(4, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatusCode.valueOf(430)));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isInstanceOf(OpenAiRequestException.class)
			.hasMessageContaining("430");

		assertThat(sleeper.delays).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("백오프 대기가 인터럽트되면 상태를 복원하고 중단한다")
	void restoresInterruptFlag() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer interruptServer = MockRestServiceServer.bindTo(builder).build();
		interruptServer.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withServerError());
		OpenAiResponsesRestClient client = new OpenAiResponsesRestClient(
			builder, TestOpenAiProperties.of(3, 2_000L, 2.0), new ObjectMapper(),
			millis -> {
				throw new InterruptedException("중단");
			},
			() -> 0.0);

		try {
			assertThatThrownBy(() -> client.createResponse(primaryRequest()))
				.isInstanceOf(OpenAiUnavailableException.class)
				.hasMessageContaining("인터럽트");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			// 다른 테스트로 새어 나가지 않게 플래그를 비운다.
			Thread.interrupted();
		}
		interruptServer.verify();
	}

	@Test
	@DisplayName("성공이든 실패든 응답 본문(해석문)은 클라이언트 로거의 어떤 레벨로도 남지 않는다")
	void neverLogsResponseBody() {
		String secret = "이 사람의 재물운은 임수 일간의 흐름을 따라 크게 트인다";
		Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRestClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);

		// 서버를 공유 필드에 덮어쓰면 먼저 만든 서버의 기대가 verify 되지 못한 채 버려지므로
		// 두 경로 모두 지역 변수로 들고 끝에서 각각 verify 한다.
		Fixture success = newFixture(TestOpenAiProperties.defaults(), () -> 0.0);
		Fixture failure = newFixture(TestOpenAiProperties.defaults(), () -> 0.0);
		try {
			success.server().expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(completedBody(secret), MediaType.APPLICATION_JSON));
			assertThat(success.client().createResponse(primaryRequest())).isEqualTo(secret);

			// 실패 경로(미완성 응답)에서도 본문을 남기지 않는지 같이 본다.
			String incomplete = """
				{
				  "id": "resp_9",
				  "model": "gpt-5.4",
				  "status": "incomplete",
				  "incomplete_details": {"reason": "max_output_tokens"},
				  "output": [
				    {"type": "message", "content": [{"type": "output_text", "text": "%s"}]}
				  ]
				}
				""".formatted(secret);
			failure.server().expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));
			assertThatThrownBy(() -> failure.client().createResponse(primaryRequest()))
				.isInstanceOf(OpenAiIncompleteResponseException.class);

			assertThat(appender.list).isNotEmpty();
			assertThat(appender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(message -> message.contains(secret));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
		success.server().verify();
		failure.server().verify();
	}

	@Test
	@DisplayName("응답 본문(해석문)은 예외 메시지·cause 체인·전역 핸들러가 남기는 로그 어디에도 담기지 않는다")
	void neverLeaksResponseBodyThroughExceptions() {
		String secret = "이 사람의 재물운은 임수 일간의 흐름을 따라 크게 트인다";
		// 루트 로거에 붙여야 GlobalExceptionHandler 의 log.error 까지 함께 본다.
		Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = rootLogger.getLevel();
		rootLogger.setLevel(Level.TRACE);
		rootLogger.addAppender(appender);

		// (1) message 가 아닌 항목에만 해석문이 들어 있어 output_text 를 찾지 못하는 본문
		String noOutputText = """
			{
			  "id": "resp_leak_1",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "reasoning", "content": [{"type": "output_text", "text": "%s"}]}
			  ]
			}
			""".formatted(secret);
		// (2) 해석문을 담은 채 JSON 으로 읽히지 않는 본문
		String brokenJson = "{\"status\": \"completed\", \"note\": \"" + secret + "\", }";

		Fixture missing = newFixture(TestOpenAiProperties.defaults(), () -> 0.0);
		Fixture broken = newFixture(TestOpenAiProperties.defaults(), () -> 0.0);
		try {
			missing.server().expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(noOutputText, MediaType.APPLICATION_JSON));
			Throwable missingFailure = catchThrowable(
				() -> missing.client().createResponse(primaryRequest()));

			broken.server().expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(brokenJson, MediaType.APPLICATION_JSON));
			Throwable brokenFailure = catchThrowable(
				() -> broken.client().createResponse(primaryRequest()));

			assertThat(missingFailure).isInstanceOf(OpenAiUnavailableException.class);
			assertThat(brokenFailure).isInstanceOf(OpenAiUnavailableException.class);
			assertThat(causeChainText(missingFailure)).doesNotContain(secret);
			assertThat(causeChainText(brokenFailure)).doesNotContain(secret);

			// 예외 메시지는 결국 전역 핸들러의 log.error 를 타고 로그로 나간다.
			GlobalExceptionHandler handler = new GlobalExceptionHandler();
			handler.handleOpenAiUnavailable((OpenAiUnavailableException) missingFailure);
			handler.handleOpenAiUnavailable((OpenAiUnavailableException) brokenFailure);

			assertThat(appender.list).isNotEmpty();
			assertThat(appender.list)
				.extracting(OpenAiResponsesRestClientTest::eventText)
				.noneMatch(text -> text.contains(secret));
		} finally {
			rootLogger.detachAppender(appender);
			rootLogger.setLevel(originalLevel);
			appender.stop();
		}
		missing.server().verify();
		broken.server().verify();
	}

	@Test
	@DisplayName("usage 가 있으면 input·output·reasoning·total 토큰 수를 모두 로그로 남긴다")
	void logsUsageTokenCounts() {
		Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRestClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);

		try {
			OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
			server.expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(completedBody("해석 결과 JSON"), MediaType.APPLICATION_JSON));

			client.createResponse(primaryRequest());

			assertThat(appender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.filteredOn(message -> message.contains("OpenAI usage"))
				.singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
				.contains("model: gpt-5.4")
				.contains("input_tokens: 1200")
				.contains("output_tokens: 3400")
				.contains("reasoning_tokens: 800")
				.contains("total_tokens: 4600");
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
		server.verify();
	}

	@Test
	@DisplayName("usage 필드가 없는 응답도 터지지 않고 output_text 를 돌려주며 usage 로그는 남기지 않는다")
	void skipsUsageLogWhenUsageAbsent() {
		Logger logger = (Logger) LoggerFactory.getLogger(OpenAiResponsesRestClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.TRACE);
		logger.addAppender(appender);

		try {
			OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
			server.expect(ExpectedCount.once(), requestTo(URL))
				.andRespond(withSuccess(bodyWithoutUsage("usage 없음"), MediaType.APPLICATION_JSON));

			assertThat(client.createResponse(primaryRequest())).isEqualTo("usage 없음");

			assertThat(appender.list)
				.extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(message -> message.contains("OpenAI usage"));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
		server.verify();
	}

	@ParameterizedTest(name = "Retry-After=[{0}] 이면 {1}ms 를 기다린다")
	@CsvSource(delimiter = '|', value = {
		// 음수는 Thread.sleep 이 IllegalArgumentException 을 내므로 버리고 지수 백오프로 간다.
		"-1                            | 2000",
		// 0 은 정상적인 "지금 바로 다시" 신호다. 지수 백오프로 밀어내지 않는다.
		"0                             | 0",
		// 빈 값·숫자가 아닌 값·스펙상 정상인 HTTP-date 는 모두 해석 실패로 보고 지수 백오프를 쓴다.
		"''                            | 2000",
		"not-a-number                  | 2000",
		"Wed, 21 Oct 2026 07:28:00 GMT | 2000"
	})
	@DisplayName("429 의 Retry-After 경계값은 음수·해석 실패면 지수 백오프로, 0 이면 즉시 재시도로 간다")
	void retryAfterBoundaries(String retryAfter, long expectedDelayMs) {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
				.header("Retry-After", retryAfter));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("한도 회복"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("한도 회복");
		assertThat(sleeper.delays).containsExactly(expectedDelayMs);
		server.verify();
	}

	@Test
	@DisplayName("refusal 이 output_text 뒤에 있어도 output_text 보다 먼저 처리된다")
	void refusalWinsOverPrecedingOutputText() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_mixed_1",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "content": [
			      {"type": "output_text", "text": "먼저 온 해석문"},
			      {"type": "refusal", "refusal": "뒤에 온 거부 사유"}
			    ]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isExactlyInstanceOf(OpenAiRefusalException.class)
			.hasMessageContaining("뒤에 온 거부 사유");

		server.verify();
	}

	@Test
	@DisplayName("message 가 여럿이면 뒤 message 의 refusal 도 앞 message 의 output_text 를 이긴다")
	void refusalInLaterMessageWins() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_mixed_2",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "content": [{"type": "output_text", "text": "첫 message 해석문"}]},
			    {"type": "message", "content": [{"type": "refusal", "refusal": "둘째 message 거부"}]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isExactlyInstanceOf(OpenAiRefusalException.class)
			.hasMessageContaining("둘째 message 거부");

		server.verify();
	}

	@Test
	@DisplayName("output_text 가 여러 개면 처음 것을 쓴다")
	void usesFirstOutputTextAcrossMessages() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_mixed_3",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "content": [
			      {"type": "output_text", "text": "첫 번째"},
			      {"type": "output_text", "text": "같은 message 의 두 번째"}
			    ]},
			    {"type": "message", "content": [{"type": "output_text", "text": "다음 message"}]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(client.createResponse(primaryRequest())).isEqualTo("첫 번째");

		server.verify();
	}

	@Test
	@DisplayName("message 가 아닌 output 항목의 content 에 담긴 output_text 는 무시한다")
	void ignoresContentOfNonMessageOutputItems() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.defaults());
		String body = """
			{
			  "id": "resp_mixed_4",
			  "model": "gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "reasoning", "content": [{"type": "output_text", "text": "추론 흔적"}]},
			    {"type": "web_search_call", "content": [{"type": "refusal", "refusal": "무시될 거부"}]},
			    {"type": "message", "content": [{"type": "output_text", "text": "진짜 해석문"}]}
			  ]
			}
			""";
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		// reasoning 의 output_text 를 주워 오지도, web_search_call 의 refusal 로 던지지도 않는다.
		assertThat(client.createResponse(primaryRequest())).isEqualTo("진짜 해석문");

		server.verify();
	}

	@Test
	@DisplayName("502 가 text/html 에러 페이지로 와도 5xx 로 분류해 재시도한다")
	void retriesHtmlErrorPage() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		// 프록시·CDN 이 장애 때 내는 형태. 본문이 JSON 이 아니어도 상태코드 기준 분류는 같아야 한다.
		server.expect(ExpectedCount.times(2), requestTo(URL))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY)
				.contentType(MediaType.TEXT_HTML)
				.body("<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("프록시 회복"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("프록시 회복");
		assertThat(sleeper.delays).containsExactly(2_000L, 4_000L);
		server.verify();
	}

	@Test
	@DisplayName("공백뿐인 응답 본문은 재시도 없이 OpenAiUnavailableException 으로 실패한다")
	void blankResponseBodyFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess("   \n  ", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isExactlyInstanceOf(OpenAiUnavailableException.class)
			.hasMessageContaining("본문이 비어 있습니다");

		assertThat(sleeper.delays).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("본문이 아예 없는 응답도 재시도 없이 OpenAiUnavailableException 으로 실패한다")
	void emptyResponseBodyFails() {
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0));
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.createResponse(primaryRequest()))
			.isExactlyInstanceOf(OpenAiUnavailableException.class)
			.hasMessageContaining("본문이 비어 있습니다");

		assertThat(sleeper.delays).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("지터가 최대치(1.0)면 지수 백오프에 20% 가 더해진 값까지만 기다린다")
	void addsJitterUpToRatio() {
		// 난수를 1.0 으로 고정하면 지터가 붙은 상한이 결정적으로 드러난다.
		OpenAiResponsesRestClient client = clientWith(TestOpenAiProperties.of(3, 2_000L, 2.0),
			() -> 1.0);
		server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(URL))
			.andRespond(withSuccess(completedBody("지터 뒤 성공"), MediaType.APPLICATION_JSON));

		String result = client.createResponse(primaryRequest());

		assertThat(result).isEqualTo("지터 뒤 성공");
		// 2000 * 1.2, 4000 * 1.2. 지터를 빼면 2000, 4000 이 되어 어긋난다.
		assertThat(sleeper.delays).containsExactly(2_400L, 4_800L);
		server.verify();
	}

	/**
	 * 한 테스트 안에서 클라이언트·모의 서버·sleeper 를 공유 필드 없이 같이 들고 다닌다.
	 */
	private record Fixture(OpenAiResponsesRestClient client, MockRestServiceServer server,
						   RecordingSleeper sleeper) {

	}

	/**
	 * 실제로 기다리지 않고 요청된 대기 시간만 기록한다.
	 */
	private static final class RecordingSleeper implements BackoffSleeper {

		private final List<Long> delays = new ArrayList<>();

		@Override
		public void sleep(long millis) {
			delays.add(millis);
		}
	}
}
