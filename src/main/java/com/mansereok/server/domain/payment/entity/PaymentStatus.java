package com.mansereok.server.domain.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
	PAID("PAID", "결제완료"),
	VIRTUAL_ACCOUNT_ISSUED("VIRTUAL_ACCOUNT_ISSUED", "가상계좌 발급"),
	FAILED("FAILED", "결제실패"),
	CANCELLED("CANCELLED", "결제취소"),
	READY("READY", "미결제"); // 결제 진행중 .

	private final String portOneStatus;
	private final String description;

	public static PaymentStatus fromPortOneStatus(String status) {
		for (PaymentStatus paymentStatus : values()) {
			if (paymentStatus.getPortOneStatus().equalsIgnoreCase(status)) {
				return paymentStatus;
			}
		}
		// 기본값은 실패 또는 처리 불가 상태로 설정할 수 있습니다.
		return FAILED;
	}
}
