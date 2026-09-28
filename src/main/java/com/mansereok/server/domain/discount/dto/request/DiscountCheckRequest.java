package com.mansereok.server.domain.discount.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DiscountCheckRequest {

	@NotNull(message = "상품 ID는 필수입니다.")
	private Long subCategoryId;

	// 비어 있으면 할인 없이 원래 금액을 돌려준다.
	private String discountCode;
}
