package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

	/** 내부 진단용 정보. 사용자 응답 message 로 새면 안 되는 것들이다. */
	private static final String INTERNAL_MODEL = "gpt-5.4";
	private static final String INTERNAL_ATTEMPTS = "재시도 3회 + fallback 1회";
	private static final String INTERNAL_BODY_LENGTH = "본문 길이: 91234";

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("OpenAI 일시 장애는 503 OPENAI_UNAVAILABLE 로 내려간다")
	void mapsUnavailableTo503() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiUnavailable(
			new OpenAiUnavailableException(INTERNAL_ATTEMPTS + " 실패, model: " + INTERNAL_MODEL
				+ ", " + INTERNAL_BODY_LENGTH));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_UNAVAILABLE");
		assertThat(response.getBody().getStatus()).isEqualTo(503);
		assertThat(response.getBody().getMessage())
			.isEqualTo("사주 해석 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
		assertThatMessageHidesInternals(response);
	}

	@Test
	@DisplayName("OpenAI 요청 오류는 400 OPENAI_REQUEST_ERROR 로 내려간다")
	void mapsRequestErrorTo400() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiRequest(
			new OpenAiRequestException("OpenAI 요청이 거절되었습니다. status: 400, model: "
				+ INTERNAL_MODEL + ", " + INTERNAL_ATTEMPTS + ", " + INTERNAL_BODY_LENGTH));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_REQUEST_ERROR");
		assertThat(response.getBody().getStatus()).isEqualTo(400);
		assertThat(response.getBody().getMessage())
			.isEqualTo("사주 해석 요청을 처리할 수 없습니다.");
		assertThatMessageHidesInternals(response);
	}

	@Test
	@DisplayName("미완성 응답은 502 OPENAI_INCOMPLETE_RESPONSE 로 내려간다")
	void mapsIncompleteTo502() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiIncompleteResponse(
			new OpenAiIncompleteResponseException("max_output_tokens"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_INCOMPLETE_RESPONSE");
		assertThat(response.getBody().getStatus()).isEqualTo(502);
		assertThat(response.getBody().getMessage())
			.isEqualTo("사주 해석이 끝까지 생성되지 않았습니다. 잠시 후 다시 시도해주세요.");
		// reason 은 내부 진단용이다. 사용자 응답에 그대로 실리지 않는다.
		assertThat(response.getBody().getMessage()).doesNotContain("max_output_tokens");
		assertThatMessageHidesInternals(response);
	}

	@Test
	@DisplayName("거부 응답은 502 OPENAI_REFUSAL 로 내려간다")
	void mapsRefusalTo502() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiRefusal(
			new OpenAiRefusalException("사용자의 사주 원문이 섞인 모델 거부 사유"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_REFUSAL");
		assertThat(response.getBody().getStatus()).isEqualTo(502);
		assertThat(response.getBody().getMessage())
			.isEqualTo("사주 해석을 생성할 수 없습니다. 입력 내용을 확인해주세요.");
		// 모델이 돌려준 거부 사유 원문을 사용자에게 그대로 되비추지 않는다.
		assertThat(response.getBody().getMessage()).doesNotContain("사용자의 사주 원문");
		assertThatMessageHidesInternals(response);
	}

	@Test
	@DisplayName("새 OpenAI 예외들은 기존 GptApiFailedException 계층과 섞이지 않는다")
	void openAiExceptionsAreSeparateHierarchy() {
		assertThat(new OpenAiUnavailableException("x"))
			.isNotInstanceOf(GptApiFailedException.class)
			.isInstanceOf(OpenAiException.class);
		assertThat(new OpenAiRequestException("x")).isInstanceOf(OpenAiException.class);
		assertThat(new OpenAiIncompleteResponseException("max_output_tokens"))
			.isInstanceOf(OpenAiException.class);
		assertThat(new OpenAiRefusalException("r")).isInstanceOf(OpenAiException.class);
	}

	/**
	 * 모델명·시도 횟수·본문 길이 같은 내부 메시지가 사용자 응답 message 로 새지 않는지 본다.
	 */
	private void assertThatMessageHidesInternals(ResponseEntity<ErrorResponse> response) {
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getMessage())
			.doesNotContain(INTERNAL_MODEL)
			.doesNotContain(INTERNAL_ATTEMPTS)
			.doesNotContain(INTERNAL_BODY_LENGTH)
			.doesNotContain("본문 길이")
			.doesNotContain("status: 400");
	}

	@Test
	@DisplayName("스레드 풀 포화는 503 SERVER_BUSY 로 내려간다")
	void mapsRejectedExecutionTo503() {
		ResponseEntity<ErrorResponse> response = handler.handleRejectedExecution(
			new RejectedExecutionException("무료 사주 요청이 폭주하고 있습니다. 잠시 후 다시 시도해주세요."));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("SERVER_BUSY");
		assertThat(response.getBody().getStatus()).isEqualTo(503);
	}

	/**
	 * 실제로 컨트롤러까지 올라오는 타입은 ThreadPoolTaskExecutor 가 감싼 TaskRejectedException 이다.
	 * RejectedExecutionException 의 하위 타입이라 같은 핸들러가 받는데, 스프링 쪽 계층이 바뀌면
	 * 조용히 500 으로 돌아가므로 상속 관계까지 여기서 고정한다.
	 */
	@Test
	@DisplayName("스프링이 감싼 TaskRejectedException 도 같은 503 핸들러가 받는다")
	void mapsSpringTaskRejectedTo503() {
		TaskRejectedException rejected = new TaskRejectedException("Executor did not accept task");

		assertThat(rejected).isInstanceOf(RejectedExecutionException.class);

		ResponseEntity<ErrorResponse> response = handler.handleRejectedExecution(rejected);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("SERVER_BUSY");
	}
}
