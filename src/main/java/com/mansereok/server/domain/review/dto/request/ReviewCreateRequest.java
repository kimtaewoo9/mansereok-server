package com.mansereok.server.domain.review.dto.request;

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

	@Size(min = 20, message = "리뷰 내용은 최소 20자 이상이어야 합니다.")
	private String content;
}
