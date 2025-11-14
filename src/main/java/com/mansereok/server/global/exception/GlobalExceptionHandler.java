package com.mansereok.server.global.exception;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@Hidden
public class GlobalExceptionHandler {

	/**
	 * [400 Bad Request] Validation 실패
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
		MethodArgumentNotValidException e) {
		Map<String, String> errors = new HashMap<>();
		e.getBindingResult().getAllErrors().forEach((error) -> {
			String fieldName = ((FieldError) error).getField();
			String errorMessage = error.getDefaultMessage();
			errors.put(fieldName, errorMessage);
		});

		log.warn("Validation 실패: {}", errors);
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.BAD_REQUEST.value(),
			"VALIDATION_ERROR",
			"입력값 검증에 실패했습니다.",
			errors
		);
		return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
	}

	/**
	 * [400 Bad Request] 비즈니스 로직 예외 (PaymentException 등)
	 */
	@ExceptionHandler(PaymentException.class)
	public ResponseEntity<ErrorResponse> handlePaymentException(PaymentException e) {
		log.warn("결제/주문 비즈니스 예외: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.BAD_REQUEST.value(),
			"PAYMENT_ERROR",
			e.getMessage()
		);
		return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
	}

	/**
	 * [400 Bad Request] IllegalArgumentException (잘못된 인자)
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
		IllegalArgumentException e) {
		log.warn("잘못된 인자 전달: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.BAD_REQUEST.value(),
			"INVALID_INPUT",
			e.getMessage()
		);
		return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
	}

	/**
	 * [401 Unauthorized] 잘못된 인증 정보 (비밀번호 틀림 등)
	 */
	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ErrorResponse> handleBadCredentialsException(
		BadCredentialsException e) {
		log.warn("로그인 실패 (잘못된 인증 정보): email 또는 password 불일치");
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.UNAUTHORIZED.value(),
			"INVALID_CREDENTIALS",
			"이메일 또는 비밀번호가 일치하지 않습니다."
		);
		return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
	}

	/**
	 * [401 Unauthorized] 기타 인증 실패 (계정 잠김, 만료 등)
	 */
	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAuthenticationException(
		AuthenticationException e) {
		log.warn("인증 실패: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.UNAUTHORIZED.value(),
			"AUTHENTICATION_FAILED",
			"인증에 실패했습니다. 다시 로그인해주세요."
		);
		return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler(InvalidRefreshTokenException.class)
	public ResponseEntity<ErrorResponse> handleInvalidRefreshTokenException(
		InvalidRefreshTokenException e) {
		log.warn("유효하지 않은 리프레시 토큰: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.UNAUTHORIZED.value(),
			"INVALID_REFRESH_TOKEN",
			e.getMessage()
		);
		return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
	}

	/**
	 * [403 Forbidden] 인가(권한) 실패
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDeniedException(
		AccessDeniedException e) {
		log.warn("권한 없는 리소스 접근 시도: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.FORBIDDEN.value(),
			"FORBIDDEN",
			"이 리소스에 접근할 권한이 없습니다."
		);
		return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
	}

	/**
	 * [404 Not Found] 리소스를 찾을 수 없음
	 */
	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleEntityNotFoundException(
		EntityNotFoundException e) {
		log.warn("엔티티 조회 실패: {}", e.getMessage());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.NOT_FOUND.value(),
			"NOT_FOUND",
			"요청하신 리소스를 찾을 수 없습니다."
		);
		return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	/**
	 * [500 Internal Server Error] 처리되지 않은 모든 서버 내부 오류 RuntimeException 포함하여 모든 예외를 마지막에 처리
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGlobalException(Exception e) {
		// 예상하지 못한 에러는 상세 로그 남기기
		log.error("처리되지 않은 서버 내부 오류: {}", e.getMessage(), e);

		ErrorResponse response = ErrorResponse.of(
			HttpStatus.INTERNAL_SERVER_ERROR.value(),
			"INTERNAL_SERVER_ERROR",
			"서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
		);
		return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@ExceptionHandler(GptApiFailedException.class)
	public ResponseEntity<ErrorResponse> handleGptApiFailed(GptApiFailedException e) {
		log.error("🚨 외부 API (GPT) 호출 최종 실패: {}", e.getMessage(), e.getCause());

		HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE; // 기본값 503

		if (e.getCause() instanceof HttpStatusCodeException) {
			try {
				HttpStatusCodeException httpEx = (HttpStatusCodeException) e.getCause();
				status = HttpStatus.resolve(httpEx.getStatusCode().value());
				if (status == null) {
					status = HttpStatus.SERVICE_UNAVAILABLE;
				}
			} catch (Exception ex) {
				log.warn("상태 코드 파싱 실패, 기본값(503) 사용", ex);
			}
		}

		ErrorResponse response = ErrorResponse.of(
			status.value(),
			"EXTERNAL_API_FAILURE",
			"사주 해석 서비스에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
		);
		return new ResponseEntity<>(response, status);
	}


}
