package com.mansereok.server.domain.payment.reconciliation.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 대사 한 번(run)의 진행 상태.
 *
 * <p>RUNNING 으로 남아 있는 run 은 배치가 도중에 죽었다는 뜻이라 운영자가 알아볼 수 있어야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum ReconciliationStatus {
	RUNNING("진행 중"),
	COMPLETED("완료"),
	FAILED("실패");

	private final String description;
}
