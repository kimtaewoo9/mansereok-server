package com.mansereok.server.domain.discount.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DiscountCheckRequest {

	private Long subCategoryId;
	private String discountCode;
}
