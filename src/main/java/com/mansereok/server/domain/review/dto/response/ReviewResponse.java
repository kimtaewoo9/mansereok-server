package com.mansereok.server.domain.review.dto.response;

import com.mansereok.server.domain.review.entity.Review;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ReviewResponse {

	private Long reviewId;
	private Long subCategoryId;
	private String content;
	private String email;
	private String userName;
	private LocalDateTime createdAt;

	public ReviewResponse(Review review, String productName) {
		this.reviewId = review.getId();
		this.subCategoryId = review.getSubCategoryId();
		this.content = review.getContent();
		this.email = review.getUserEmail();
		this.createdAt = review.getCreatedAt();
	}

	public static ReviewResponse from(Review review) {
		ReviewResponse response = new ReviewResponse();
		response.reviewId = review.getId();
		response.subCategoryId = review.getSubCategoryId();
		response.content = review.getContent();
		response.email = review.getUserEmail();
		response.userName = review.getUserName();
		response.createdAt = review.getCreatedAt();
		return response;
	}
}
