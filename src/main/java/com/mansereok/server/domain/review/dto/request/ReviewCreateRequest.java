package com.mansereok.server.domain.review.dto.request;

import com.mansereok.server.domain.review.entity.Review;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ReviewCreateRequest {

	@NotNull(message = "상품 ID는 필수입니다.")
	private Long subCategoryId;

	@NotNull(message = "주문 ID는 필수입니다.") // 추가된 필드
	private Long orderId;

	// @Size 는 null 을 통과시키므로 @NotBlank 로 빠진 값과 공백만 있는 값을 먼저 막는다. 앞뒤 공백을 뺀 길이는 Review.write 가 한 번 더 본다.
	@NotBlank(message = "리뷰 내용을 입력해주세요.")
	@Size(min = Review.MIN_CONTENT_LENGTH, max = Review.MAX_CONTENT_LENGTH,
		message = "리뷰 내용은 {min}자 이상 {max}자 이하로 입력해주세요.")
	private String content;
}
