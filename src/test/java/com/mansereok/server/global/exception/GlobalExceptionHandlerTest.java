package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.portone.sdk.server.errors.WebhookVerificationException;
import jakarta.persistence.EntityNotFoundException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestHeader;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	/**
	 * MissingRequestHeaderException 생성용 더미 메서드. 실제 컨트롤러 시그니처에 테스트가 묶이지 않도록 둔다.
	 */
	@SuppressWarnings("unused")
	private void dummy(@RequestHeader("webhook-signature") String signature) {
	}

	@Test
	@DisplayName("웹훅 서명 검증 실패는 401 과 WEBHOOK_SIGNATURE_INVALID 로 응답한다")
	void webhookVerificationException_mapsTo401() {
		// given
		WebhookVerificationException e = new WebhookVerificationException("서명 불일치", null);

		// when
		ResponseEntity<ErrorResponse> response = handler.handleWebhookVerificationException(e);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getStatus()).isEqualTo(401);
		assertThat(response.getBody().getErrorCode()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");
	}

	@Test
	@DisplayName("필수 요청 헤더 누락은 400 과 MISSING_HEADER 로 응답하고 헤더 이름을 알려준다")
	void missingRequestHeaderException_mapsTo400() throws Exception {
		// given
		Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class);
		MissingRequestHeaderException e = new MissingRequestHeaderException("webhook-signature",
			MethodParameter.forExecutable(method, 0));

		// when
		ResponseEntity<ErrorResponse> response = handler.handleMissingRequestHeaderException(e);

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getStatus()).isEqualTo(400);
		assertThat(response.getBody().getErrorCode()).isEqualTo("MISSING_HEADER");
		assertThat(response.getBody().getMessage()).contains("webhook-signature");
	}

	@Test
	@DisplayName("AccessDeniedException 은 403 과 FORBIDDEN 으로 응답한다")
	void accessDeniedException_mapsTo403() {
		// when
		ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(
			new AccessDeniedException("본인의 주문만 조회할 수 있습니다."));

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("FORBIDDEN");
	}

	@Test
	@DisplayName("PortOneUnavailableException 은 503 과 PORTONE_UNAVAILABLE 로 응답한다")
	void portOneUnavailableException_mapsTo503() {
		// when
		ResponseEntity<ErrorResponse> response = handler.handlePortOneUnavailableException(
			new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.",
				new RuntimeException("Read timed out")));

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getStatus()).isEqualTo(503);
		assertThat(response.getBody().getErrorCode()).isEqualTo("PORTONE_UNAVAILABLE");
		assertThat(response.getBody().getMessage())
			.isEqualTo("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.");
	}

	@Test
	@DisplayName("EntityNotFoundException 은 404 와 NOT_FOUND 로 응답한다")
	void entityNotFoundException_mapsTo404() {
		// when
		ResponseEntity<ErrorResponse> response = handler.handleEntityNotFoundException(
			new EntityNotFoundException("주문을 찾을 수 없습니다."));

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("NOT_FOUND");
	}
}
