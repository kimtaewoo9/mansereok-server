package com.mansereok.server.domain.payment.reconciliation.service;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;

/**
 * DB 에만 있는 결제를 포트원에 단건 조회한 결과.
 *
 * <p>"PG 에 없다"(404)와 "조회하지 못했다"(일시 장애)는 운영자가 해야 할 일이 달라서 서로 다른 불일치로
 * 기록된다. 조회 실패 한 건이 대사 전체를 멈추지 않도록 예외 대신 값으로 들고 다닌다.
 */
public sealed interface PgLookup {

	static PgLookup found(PortOnePaymentResponse payment) {
		return new Found(payment);
	}

	static PgLookup notFound() {
		return new NotFound();
	}

	static PgLookup failed(String message) {
		return new Failed(message);
	}

	record Found(PortOnePaymentResponse payment) implements PgLookup {

	}

	record NotFound() implements PgLookup {

	}

	record Failed(String message) implements PgLookup {

	}
}
