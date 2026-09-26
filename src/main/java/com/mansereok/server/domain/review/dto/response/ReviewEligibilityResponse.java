package com.mansereok.server.domain.review.dto.response;

import com.mansereok.server.domain.review.service.RejectionReason;

/**
 * 리뷰 작성 자격 조회 결과.
 *
 * <p>JSON 키는 구성 요소 이름 그대로 eligible, reason, message 다. 프런트가 읽는 키가 eligible 이므로 구성 요소 이름을
 * isEligible 로 바꾸지 않는다. 바꾸면 컴파일과 서버 테스트는 통과해도 JSON 키가 isEligible 로 바뀐다.
 *
 * @param eligible 리뷰를 쓸 수 있으면 true
 * @param reason   쓸 수 없는 이유. 쓸 수 있으면 null
 * @param message  사용자에게 보여줄 문구
 */
public record ReviewEligibilityResponse(
	boolean eligible,
	RejectionReason reason,
	String message
) {

	public static ReviewEligibilityResponse allowed() {
		return new ReviewEligibilityResponse(true, null, "리뷰 작성이 가능합니다.");
	}

	public static ReviewEligibilityResponse rejected(RejectionReason reason) {
		return new ReviewEligibilityResponse(false, reason, reason.getMessage());
	}
}
