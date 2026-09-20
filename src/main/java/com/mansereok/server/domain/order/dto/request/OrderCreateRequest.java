package com.mansereok.server.domain.order.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderCreateRequest {

	@NotNull(message = "상품 ID는 필수입니다.")
	private Long subCategoryId; // 구매할 상품 ID
	private String discountCode; // 프론트가 보낼 할인 코드.

	private Long couponId; // 프론트가 보낼 쿠폰 ID
}
