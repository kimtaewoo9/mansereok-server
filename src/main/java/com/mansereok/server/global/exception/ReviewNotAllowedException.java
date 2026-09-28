package com.mansereok.server.global.exception;

import com.mansereok.server.domain.review.service.RejectionReason;
import lombok.Getter;

/**
 * 리뷰를 쓸 수 없는 주문으로 리뷰를 쓰려 할 때 던진다. {@link ReviewExceptionHandler} 가 이유에 정해 둔 상태 코드와 errorCode
 * REVIEW_NOT_ALLOWED 로 답한다. 메시지는 이유에 정해 둔 사용자 문구다.
 */
@Getter
public class ReviewNotAllowedException extends RuntimeException {

	private final RejectionReason reason;

	public ReviewNotAllowedException(RejectionReason reason) {
		super(reason.getMessage());
		this.reason = reason;
	}
}
