package com.mansereok.server.domain.payment.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.MismatchType;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대사 규칙 검증. 조회가 없는 순수 클래스라 입력을 그대로 만들어 넣는다.
 */
class PaymentReconcilerTest {

	private static final Long RUN_ID = 7L;
	private static final LocalDateTime DETECTED_AT = LocalDateTime.of(2026, 9, 24, 5, 0);
	private static final String IMP_UID = "pay_test_001";
	private static final long PRICE = 10000L;

	private final PaymentReconciler reconciler = new PaymentReconciler();

	// ===== 픽스처 =====

	private PortOnePaymentResponse pgPayment(String impUid, String status, Long total) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(impUid);
		response.setStatus(status);
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		response.setAmount(amount);
		return response;
	}

	private Payment dbPayment(String impUid, PaymentStatus status, long amount) {
		return Payment.create(impUid, "order_" + impUid, amount, status, 1L, 1L, 1L);
	}

	private List<PaymentReconciliationMismatch> reconcile(List<PortOnePaymentResponse> pgPayments,
		List<Payment> windowPayments) {
		return reconcile(pgPayments, windowPayments, List.of(), Map.of());
	}

	private List<PaymentReconciliationMismatch> reconcile(List<PortOnePaymentResponse> pgPayments,
		List<Payment> windowPayments, List<Payment> cancelRequestedPayments,
		Map<String, PgLookup> pgLookups) {
		return reconciler.reconcile(RUN_ID, pgPayments, windowPayments, cancelRequestedPayments,
			pgLookups, DETECTED_AT);
	}

	// ===== 타입별 규칙 =====

	@Test
	@DisplayName("PG 와 DB 가 같으면 불일치가 없다")
	void matchingPayment_hasNoMismatch() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "PAID", PRICE)),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)));

		assertThat(mismatches).isEmpty();
	}

	@Test
	@DisplayName("PG 에만 있는 거래는 MISSING_IN_DB 로 PG 값과 함께 기록한다")
	void pgOnlyPayment_isMissingInDb() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "PAID", PRICE)), List.of());

		assertThat(mismatches).hasSize(1);
		PaymentReconciliationMismatch mismatch = mismatches.get(0);
		assertThat(mismatch.getType()).isEqualTo(MismatchType.MISSING_IN_DB);
		assertThat(mismatch.getRunId()).isEqualTo(RUN_ID);
		assertThat(mismatch.getImpUid()).isEqualTo(IMP_UID);
		assertThat(mismatch.getPgStatus()).isEqualTo("PAID");
		assertThat(mismatch.getPgAmount()).isEqualTo(PRICE);
		assertThat(mismatch.getDbStatus()).isNull();
		assertThat(mismatch.getDetectedAt()).isEqualTo(DETECTED_AT);
	}

	@Test
	@DisplayName("DB 에만 있고 단건 조회도 404 면 MISSING_IN_PG 로 기록한다")
	void dbOnlyPaymentNotFoundInPg_isMissingInPg() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)), List.of(),
			Map.of(IMP_UID, PgLookup.notFound()));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.MISSING_IN_PG);
		assertThat(mismatches.get(0).getDbAmount()).isEqualTo(PRICE);
	}

	@Test
	@DisplayName("금액이 다르면 AMOUNT_MISMATCH 로 양쪽 금액을 기록한다")
	void differentAmount_isAmountMismatch() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "PAID", PRICE)),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, 9000L)));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.AMOUNT_MISMATCH);
		assertThat(mismatches.get(0).getPgAmount()).isEqualTo(PRICE);
		assertThat(mismatches.get(0).getDbAmount()).isEqualTo(9000L);
	}

	@Test
	@DisplayName("PG 는 취소인데 DB 는 결제완료면 STATUS_MISMATCH 로 기록한다")
	void differentStatus_isStatusMismatch() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "CANCELLED", PRICE)),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.STATUS_MISMATCH);
		assertThat(mismatches.get(0).getPgStatus()).isEqualTo("CANCELLED");
		assertThat(mismatches.get(0).getDbStatus()).isEqualTo(PaymentStatus.PAID);
	}

	@Test
	@DisplayName("PARTIAL_CANCELLED 는 CANCELLED 로 매핑돼 DB 가 CANCELLED 면 불일치가 아니다")
	void partialCancelledMapsToCancelled() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "PARTIAL_CANCELLED", PRICE)),
			List.of(dbPayment(IMP_UID, PaymentStatus.CANCELLED, PRICE)));

		assertThat(mismatches).isEmpty();
	}

	@Test
	@DisplayName("DB 가 CANCEL_REQUESTED 면 STATUS_MISMATCH 없이 CANCEL_REQUESTED_STALE 만 남긴다")
	void cancelRequested_isStaleOnly() {
		Payment stalePayment = dbPayment(IMP_UID, PaymentStatus.CANCEL_REQUESTED, PRICE);

		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "PAID", PRICE)), List.of(), List.of(stalePayment),
			Map.of());

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.CANCEL_REQUESTED_STALE);
		assertThat(mismatches.get(0).getPgStatus()).isEqualTo("PAID");
		assertThat(mismatches.get(0).getDetail()).contains("PG 상태=PAID");
	}

	@Test
	@DisplayName("DB 전용 건의 단건 조회가 실패하면 PG_LOOKUP_FAILED 로 사유와 함께 기록한다")
	void lookupFailure_isPgLookupFailed() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)), List.of(),
			Map.of(IMP_UID, PgLookup.failed("포트원 일시 장애")));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.PG_LOOKUP_FAILED);
		assertThat(mismatches.get(0).getDetail()).contains("포트원 일시 장애");
	}

	@Test
	@DisplayName("DB 전용 건도 단건 조회로 찾았으면 목록에 있는 건처럼 금액을 비교한다")
	void dbOnlyPaymentFoundByLookup_isCompared() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, 9000L)), List.of(),
			Map.of(IMP_UID, PgLookup.found(pgPayment(IMP_UID, "PAID", PRICE))));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.AMOUNT_MISMATCH);
	}

	// ===== 공통 규칙 =====

	@Test
	@DisplayName("무료 결제는 접두사로도 0원으로도 알아보고 모든 비교에서 뺀다")
	void freePayments_areExcluded() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(),
			List.of(dbPayment("free_1758600000_abcd1234", PaymentStatus.PAID, 0L),
				dbPayment("pay_zero_amount", PaymentStatus.PAID, 0L)),
			List.of(), Map.of());

		assertThat(mismatches).isEmpty();
	}

	@Test
	@DisplayName("창 조회와 CANCEL_REQUESTED 조회에 같은 결제가 걸려도 같은 타입을 두 번 남기지 않는다")
	void sameImpUidAndType_isRecordedOnce() {
		Payment stalePayment = dbPayment(IMP_UID, PaymentStatus.CANCEL_REQUESTED, PRICE);

		List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(),
			List.of(stalePayment), List.of(stalePayment),
			Map.of(IMP_UID, PgLookup.notFound()));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactlyInAnyOrder(MismatchType.MISSING_IN_PG,
				MismatchType.CANCEL_REQUESTED_STALE);
	}

	@Test
	@DisplayName("우리가 모르는 PG 상태는 상태를 비교하지 않고 금액만 비교한다")
	void unknownPgStatus_comparesAmountOnly() {
		List<PaymentReconciliationMismatch> mismatches = reconcile(
			List.of(pgPayment(IMP_UID, "SOMETHING_NEW", 9000L)),
			List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)));

		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.AMOUNT_MISMATCH);
		assertThat(mismatches.get(0).getPgStatus()).isEqualTo("SOMETHING_NEW");
	}
}
