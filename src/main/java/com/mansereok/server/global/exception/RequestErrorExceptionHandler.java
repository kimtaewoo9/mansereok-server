package com.mansereok.server.global.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 요청이 잘못됐거나(본문·파라미터·메서드·Content-Type) DB 잠금을 얻지 못한 경우를 500 대신 알맞은 상태 코드로 돌려준다.
 *
 * <p>{@link GlobalExceptionHandler} 의 {@code @ExceptionHandler(Exception.class)} 는 모든 예외를 받는다. 스프링 MVC 가 원래
 * 400·405·415 로 돌려주던 예외도 그보다 먼저 이 catch-all 에 잡혀 500 과 ERROR 로그가 됐다. 이 클래스는
 * {@link Ordered#HIGHEST_PRECEDENCE} 로 먼저 보고, 아래 타입만 번역한다. 응답 형식은 {@link ErrorResponse} 이고 로그는 WARN 한
 * 줄이다. 응답에는 필드 이름과 보낸 값만 담고 예외 원문(Jackson·SQL 메시지, 내부 클래스 이름)은 담지 않는다.
 *
 * <ul>
 *   <li>본문을 읽을 수 없음(깨진 JSON, 빈 본문, enum·날짜 형식 불일치): 400 INVALID_REQUEST_BODY</li>
 *   <li>파라미터 형식 불일치, 파라미터 제약 위반: 400 INVALID_PARAMETER</li>
 *   <li>필수 쿼리 파라미터 없음: 400 MISSING_PARAMETER</li>
 *   <li>지원하지 않는 HTTP 메서드: 405 METHOD_NOT_ALLOWED(Allow 헤더 포함)</li>
 *   <li>지원하지 않는 Content-Type: 415 UNSUPPORTED_MEDIA_TYPE</li>
 *   <li>DB 잠금 대기 초과·교착({@link PessimisticLockingFailureException} 과 하위의 CannotAcquireLockException):
 *   503 SERVER_BUSY. 다시 보내면 성공할 수 있는 혼잡이라 5xx 로 두어 포트원 웹훅 같은 호출자가 다시 보내게 한다.</li>
 * </ul>
 *
 * <p>일부러 하지 않는 것은 다음과 같다.
 * <ul>
 *   <li>{@code ServletRequestBindingException} 부모 타입은 잡지 않는다. 헤더 누락(MissingRequestHeaderException)도 그 하위라,
 *   잡으면 결제 스택 GlobalExceptionHandler 의 MISSING_HEADER 응답이 이 클래스의 응답으로 바뀐다.</li>
 *   <li>{@code ResponseEntityExceptionHandler} 를 상속하지 않는다. 상속하면 MVC 표준 예외를 모두 먼저 가져가서
 *   GlobalExceptionHandler 의 VALIDATION_ERROR(필드별 메시지), NoResourceFoundException 404, 결제 스택의 MISSING_HEADER 응답이
 *   바뀌고, 본문도 ErrorResponse 가 아니라 ProblemDetail 이 된다.</li>
 *   <li>DataIntegrityViolationException 을 한꺼번에 409 로 바꾸지 않는다. UNIQUE·NOT NULL·FK·길이 초과가 모두 같은 타입이라,
 *   경합이 나는 호출 지점에서 {@link UniqueConstraintViolations} 로 UNIQUE 위반만 도메인 예외로 바꾼다.</li>
 * </ul>
 *
 * <p>스프링은 advice 순서대로 예외 타입을 찾고, 없으면 그 advice 안에서 원인(cause) 타입까지 찾은 뒤 다음 advice 로 넘어간다.
 * 그래서 아래 타입을 원인으로 품은 다른 예외(예: 잠금 실패를 감싼 도메인 예외)도 이 클래스가 먼저 처리한다.
 *
 * <p>이 파일은 결제 스택이 그대로 복사해 쓴다. 고칠 때는 두 스택에 같게 고친다.
 */
@Slf4j
@Hidden
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class RequestErrorExceptionHandler {

	/**
	 * [400] 본문을 읽을 수 없음. 값의 형식이 틀린 경우(InvalidFormatException)에는 어느 필드의 어떤 값인지 알려준다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e,
		HttpServletRequest request) {
		if (e.getCause() instanceof InvalidFormatException invalidFormat) {
			String field = fieldPath(invalidFormat.getPath());
			// 보낸 값에는 이름·생년월일 같은 개인정보가 있을 수 있어 로그에는 필드 이름만 남긴다.
			log.warn("요청 본문 값의 형식이 맞지 않음: {} {}, field={}", request.getMethod(),
				request.getRequestURI(), field);
			String target = field.isEmpty() ? "요청 본문" : "요청 본문의 " + field;
			return badRequest("INVALID_REQUEST_BODY",
				target + " 값 '" + invalidFormat.getValue() + "' 이 올바른 형식이 아닙니다.");
		}
		log.warn("요청 본문을 읽을 수 없음: {} {}", request.getMethod(), request.getRequestURI());
		return badRequest("INVALID_REQUEST_BODY", "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요.");
	}

	/**
	 * [400] 쿼리 파라미터나 경로 변수의 형식이 타입과 맞지 않음(예: 숫자 자리에 abc).
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleParameterTypeMismatch(
		MethodArgumentTypeMismatchException e, HttpServletRequest request) {
		log.warn("요청 파라미터 형식이 맞지 않음: {} {}, parameter={}", request.getMethod(),
			request.getRequestURI(), e.getName());
		return badRequest("INVALID_PARAMETER",
			"요청 파라미터 '" + e.getName() + "' 의 값 '" + e.getValue() + "' 이 올바른 형식이 아닙니다.");
	}

	/**
	 * [400] 파라미터에 직접 붙인 제약(@Min, @Max 등) 위반. 파라미터 이름별 위반 메시지를 errors 에 담는다.
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleParameterConstraintViolation(
		HandlerMethodValidationException e, HttpServletRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (ParameterValidationResult result : e.getParameterValidationResults()) {
			String parameterName = result.getMethodParameter().getParameterName();
			List<MessageSourceResolvable> resolvableErrors = result.getResolvableErrors();
			if (parameterName != null && !resolvableErrors.isEmpty()) {
				errors.putIfAbsent(parameterName, resolvableErrors.get(0).getDefaultMessage());
			}
		}
		log.warn("요청 파라미터 제약 위반: {} {}, parameters={}", request.getMethod(),
			request.getRequestURI(), errors.keySet());
		ErrorResponse response = ErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
			"INVALID_PARAMETER", "요청 파라미터 값이 올바르지 않습니다.", errors);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * [400] 필수 쿼리 파라미터가 없음.
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(
		MissingServletRequestParameterException e, HttpServletRequest request) {
		log.warn("필수 요청 파라미터 없음: {} {}, parameter={}", request.getMethod(),
			request.getRequestURI(), e.getParameterName());
		return badRequest("MISSING_PARAMETER",
			"필수 요청 파라미터 '" + e.getParameterName() + "' 가 없습니다.");
	}

	/**
	 * [405] 주소는 있지만 그 HTTP 메서드는 지원하지 않음. 지원하는 메서드를 Allow 헤더로 알려준다.
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(
		HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
		log.warn("지원하지 않는 HTTP 메서드: {} {}", request.getMethod(), request.getRequestURI());
		ErrorResponse response = ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.value(),
			"METHOD_NOT_ALLOWED", "이 주소는 " + e.getMethod() + " 요청을 지원하지 않습니다.");
		Set<HttpMethod> supportedMethods = e.getSupportedHttpMethods();
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
			.headers(headers -> {
				if (supportedMethods != null) {
					headers.setAllow(supportedMethods);
				}
			})
			.body(response);
	}

	/**
	 * [415] 본문의 Content-Type 을 읽을 수 없음. 받을 수 있는 형식을 Accept 헤더로 알려준다.
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
		HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
		MediaType contentType = e.getContentType();
		log.warn("지원하지 않는 Content-Type: {} {}, contentType={}", request.getMethod(),
			request.getRequestURI(), contentType);
		String message = contentType == null
			? "Content-Type 헤더가 없거나 읽을 수 없습니다."
			: "Content-Type '" + contentType + "' 은 지원하지 않습니다.";
		ErrorResponse response = ErrorResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
			"UNSUPPORTED_MEDIA_TYPE", message);
		List<MediaType> supportedMediaTypes = e.getSupportedMediaTypes();
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.headers(headers -> {
				if (!supportedMediaTypes.isEmpty()) {
					headers.setAccept(supportedMediaTypes);
				}
			})
			.body(response);
	}

	/**
	 * [503] 행 잠금을 기다리다 시간이 넘었거나(MySQL 1205) 교착으로 롤백됨(1213). 다른 요청과 같은 행을 두고 부딪힌 것이라 잠시 뒤
	 * 다시 보내면 성공할 수 있다. 예외 메시지에는 SQL 이 들어 있어 응답에는 담지 않는다.
	 */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleLockConflict(PessimisticLockingFailureException e,
		HttpServletRequest request) {
		log.warn("DB 잠금을 얻지 못해 요청을 처리하지 못함: {} {}, {}: {}", request.getMethod(),
			request.getRequestURI(), e.getClass().getSimpleName(), e.getMostSpecificCause().getMessage());
		ErrorResponse response = ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
			"SERVER_BUSY", "요청이 몰려 지금은 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
	}

	private static ResponseEntity<ErrorResponse> badRequest(String errorCode, String message) {
		ErrorResponse response = ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), errorCode, message);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	/**
	 * Jackson 이 기록한 위치를 items[0].gender 모양의 필드 경로로 바꾼다. Jackson 의 getPathReference() 는 내부 클래스 이름을
	 * 담고 있어 쓰지 않는다. 본문 전체가 값 하나인 경우처럼 경로가 비어 있으면 빈 문자열을 돌려준다.
	 */
	private static String fieldPath(List<JsonMappingException.Reference> path) {
		StringBuilder fieldPath = new StringBuilder();
		for (JsonMappingException.Reference reference : path) {
			if (reference.getFieldName() != null) {
				if (!fieldPath.isEmpty()) {
					fieldPath.append('.');
				}
				fieldPath.append(reference.getFieldName());
			} else if (reference.getIndex() >= 0) {
				fieldPath.append('[').append(reference.getIndex()).append(']');
			}
		}
		return fieldPath.toString();
	}
}
