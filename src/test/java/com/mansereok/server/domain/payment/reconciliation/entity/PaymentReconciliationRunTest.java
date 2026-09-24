package com.mansereok.server.domain.payment.reconciliation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReconciliationRunTest {

	private static final LocalDate TARGET_DATE = LocalDate.of(2026, 9, 23);
	private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 9, 23, 0, 0);
	private static final LocalDateTime WINDOW_UNTIL = LocalDateTime.of(2026, 9, 24, 0, 0);
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 24, 5, 0);
	private static final LocalDateTime FINISHED_AT = LocalDateTime.of(2026, 9, 24, 5, 1);

	private PaymentReconciliationRun startedRun() {
		return PaymentReconciliationRun.start(TARGET_DATE, WINDOW_FROM, WINDOW_UNTIL, STARTED_AT);
	}

	@Test
	@DisplayName("start 는 창과 시작 시각을 기록하고 RUNNING 으로 둔다")
	void start_isRunning() {
		PaymentReconciliationRun run = startedRun();

		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.RUNNING);
		assertThat(run.getTargetDate()).isEqualTo(TARGET_DATE);
		assertThat(run.getWindowFrom()).isEqualTo(WINDOW_FROM);
		assertThat(run.getWindowUntil()).isEqualTo(WINDOW_UNTIL);
		assertThat(run.getStartedAt()).isEqualTo(STARTED_AT);
		assertThat(run.getFinishedAt()).isNull();
	}

	@Test
	@DisplayName("complete 는 건수와 끝난 시각을 기록하고 COMPLETED 로 바꾼다")
	void complete_recordsCounts() {
		PaymentReconciliationRun run = startedRun();

		run.complete(12, 10, 2, FINISHED_AT);

		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.COMPLETED);
		assertThat(run.getPgPaymentCount()).isEqualTo(12);
		assertThat(run.getDbPaymentCount()).isEqualTo(10);
		assertThat(run.getMismatchCount()).isEqualTo(2);
		assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
		assertThat(run.getErrorMessage()).isNull();
	}

	@Test
	@DisplayName("fail 은 오류 메시지와 끝난 시각을 기록하고 FAILED 로 바꾼다")
	void fail_recordsErrorMessage() {
		PaymentReconciliationRun run = startedRun();

		run.fail("포트원 일시 장애", FINISHED_AT);

		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("포트원 일시 장애");
		assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
	}

	@Test
	@DisplayName("fail 은 컬럼 길이를 넘는 오류 메시지를 잘라 담는다")
	void fail_truncatesLongErrorMessage() {
		PaymentReconciliationRun run = startedRun();
		String longMessage = "오".repeat(PaymentReconciliationRun.ERROR_MESSAGE_MAX_LENGTH + 10);

		run.fail(longMessage, FINISHED_AT);

		assertThat(run.getErrorMessage())
			.hasSize(PaymentReconciliationRun.ERROR_MESSAGE_MAX_LENGTH);
	}
}
