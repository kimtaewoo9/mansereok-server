package com.mansereok.server.domain.payment.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대사 한 번의 실행 기록.
 *
 * <p>같은 날짜를 다시 대사하면 새 run 이 생기고 이전 run 은 그대로 남는다. 언제 무엇을 봤는지가 감사 이력이라
 * 지우거나 덮어쓰지 않는다.
 */
@Entity
@Table(name = "payment_reconciliation_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReconciliationRun {

	/** error_message 컬럼 길이. 예외 메시지는 이보다 길 수 있어 엔티티가 직접 자른다. */
	public static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 대사 대상 영업일(KST). */
	private LocalDate targetDate;

	/** DB 조회에 쓴 창. PG 조회 창(Instant)을 JVM 시간대로 옮긴 값이다. */
	private LocalDateTime windowFrom;
	private LocalDateTime windowUntil;

	@Enumerated(EnumType.STRING)
	private ReconciliationStatus status;

	private int pgPaymentCount;
	/** 대조한 DB 결제 건수. 무료 결제를 빼고 impUid 로 중복을 걷어낸 수다. */
	private int dbPaymentCount;
	private int mismatchCount;

	private LocalDateTime startedAt;
	private LocalDateTime finishedAt;

	@Column(length = ERROR_MESSAGE_MAX_LENGTH)
	private String errorMessage;

	public static PaymentReconciliationRun start(LocalDate targetDate, LocalDateTime windowFrom,
		LocalDateTime windowUntil, LocalDateTime now) {
		PaymentReconciliationRun run = new PaymentReconciliationRun();
		run.targetDate = targetDate;
		run.windowFrom = windowFrom;
		run.windowUntil = windowUntil;
		run.status = ReconciliationStatus.RUNNING;
		run.startedAt = now;
		return run;
	}

	public void complete(int pgPaymentCount, int dbPaymentCount, int mismatchCount,
		LocalDateTime now) {
		this.pgPaymentCount = pgPaymentCount;
		this.dbPaymentCount = dbPaymentCount;
		this.mismatchCount = mismatchCount;
		this.status = ReconciliationStatus.COMPLETED;
		this.finishedAt = now;
	}

	public void fail(String errorMessage, LocalDateTime now) {
		this.errorMessage = truncate(errorMessage);
		this.status = ReconciliationStatus.FAILED;
		this.finishedAt = now;
	}

	private static String truncate(String errorMessage) {
		if (errorMessage == null || errorMessage.length() <= ERROR_MESSAGE_MAX_LENGTH) {
			return errorMessage;
		}
		return errorMessage.substring(0, ERROR_MESSAGE_MAX_LENGTH);
	}
}
