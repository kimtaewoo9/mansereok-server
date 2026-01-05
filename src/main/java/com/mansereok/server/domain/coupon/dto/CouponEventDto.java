package com.mansereok.server.domain.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CouponEventDto {

	private Long templateId;
	private String name;
	private int discountValue;

	private boolean isIssued;
	private boolean isSoldOut;
}
