package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("OpenAI 일시 장애는 503 OPENAI_UNAVAILABLE 로 내려간다")
	void mapsUnavailableTo503() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiUnavailable(
			new OpenAiUnavailableException("재시도 3회 + fallback 1회 실패"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_UNAVAILABLE");
		assertThat(response.getBody().getStatus()).isEqualTo(503);
	}

	@Test
	@DisplayName("OpenAI 요청 오류는 400 OPENAI_REQUEST_ERROR 로 내려간다")
	void mapsRequestErrorTo400() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiRequest(
			new OpenAiRequestException("OpenAI 요청이 거절되었습니다. status: 400"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_REQUEST_ERROR");
		assertThat(response.getBody().getStatus()).isEqualTo(400);
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
	}

	@Test
	@DisplayName("거부 응답은 502 OPENAI_REFUSAL 로 내려간다")
	void mapsRefusalTo502() {
		ResponseEntity<ErrorResponse> response = handler.handleOpenAiRefusal(
			new OpenAiRefusalException("도와드릴 수 없습니다."));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("OPENAI_REFUSAL");
		assertThat(response.getBody().getStatus()).isEqualTo(502);
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
}
