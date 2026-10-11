package com.mansereok.server.domain.payment.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.MismatchType;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import com.mansereok.server.support.fixture.TestPayments;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 대사 규칙 검증. 조회가 없는 순수 클래스라 입력을 그대로 만들어 넣는다.
 */
class PaymentReconcilerTest {

	private static final Long RUN_ID = 7L;
	private static final LocalDateTime DETECTED_AT = LocalDateTime.of(2026, 9, 24, 5, 0);
	private static final String IMP_UID = "pay_test_001";
	private static final long PRICE = 10000L;

	// customData 파싱을 실제로 거치도록 진짜 ObjectMapper 를 쓴다.
	private final PaymentReconciler reconciler = new PaymentReconciler(new ObjectMapper());

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
		return TestPayments.payment().paymentId(impUid).merchantUid("order_" + impUid).amount(amount).inStatus(status);
	}

	private List<PaymentReconciliationMismatch> reconcile(List<PortOnePaymentResponse> pgPayments,
		List<Payment> dbPayments) {
		return reconcile(pgPayments, dbPayments, List.of(), Map.of());
	}

	private List<PaymentReconciliationMismatch> reconcile(List<PortOnePaymentResponse> pgPayments,
		List<Payment> dbPayments, List<Payment> cancelRequestedPayments,
		Map<String, PgLookup> pgLookups) {
		return reconciler.reconcile(RUN_ID, pgPayments, dbPayments, cancelRequestedPayments,
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
	@DisplayName("여러 조회에 같은 결제가 걸려도 같은 타입을 두 번 남기지 않는다")
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

	// ===== PG 단건 조회가 돌려준 결제 ID 대조 =====

	@Nested
	@DisplayName("단건 조회가 돌려준 PG 결제 ID 를 DB 결제 ID 와 대조할 때")
	class WhenComparingLookedUpPaymentId {

		private static final String VARIANT_IMP_UID = "pay_A#1";

		@Test
		@DisplayName("'pay_A#1' 조회에 pay_A 가 돌아오면 금액과 상태가 같아도 PG_ID_MISMATCH 한 건을 DB 결제 ID 로 기록한다")
		void recordsPgIdMismatchEvenWhenAmountAndStatusMatch() {
			// given
			Payment variantPayment = dbPayment(VARIANT_IMP_UID, PaymentStatus.PAID, PRICE);
			Map<String, PgLookup> lookups =
				Map.of(VARIANT_IMP_UID, PgLookup.found(pgPayment("pay_A", "PAID", PRICE)));

			// when
			List<PaymentReconciliationMismatch> mismatches =
				reconcile(List.of(), List.of(variantPayment), List.of(), lookups);

			// then
			assertThat(mismatches)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid)
				.containsExactly(tuple(MismatchType.PG_ID_MISMATCH, VARIANT_IMP_UID));
		}

		@Test
		@DisplayName("다른 ID 의 결제가 돌아오면 그 결제의 금액·상태와 비교하지 않아 AMOUNT_MISMATCH·STATUS_MISMATCH 를 만들지 않는다")
		void skipsAmountAndStatusComparison() {
			// given
			Payment variantPayment = dbPayment(VARIANT_IMP_UID, PaymentStatus.PAID, 9000L);
			Map<String, PgLookup> lookups =
				Map.of(VARIANT_IMP_UID, PgLookup.found(pgPayment("pay_A", "CANCELLED", PRICE)));

			// when
			List<PaymentReconciliationMismatch> mismatches =
				reconcile(List.of(), List.of(variantPayment), List.of(), lookups);

			// then
			assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
				.containsExactly(MismatchType.PG_ID_MISMATCH);
		}

		@Test
		@DisplayName("PG 목록의 pay_A 에 DB 결제 pay_A 와 pay_A#1 이 함께 붙으면 ID 가 다른 pay_A#1 만 한 건 기록한다")
		void twoDbPaymentsOnOnePgPayment_recordsTheExtraOne() {
			// given
			PortOnePaymentResponse pgPaymentA = pgPayment("pay_A", "PAID", PRICE);
			List<Payment> dbPayments = List.of(dbPayment("pay_A", PaymentStatus.PAID, PRICE),
				dbPayment(VARIANT_IMP_UID, PaymentStatus.PAID, PRICE));
			Map<String, PgLookup> lookups = Map.of(VARIANT_IMP_UID, PgLookup.found(pgPaymentA));

			// when
			List<PaymentReconciliationMismatch> mismatches =
				reconcile(List.of(pgPaymentA), dbPayments, List.of(), lookups);

			// then
			assertThat(mismatches)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid)
				.containsExactly(tuple(MismatchType.PG_ID_MISMATCH, VARIANT_IMP_UID));
		}

		@ParameterizedTest(name = "[{index}] 돌아온 결제 ID={0}")
		@NullSource
		@ValueSource(strings = {"pay_A", "PAY_A#1", "pay_A#1 "})
		@DisplayName("돌아온 결제 ID 가 없거나 글자 하나라도 다르면 다른 결제로 본다")
		void anyDifferenceInReturnedId_isPgIdMismatch(String returnedId) {
			// given
			Payment variantPayment = dbPayment(VARIANT_IMP_UID, PaymentStatus.PAID, PRICE);
			Map<String, PgLookup> lookups =
				Map.of(VARIANT_IMP_UID, PgLookup.found(pgPayment(returnedId, "PAID", PRICE)));

			// when
			List<PaymentReconciliationMismatch> mismatches =
				reconcile(List.of(), List.of(variantPayment), List.of(), lookups);

			// then
			assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
				.containsExactly(MismatchType.PG_ID_MISMATCH);
		}

		@Test
		@DisplayName("돌아온 결제 ID 가 DB 결제 ID 와 같고 금액·상태도 같으면 불일치가 없다")
		void sameIdAmountAndStatus_hasNoMismatch() {
			// given
			Payment payment = dbPayment(VARIANT_IMP_UID, PaymentStatus.PAID, PRICE);
			Map<String, PgLookup> lookups =
				Map.of(VARIANT_IMP_UID, PgLookup.found(pgPayment(VARIANT_IMP_UID, "PAID", PRICE)));

			// when
			List<PaymentReconciliationMismatch> mismatches =
				reconcile(List.of(), List.of(payment), List.of(), lookups);

			// then
			assertThat(mismatches).isEmpty();
		}
	}

	// ===== PG 에만 있는 거래의 주문 번호 =====

	@Nested
	@DisplayName("PG 에만 있는 거래를 MISSING_IN_DB 로 기록할 때")
	class WhenRecordingMissingInDb {

		@Test
		@DisplayName("주문 번호를 PG 결제의 customData 에서 꺼내 채운다")
		void takesMerchantUidFromCustomData() {
			// given
			PortOnePaymentResponse pgOnly = pgPayment(IMP_UID, "PAID", PRICE);
			pgOnly.setCustomData("{\"merchantUid\":\"order_1\",\"subCategoryId\":3}");

			// when
			List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(pgOnly), List.of());

			// then
			assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType,
					PaymentReconciliationMismatch::getMerchantUid)
				.containsExactly(tuple(MismatchType.MISSING_IN_DB, "order_1"));
		}

		@ParameterizedTest(name = "[{index}] customData={0}")
		@NullSource
		@ValueSource(strings = {"not-json", "{}", "{\"merchant_uid\":\"order_1\"}", "{\"merchantUid\":\" \"}"})
		@DisplayName("customData 가 없거나 형식이 어긋나면 주문 번호를 비우고 그 사실을 detail 에 적는다")
		void unreadableCustomData_leavesMerchantUidEmptyWithReason(String customData) {
			// given
			PortOnePaymentResponse pgOnly = pgPayment(IMP_UID, "PAID", PRICE);
			pgOnly.setCustomData(customData);

			// when
			List<PaymentReconciliationMismatch> mismatches = reconcile(List.of(pgOnly), List.of());

			// then
			assertThat(mismatches).singleElement().satisfies(mismatch -> {
				assertThat(mismatch.getType()).isEqualTo(MismatchType.MISSING_IN_DB);
				assertThat(mismatch.getMerchantUid()).isNull();
				assertThat(mismatch.getDetail()).endsWith("customData 에서 주문 번호를 읽지 못했습니다.");
			});
		}
	}

	// ===== 저장 직전 재확인 =====

	@Nested
	@DisplayName("다시 읽은 값으로 첫 대조의 불일치를 확인하면")
	class WhenRecheckingWithFreshValues {

		@ParameterizedTest(name = "[{index}] 다시 읽은 PG={0}, DB={1} → 남는 건수={2}")
		@CsvSource(textBlock = """
			# 양쪽이 같아졌을 때만 뺀다
			CANCELLED,        CANCELLED,        0
			CANCELLED,        PAID,             1
			# 첫 읽기 뒤에 시작한 환불은 먼저 본 PG=CANCELLED 를 설명하지 못하므로 남긴다
			CANCELLED,        CANCEL_REQUESTED, 1
			# 우리가 모르는 PG 상태는 같아졌다고 확인하지 못한 것이다
			SOMETHING_NEW,    CANCELLED,        1
			""")
		@DisplayName("STATUS_MISMATCH 는 다시 조회한 PG 상태와 다시 읽은 DB 상태가 같아졌을 때만 빼고, DB 가 CANCEL_REQUESTED 가 됐어도 남긴다")
		void statusMismatch_isDroppedOnlyWhenBothSidesAgree(String freshPgStatus,
			PaymentStatus freshDbStatus, int keptCount) {
			// given: 첫 읽기는 PG=CANCELLED, DB=PAID
			List<PaymentReconciliationMismatch> firstRead = reconcile(
				List.of(pgPayment(IMP_UID, "CANCELLED", PRICE)),
				List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)));
			assertThat(firstRead).as("첫 대조 결과").extracting(PaymentReconciliationMismatch::getType)
				.containsExactly(MismatchType.STATUS_MISMATCH);
			Map<String, Payment> freshDb = Map.of(IMP_UID, dbPayment(IMP_UID, freshDbStatus, PRICE));
			Map<String, PgLookup> freshPg =
				Map.of(IMP_UID, PgLookup.found(pgPayment(IMP_UID, freshPgStatus, PRICE)));

			// when
			List<PaymentReconciliationMismatch> keptMismatches =
				reconciler.keepStillMismatched(firstRead, freshDb, freshPg);

			// then
			assertThat(keptMismatches).hasSize(keptCount);
		}

		@Test
		@DisplayName("STATUS_MISMATCH 인데 다시 조회한 PG 결제 ID 가 다르면 확인하지 못한 것이라 첫 판단대로 남긴다")
		void statusMismatchWithAnotherIdOnSecondLookup_isKept() {
			// given
			List<PaymentReconciliationMismatch> firstRead = reconcile(
				List.of(pgPayment(IMP_UID, "CANCELLED", PRICE)),
				List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)));
			Map<String, Payment> freshDb =
				Map.of(IMP_UID, dbPayment(IMP_UID, PaymentStatus.PAID, PRICE));
			Map<String, PgLookup> freshPg =
				Map.of(IMP_UID, PgLookup.found(pgPayment("pay_other", "PAID", PRICE)));

			// when
			List<PaymentReconciliationMismatch> kept =
				reconciler.keepStillMismatched(firstRead, freshDb, freshPg);

			// then
			assertThat(kept).isEqualTo(firstRead);
		}

		@Test
		@DisplayName("다시 읽은 DB 결제가 없으면 확인하지 못한 것이라 첫 판단대로 남긴다")
		void missingFreshDbPayment_isKept() {
			// given
			List<PaymentReconciliationMismatch> firstRead = reconcile(List.of(), List.of(),
				List.of(dbPayment(IMP_UID, PaymentStatus.CANCEL_REQUESTED, PRICE)),
				Map.of(IMP_UID, PgLookup.found(pgPayment(IMP_UID, "PAID", PRICE))));

			// when
			List<PaymentReconciliationMismatch> kept =
				reconciler.keepStillMismatched(firstRead, Map.of(), Map.of());

			// then
			assertThat(kept).extracting(PaymentReconciliationMismatch::getType)
				.containsExactly(MismatchType.CANCEL_REQUESTED_STALE);
		}

		@Test
		@DisplayName("환불이 끝나도 사라지지 않는 타입(MISSING_IN_PG)은 다시 읽은 값과 상관없이 그대로 둔다")
		void otherTypes_areKeptAsIs() {
			// given
			List<PaymentReconciliationMismatch> firstRead = reconcile(List.of(),
				List.of(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE)), List.of(),
				Map.of(IMP_UID, PgLookup.notFound()));
			Map<String, Payment> freshDb =
				Map.of(IMP_UID, dbPayment(IMP_UID, PaymentStatus.CANCELLED, PRICE));

			// when
			List<PaymentReconciliationMismatch> kept =
				reconciler.keepStillMismatched(firstRead, freshDb, Map.of());

			// then
			assertThat(kept).extracting(PaymentReconciliationMismatch::getType)
				.containsExactly(MismatchType.MISSING_IN_PG);
		}
	}
}
