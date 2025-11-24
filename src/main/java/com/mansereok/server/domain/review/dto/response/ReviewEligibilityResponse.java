package com.mansereok.server.domain.review.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReviewEligibilityResponse {

	private boolean isEligible;      // 작성 가능 여부
	private RejectionReason reason;  // 불가능 사유 (가능하면 null)
	private String message;          // 사용자에게 보여줄 메시지

	public static ReviewEligibilityResponse eligible() {
		return new ReviewEligibilityResponse(true, null, "리뷰 작성이 가능합니다.");
	}

	public static ReviewEligibilityResponse ineligible(RejectionReason reason, String message) {
		return new ReviewEligibilityResponse(false, reason, message);
	}

	@Getter
	public enum RejectionReason {
		ORDER_NOT_FOUND,    // 주문 정보 없음
		NOT_OWNER,          // 본인 주문 아님
		MISMATCH_PRODUCT,   // 상품 정보 불일치
		NOT_PAID,           // 결제 완료 안됨
		EXPIRED,            // 30일 경과
		ALREADY_WRITTEN     // 이미 작성함
	}
}
