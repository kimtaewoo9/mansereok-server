package com.mansereok.server.domain.payment.reconciliation.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.payment.reconciliation.service.PaymentReconciliationService;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationSchedulerTest {

	/** UTC 로는 9월 24일이지만 KST 로는 이미 9월 25일 새벽인 시각. */
	private static final Clock FIXED_CLOCK =
		Clock.fixed(Instant.parse("2026-09-24T18:00:00Z"), ZoneOffset.UTC);
	private static final LocalDate YESTERDAY_IN_KST = LocalDate.of(2026, 9, 24);

	@Mock
	private PaymentReconciliationService paymentReconciliationService;

	private PaymentReconciliationScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PaymentReconciliationScheduler(paymentReconciliationService, FIXED_CLOCK);
	}

	@Test
	@DisplayName("대사 대상은 KST 기준 어제다 (JVM 시간대의 어제가 아니다)")
	void targetDateIsYesterdayInKst() {
		scheduler.reconcileYesterday();

		verify(paymentReconciliationService).reconcile(YESTERDAY_IN_KST);
	}

	@Test
	@DisplayName("대사가 실패해도 예외를 삼킨다 (서비스가 이미 FAILED 를 기록했다)")
	void swallowsReconciliationFailure() {
		willThrow(new PortOneUnavailableException("포트원 일시 장애"))
			.given(paymentReconciliationService).reconcile(YESTERDAY_IN_KST);

		assertThatCode(() -> scheduler.reconcileYesterday()).doesNotThrowAnyException();

		verify(paymentReconciliationService).reconcile(YESTERDAY_IN_KST);
	}
}
