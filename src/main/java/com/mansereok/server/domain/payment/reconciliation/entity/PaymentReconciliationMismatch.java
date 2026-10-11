package com.mansereok.server.domain.payment.reconciliation.entity;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대사에서 찾은 불일치 한 건.
 *
 * <p>타입마다 채워지는 칸이 달라서(PG 에만 있으면 db* 가 비고, DB 에만 있으면 pg* 가 빈다) 생성자를 열지 않고
 * 타입별 정적 팩토리만 둔다. 사람이 읽는 한 줄 요약인 {@code detail} 도 각 팩토리가 만든다.
 *
 * <p>고객 개인정보는 담지 않는다. 운영자가 포트원 콘솔과 DB 를 찾아갈 수 있는 식별자와 금액·상태만 남긴다.
 */
@Entity
@Table(name = "payment_reconciliation_mismatches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReconciliationMismatch {

	/** detail 컬럼 길이. 외부 예외 메시지가 들어올 수 있어 엔티티가 직접 자른다. */
	public static final int DETAIL_MAX_LENGTH = 500;

	private static final String UNKNOWN_PG_STATUS = "조회되지 않음";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long runId;

	@Enumerated(EnumType.STRING)
	private MismatchType type;

	private String impUid;
	private String merchantUid;

	/** 포트원이 준 원문 상태. 우리가 모르는 상태값도 그대로 남긴다. */
	private String pgStatus;
	private Long pgAmount;

	@Enumerated(EnumType.STRING)
	private PaymentStatus dbStatus;
	private Long dbAmount;

	@Column(length = DETAIL_MAX_LENGTH)
	private String detail;

	private LocalDateTime detectedAt;

	public static PaymentReconciliationMismatch missingInDb(Long runId,
		PortOnePaymentResponse pgPayment, LocalDateTime detectedAt) {
		return create(runId, MismatchType.MISSING_IN_DB, pgPayment.getId(),
			pgPayment.getMerchantUid(), statusOf(pgPayment), amountOf(pgPayment), null, null,
			"PG 에 %s 상태의 거래가 있는데 DB 에 결제 기록이 없습니다.".formatted(statusOf(pgPayment)),
			detectedAt);
	}

	public static PaymentReconciliationMismatch missingInPg(Long runId, Payment dbPayment,
		LocalDateTime detectedAt) {
		return create(runId, MismatchType.MISSING_IN_PG, dbPayment.getImpUid(),
			dbPayment.getMerchantUid(), null, null, dbPayment.getStatus(), dbPayment.getAmount(),
			"DB 에 %s 상태의 결제가 있는데 PG 단건 조회가 404 입니다.".formatted(dbPayment.getStatus()),
			detectedAt);
	}

	public static PaymentReconciliationMismatch amountMismatch(Long runId,
		PortOnePaymentResponse pgPayment, Payment dbPayment, LocalDateTime detectedAt) {
		return create(runId, MismatchType.AMOUNT_MISMATCH, dbPayment.getImpUid(),
			dbPayment.getMerchantUid(), statusOf(pgPayment), amountOf(pgPayment),
			dbPayment.getStatus(), dbPayment.getAmount(),
			"결제 금액이 다릅니다. PG=%s, DB=%s".formatted(amountOf(pgPayment), dbPayment.getAmount()),
			detectedAt);
	}

	public static PaymentReconciliationMismatch statusMismatch(Long runId,
		PortOnePaymentResponse pgPayment, Payment dbPayment, LocalDateTime detectedAt) {
		return create(runId, MismatchType.STATUS_MISMATCH, dbPayment.getImpUid(),
			dbPayment.getMerchantUid(), statusOf(pgPayment), amountOf(pgPayment),
			dbPayment.getStatus(), dbPayment.getAmount(),
			"결제 상태가 다릅니다. PG=%s, DB=%s".formatted(statusOf(pgPayment), dbPayment.getStatus()),
			detectedAt);
	}

	/**
	 * @param pgPayment PG 에서 찾지 못한 건은 null 이다. 이때 PG 상태는 "조회되지 않음" 으로 남긴다.
	 */
	public static PaymentReconciliationMismatch cancelRequestedStale(Long runId,
		PortOnePaymentResponse pgPayment, Payment dbPayment, LocalDateTime detectedAt) {
		String pgStatus = statusOf(pgPayment);
		return create(runId, MismatchType.CANCEL_REQUESTED_STALE, dbPayment.getImpUid(),
			dbPayment.getMerchantUid(), pgStatus, amountOf(pgPayment), dbPayment.getStatus(),
			dbPayment.getAmount(),
			"환불 도중 CANCEL_REQUESTED 로 멈춰 있습니다. PG 상태=%s"
				.formatted(pgStatus == null ? UNKNOWN_PG_STATUS : pgStatus),
			detectedAt);
	}

	public static PaymentReconciliationMismatch pgLookupFailed(Long runId, Payment dbPayment,
		String failureMessage, LocalDateTime detectedAt) {
		return create(runId, MismatchType.PG_LOOKUP_FAILED, dbPayment.getImpUid(),
			dbPayment.getMerchantUid(), null, null, dbPayment.getStatus(), dbPayment.getAmount(),
			"PG 단건 조회에 실패해 대조하지 못했습니다. 원인=%s".formatted(failureMessage), detectedAt);
	}

	private static PaymentReconciliationMismatch create(Long runId, MismatchType type,
		String impUid, String merchantUid, String pgStatus, Long pgAmount, PaymentStatus dbStatus,
		Long dbAmount, String detail, LocalDateTime detectedAt) {
		PaymentReconciliationMismatch mismatch = new PaymentReconciliationMismatch();
		mismatch.runId = runId;
		mismatch.type = type;
		mismatch.impUid = impUid;
		mismatch.merchantUid = merchantUid;
		mismatch.pgStatus = pgStatus;
		mismatch.pgAmount = pgAmount;
		mismatch.dbStatus = dbStatus;
		mismatch.dbAmount = dbAmount;
		mismatch.detail = truncate(detail);
		mismatch.detectedAt = detectedAt;
		return mismatch;
	}

	private static String statusOf(PortOnePaymentResponse pgPayment) {
		return pgPayment == null ? null : pgPayment.getStatus();
	}

	private static Long amountOf(PortOnePaymentResponse pgPayment) {
		if (pgPayment == null || pgPayment.getAmount() == null) {
			return null;
		}
		return pgPayment.getAmount().getTotal();
	}

	private static String truncate(String detail) {
		if (detail == null || detail.length() <= DETAIL_MAX_LENGTH) {
			return detail;
		}
		return detail.substring(0, DETAIL_MAX_LENGTH);
	}
}
