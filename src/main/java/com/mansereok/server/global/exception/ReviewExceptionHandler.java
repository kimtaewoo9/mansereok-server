package com.mansereok.server.global.exception;

import com.mansereok.server.domain.review.controller.ReviewController;
import com.mansereok.server.domain.review.service.RejectionReason;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 리뷰 작성 거절({@link ReviewNotAllowedException})을 리뷰 전용 errorCode REVIEW_NOT_ALLOWED 로 돌려준다. 상태 코드와 메시지는
 * 거절 이유({@link RejectionReason})에 정해 둔 값이다. 이미 쓴 주문이면 409, 남의 주문이면 403, 없는 주문이면 404, 나머지는
 * 400 이다.
 *
 * <p>리뷰 거절은 사용자가 고치거나 받아들이면 되는 일이라 WARN 한 줄만 남긴다.
 *
 * <p>{@link ReviewController} 만 맡고 순서는 {@link Ordered#HIGHEST_PRECEDENCE} 다. {@link GlobalExceptionHandler} 의
 * {@code @ExceptionHandler(Exception.class)} 도 이 예외를 받을 수 있어서, 이 클래스가 먼저 봐야 500 이 되지 않는다.
 */
@Slf4j
@Hidden
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReviewController.class)
public class ReviewExceptionHandler {

	@ExceptionHandler(ReviewNotAllowedException.class)
	public ResponseEntity<ErrorResponse> handleReviewNotAllowed(ReviewNotAllowedException e) {
		RejectionReason reason = e.getReason();
		log.warn("리뷰 작성 거절: reason={}", reason);
		ErrorResponse response = ErrorResponse.of(reason.getStatus().value(), "REVIEW_NOT_ALLOWED",
			reason.getMessage());
		return ResponseEntity.status(reason.getStatus()).body(response);
	}
}
