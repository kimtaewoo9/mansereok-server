package com.mansereok.server.domain.payment.reconciliation.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * PG 와 DB 가 어긋난 모양.
 *
 * <p>대사는 고치지 않고 기록만 하므로, 운영자가 어떤 조치를 해야 하는지 구분할 수 있을 만큼만 나눈다.
 */
@Getter
@RequiredArgsConstructor
public enum MismatchType {
	MISSING_IN_DB("PG 에 있는 거래가 DB 에 없음 (웹훅 유실 의심)"),
	MISSING_IN_PG("DB 에 있는 결제가 PG 에 없음"),
	AMOUNT_MISMATCH("PG 와 DB 의 결제 금액이 다름"),
	STATUS_MISMATCH("PG 와 DB 의 결제 상태가 다름"),
	CANCEL_REQUESTED_STALE("환불 도중 CANCEL_REQUESTED 로 멈춤"),
	PG_LOOKUP_FAILED("PG 단건 조회가 일시 장애로 실패함");

	private final String description;
}
