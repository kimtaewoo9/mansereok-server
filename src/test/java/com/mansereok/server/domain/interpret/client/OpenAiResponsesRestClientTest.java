package com.mansereok.server.domain.interpret.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import com.mansereok.server.global.exception.OpenAiRefusalException;
import com.mansereok.server.global.exception.OpenAiRequestException;
import com.mansereok.server.global.exception.OpenAiUnavailableException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiResponsesRestClientTest {

	private static final String URL = "https://api.openai.com/v1/responses";
	private static final String PROMPT = "당신은 사주 명리학 대가입니다. 임수 일간을 해석하세요.";

	private MockRestServiceServer server;
	private RecordingSleeper sleeper;

	private OpenAiResponsesRestClient clientWith(OpenAiProperties properties) {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.sleeper = new RecordingSleeper();
		// 지터를 0 으로 고정해 백오프 대기 시간이 결정적으로 나오게 한다.
		return new OpenAiResponsesRestClient(builder, properties, new ObjectMapper(), sleeper,
			() -> 0.0);
	}

	private Gpt5Request primaryRequest() {
		OpenAiProperties.ModelTier tier = OpenAiProperties.ModelTier.defaultPrimary();
		return new Gpt5Request(tier.model(), PROMPT, tier.maxOutputTokens(),
			tier.reasoningEffort(), tier.verbosity(),
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
