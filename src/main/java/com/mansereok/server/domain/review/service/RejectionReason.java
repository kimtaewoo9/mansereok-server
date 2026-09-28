package com.mansereok.server.domain.review.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 하나로 리뷰를 쓸 수 없는 이유.
 *
 * <p>자격 조회 API 는 이 값을 reason 으로 돌려주고, 작성 API 는 이 값의 상태 코드와 메시지로 거절한다. 두 API 가 같은 문구를
 * 쓰도록 사용자에게 보여줄 메시지와 거절할 때의 HTTP 상태를 상수마다 함께 둔다. 상수 이름은 자격 조회 응답의 reason 값으로 그대로
 * 나가므로 바꾸지 않는다.
 *
 * <p>없는 주문(404 ORDER_NOT_FOUND)과 남의 주문(403 NOT_OWNER)은 상태 코드와 문구가 달라서, 남의 주문 id 를 넣어 보면 그 주문이
 * 있는지 알 수 있다. 두 경우를 나눠 답하기로 정한 것이라 그대로 둔다. 숨겨야 하면 두 경우를 같은 상태 코드와 문구로 합친다.
 */
@Getter
@RequiredArgsConstructor
public enum RejectionReason {

	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "유효하지 않은 주문 정보입니다."),
	NOT_OWNER(HttpStatus.FORBIDDEN, "본인의 주문에 대해서만 리뷰를 작성할 수 있습니다."),
	MISMATCH_PRODUCT(HttpStatus.BAD_REQUEST, "주문한 상품 정보와 일치하지 않습니다."),
	NOT_PAID(HttpStatus.BAD_REQUEST, "결제가 완료된 주문만 리뷰를 작성할 수 있습니다."),
	EXPIRED(HttpStatus.BAD_REQUEST,
		"구매 후 " + ReviewEligibilityPolicy.REVIEW_DEADLINE_DAYS + "일이 지나 리뷰를 작성할 수 없습니다."),
	ALREADY_WRITTEN(HttpStatus.CONFLICT, "이미 해당 주문에 대한 리뷰를 작성하셨습니다.");

	/** 작성 API 가 이 이유로 거절할 때의 HTTP 상태. */
	private final HttpStatus status;

	/** 사용자에게 보여줄 문구. */
	private final String message;
}
