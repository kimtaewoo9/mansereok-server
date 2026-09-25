package com.mansereok.server.domain.review.dto.response;

import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.global.util.PersonalInfoMasker;
import java.time.LocalDateTime;

/**
 * 리뷰 목록과 작성 결과로 내려주는 리뷰 한 건.
 *
 * <p>리뷰 목록은 로그인하지 않은 사람도 본다. 그래서 작성자 이메일은 담지 않고, 이름은 가린 값(홍*동)만 담는다. 응답에
 * 나가는 필드는 이 record 의 구성 요소가 전부다. 새 필드를 더할 때는 누구에게 보여도 되는 값인지 먼저 확인한다.
 *
 * @param userName 가린 작성자 이름
 */
public record ReviewResponse(
	Long reviewId,
	Long subCategoryId,
	String content,
	String userName,
	LocalDateTime createdAt
) {

	public static ReviewResponse from(Review review) {
		return new ReviewResponse(
			review.getId(),
			review.getSubCategoryId(),
			review.getContent(),
			PersonalInfoMasker.maskName(review.getUserName()),
			review.getCreatedAt()
		);
	}
}
