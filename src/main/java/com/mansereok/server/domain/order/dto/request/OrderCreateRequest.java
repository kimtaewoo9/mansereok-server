package com.mansereok.server.domain.order.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderCreateRequest {

	private Long subCategoryId; // 구매할 상품 ID
}
