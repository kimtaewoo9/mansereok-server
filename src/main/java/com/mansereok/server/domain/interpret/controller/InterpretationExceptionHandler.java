package com.mansereok.server.domain.interpret.controller;

import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 해석 API(ManseryeokController)에서만 쓰는 예외 응답.
 *
 * <p>GlobalExceptionHandler 에는 모든 예외를 500 으로 받는 처리기가 있다. 스프링은 순서가 앞선 advice 부터 맞는 처리기를 찾으므로,
 * 이 advice 를 가장 앞에 두어 해석 전용 예외가 500 으로 새지 않게 한다. 여기서 받지 않는 예외는 그대로 GlobalExceptionHandler 로
 * 넘어간다. 대상 컨트롤러를 ManseryeokController 하나로 좁혀 다른 API 의 응답은 바꾸지 않는다.
 */
@Slf4j
@Hidden
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ManseryeokController.class)
public class InterpretationExceptionHandler {

	/**
	 * [409 Conflict] 이미 해석 중이거나 완료된 결제로 해석을 다시 요청했다. GPT 는 부르지 않았다. 결제 ID 는 로그에만 남긴다.
	 */
	@ExceptionHandler(InterpretationAlreadyStartedException.class)
	public ResponseEntity<ErrorResponse> handleInterpretationAlreadyStarted(
		InterpretationAlreadyStartedException e) {
		log.warn("이미 해석 중이거나 완료된 결제로 해석을 다시 요청: paymentId={}", e.getPaymentId());
		ErrorResponse response = ErrorResponse.of(
			HttpStatus.CONFLICT.value(),
			"INTERPRETATION_ALREADY_STARTED",
			"이미 진행 중이거나 완료된 해석입니다. 결과 화면에서 확인해 주세요."
		);
		return new ResponseEntity<>(response, HttpStatus.CONFLICT);
	}
}
