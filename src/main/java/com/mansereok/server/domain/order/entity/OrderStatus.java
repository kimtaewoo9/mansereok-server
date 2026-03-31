package com.mansereok.server.domain.order.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {

	PENDING("결제 대기"),
	VIRTUAL_ACCOUNT_ISSUED("가상계좌 발급됨"),
	PAID("결제 완료"),
	CANCELLED("결제 취소"),
	FAILED("결제 실패"),
	EXPIRED("주문 만료");

	private final String description;
}
