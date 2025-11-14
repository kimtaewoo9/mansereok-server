package com.mansereok.server.domain.discount.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DiscountCheckResponse {

	private int originalAmount;
	private int discountedAmount; // 할인된 최종 금액.

	private int discountAmount; // 할인액
	private String message;
}
