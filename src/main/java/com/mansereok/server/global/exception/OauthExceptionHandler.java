package com.mansereok.server.global.exception;

import com.mansereok.server.domain.auth.controller.OauthController;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 소셜 로그인에서 제공자 호출이 실패한 경우를 500 대신 401·503 으로 돌려준다.
 *
 * <ul>
 *   <li>{@link OauthLoginException}: 401 OAUTH_LOGIN_FAILED. 인가 코드 재사용·만료처럼 다시 로그인하면 되는 실패라 WARN 한 줄만
 *   남긴다.</li>
 *   <li>{@link OauthProviderUnavailableException}: 503 OAUTH_PROVIDER_UNAVAILABLE. 제공자 장애라 ERROR 로 남기되, 이유에 HTTP 상태나
 *   원인 예외 이름이 들어 있어 스택 트레이스는 남기지 않는다.</li>
 * </ul>
 *
 * <p>응답에는 정해 둔 문구만 담고 제공자 응답 원문이나 이유(reason)는 담지 않는다. 로그에도 제공자와 이유만 남기고 토큰·이메일·이름은
 * 남기지 않는다.
 *
 * <p>{@link OauthController} 만 맡고 순서는 {@link Ordered#HIGHEST_PRECEDENCE} 다. {@link GlobalExceptionHandler} 의
 * {@code @ExceptionHandler(Exception.class)} 는 이 두 예외도 받을 수 있어서, 이 클래스가 그보다 먼저 봐야 500 이 되지 않는다.
 * {@link RequestErrorExceptionHandler} 는 원인 체인까지 보므로 그보다도 앞에 둔다.
 */
@Slf4j
@Hidden
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OauthController.class)
public class OauthExceptionHandler {

	/**
	 * [401] 제공자가 요청을 거절했거나 로그인에 필요한 값을 주지 않음.
	 */
	@ExceptionHandler(OauthLoginException.class)
	public ResponseEntity<ErrorResponse> handleLoginFailed(OauthLoginException e) {
		log.warn("소셜 로그인 실패: provider={}, reason={}", e.getProvider(), e.getReason());
		ErrorResponse response = ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), "OAUTH_LOGIN_FAILED",
			"소셜 로그인에 실패했습니다. 처음부터 다시 로그인해주세요.");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
	}

	/**
	 * [503] 제공자에 닿지 못했거나 시간 안에 답이 없거나 제공자가 5xx 로 답함.
	 */
	@ExceptionHandler(OauthProviderUnavailableException.class)
	public ResponseEntity<ErrorResponse> handleProviderUnavailable(OauthProviderUnavailableException e) {
		log.error("소셜 로그인 제공자 장애: provider={}, reason={}", e.getProvider(), e.getReason());
		ErrorResponse response = ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
			"OAUTH_PROVIDER_UNAVAILABLE", "소셜 로그인 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해주세요.");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
	}
}
