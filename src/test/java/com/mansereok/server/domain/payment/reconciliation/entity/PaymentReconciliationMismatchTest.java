package com.mansereok.server.domain.payment.reconciliation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 대사 불일치 한 건을 만드는 타입별 정적 팩토리를 검증한다.
 *
 * <p>타입마다 채우는 칸이 다르고(PG 에만 있으면 db* 가 비고, DB 에만 있으면 pg* 가 빈다), detail 은 컬럼 길이(500자)에 맞춰
 * 엔티티가 직접 자른다. detail 이 500자를 넘으면 불일치 저장이 실패해 그날 대사 run 전체가 FAILED 로 끝나므로, 자르기는 이 엔티티가
 * 지키는 유일한 방어다.
 */
class PaymentReconciliationMismatchTest {

	private static final Long RUN_ID = 7L;
	private static final LocalDateTime DETECTED_AT = LocalDateTime.of(2026, 9, 24, 5, 0);
	private static final String IMP_UID = "pay_test_001";
	private static final String MERCHANT_UID = "order_test_001";

	private static PortOnePaymentResponse pgPayment(String id, String status, Long total) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(id);
		response.setStatus(status);
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		response.setAmount(amount);
		return response;
	}

	private static Payment dbPayment(PaymentStatus status, long amount) {
		return Payment.create(IMP_UID, MERCHANT_UID, amount, status, 1L, 1L, 1L);
	}

	@Nested
	@DisplayName("detail 길이")
	class DetailLength {

		@Test
		@DisplayName("PG 단건 조회 실패 사유가 길어 detail 이 500자를 넘으면 앞에서부터 500자로 자른다")
		void longFailureMessage_isTruncatedToColumnLength() {
			// given
			String longMessage = "x".repeat(600);

			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.pgLookupFailed(RUN_ID,
				dbPayment(PaymentStatus.PAID, 10000L), longMessage, DETECTED_AT);

			// then
			assertThat(mismatch.getDetail())
				.hasSize(PaymentReconciliationMismatch.DETAIL_MAX_LENGTH)
				.startsWith("PG 단건 조회에 실패해 대조하지 못했습니다. 원인=xxx");
		}

		@Test
		@DisplayName("detail 이 500자 이하면 자르지 않고 그대로 둔다")
		void shortFailureMessage_isKeptWhole() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.pgLookupFailed(RUN_ID,
				dbPayment(PaymentStatus.PAID, 10000L), "포트원 일시 장애", DETECTED_AT);

			// then
			assertThat(mismatch.getDetail()).isEqualTo("PG 단건 조회에 실패해 대조하지 못했습니다. 원인=포트원 일시 장애");
		}
	}

	@Nested
	@DisplayName("PG 에만 있는 거래(missingInDb)는")
	class MissingInDb {

		@Test
		@DisplayName("PG 결제 ID·상태·금액과 넘겨받은 주문 번호를 채우고 db* 칸은 비운다")
		void fillsPgSideAndMerchantUid() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.missingInDb(RUN_ID,
				pgPayment(IMP_UID, "PAID", 10000L), MERCHANT_UID, DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getRunId, PaymentReconciliationMismatch::getType,
					PaymentReconciliationMismatch::getImpUid, PaymentReconciliationMismatch::getMerchantUid,
					PaymentReconciliationMismatch::getPgStatus, PaymentReconciliationMismatch::getPgAmount,
					PaymentReconciliationMismatch::getDbStatus, PaymentReconciliationMismatch::getDbAmount,
					PaymentReconciliationMismatch::getDetectedAt)
				.containsExactly(RUN_ID, MismatchType.MISSING_IN_DB, IMP_UID, MERCHANT_UID, "PAID", 10000L,
					null, null, DETECTED_AT);
			assertThat(mismatch.getDetail()).isEqualTo("PG 에 PAID 상태의 거래가 있는데 DB 에 결제 기록이 없습니다.");
		}

		@Test
		@DisplayName("customData 에서 주문 번호를 읽지 못해 null 을 받으면 주문 번호를 비우고 그 사실을 detail 에 적는다")
		void unreadableMerchantUid_isExplainedInDetail() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.missingInDb(RUN_ID,
				pgPayment(IMP_UID, "PAID", 10000L), null, DETECTED_AT);

			// then
			assertThat(mismatch.getMerchantUid()).isNull();
			assertThat(mismatch.getDetail()).isEqualTo(
				"PG 에 PAID 상태의 거래가 있는데 DB 에 결제 기록이 없습니다. customData 에서 주문 번호를 읽지 못했습니다.");
		}
	}

	@Nested
	@DisplayName("DB 쪽 값만 있는 타입은")
	class DbSideOnly {

		@Test
		@DisplayName("missingInPg 는 pg* 칸을 비우고 DB 결제 ID·주문 번호·상태·금액을 채운다")
		void missingInPg() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.missingInPg(RUN_ID,
				dbPayment(PaymentStatus.PAID, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid,
					PaymentReconciliationMismatch::getMerchantUid, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDbAmount)
				.containsExactly(MismatchType.MISSING_IN_PG, IMP_UID, MERCHANT_UID, null, null,
					PaymentStatus.PAID, 10000L);
		}

		@Test
		@DisplayName("pgLookupFailed 는 pg* 칸을 비우고 DB 결제 ID·주문 번호·상태·금액을 채운다")
		void pgLookupFailed() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.pgLookupFailed(RUN_ID,
				dbPayment(PaymentStatus.PAID, 10000L), "포트원 일시 장애", DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid,
					PaymentReconciliationMismatch::getMerchantUid, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDbAmount)
				.containsExactly(MismatchType.PG_LOOKUP_FAILED, IMP_UID, MERCHANT_UID, null, null,
					PaymentStatus.PAID, 10000L);
		}

		@Test
		@DisplayName("pgIdMismatch 는 돌아온 PG 결제가 이 결제의 것이 아니라서 그 값을 pg* 에 두지 않고, 돌아온 결제 ID 를 detail 에 적는다")
		void pgIdMismatch() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.pgIdMismatch(RUN_ID,
				pgPayment("pay_other", "PAID", 10000L), dbPayment(PaymentStatus.PAID, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid,
					PaymentReconciliationMismatch::getMerchantUid, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDbAmount)
				.containsExactly(MismatchType.PG_ID_MISMATCH, IMP_UID, MERCHANT_UID, null, null,
					PaymentStatus.PAID, 10000L);
			assertThat(mismatch.getDetail()).isEqualTo(
				"PG 단건 조회가 다른 결제 ID(pay_other)의 거래를 돌려줘 대조하지 않았습니다. 한 PG 결제에 DB 결제가 여러 건 붙었을 수 있습니다.");
		}

		@Test
		@DisplayName("pgIdMismatch 는 돌아온 PG 결제에 ID 가 없으면 'null' 을 ID 처럼 적지 않고 응답에 결제 ID 가 없었다고 적는다")
		void pgIdMismatchWithoutReturnedId() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.pgIdMismatch(RUN_ID,
				pgPayment(null, "PAID", 10000L), dbPayment(PaymentStatus.PAID, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch.getType()).isEqualTo(MismatchType.PG_ID_MISMATCH);
			assertThat(mismatch.getDetail()).isEqualTo("PG 단건 조회 응답에 결제 ID 가 없어 대조하지 않았습니다.");
		}
	}

	@Nested
	@DisplayName("양쪽 값을 비교한 타입은 pg* 와 db* 를 모두 채운다")
	class BothSides {

		@Test
		@DisplayName("amountMismatch 는 양쪽 금액을 detail 에도 적는다")
		void amountMismatch() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.amountMismatch(RUN_ID,
				pgPayment(IMP_UID, "PAID", 10000L), dbPayment(PaymentStatus.PAID, 9000L), DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getImpUid,
					PaymentReconciliationMismatch::getMerchantUid, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDbAmount, PaymentReconciliationMismatch::getDetail)
				.containsExactly(MismatchType.AMOUNT_MISMATCH, IMP_UID, MERCHANT_UID, "PAID", 10000L,
					PaymentStatus.PAID, 9000L, "결제 금액이 다릅니다. PG=10000, DB=9000");
		}

		@Test
		@DisplayName("statusMismatch 는 양쪽 상태를 detail 에도 적는다")
		void statusMismatch() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.statusMismatch(RUN_ID,
				pgPayment(IMP_UID, "CANCELLED", 10000L), dbPayment(PaymentStatus.PAID, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDbAmount, PaymentReconciliationMismatch::getDetail)
				.containsExactly(MismatchType.STATUS_MISMATCH, "CANCELLED", 10000L, PaymentStatus.PAID, 10000L,
					"결제 상태가 다릅니다. PG=CANCELLED, DB=PAID");
		}

		@Test
		@DisplayName("PG 응답에 금액이 없으면 pgAmount 를 비운다")
		void pgPaymentWithoutAmount_leavesPgAmountEmpty() {
			// given
			PortOnePaymentResponse withoutAmount = pgPayment(IMP_UID, "CANCELLED", 10000L);
			withoutAmount.setAmount(null);

			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.statusMismatch(RUN_ID,
				withoutAmount, dbPayment(PaymentStatus.PAID, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch.getPgAmount()).isNull();
		}
	}

	@Nested
	@DisplayName("환불 도중 멈춘 결제(cancelRequestedStale)는")
	class CancelRequestedStale {

		@Test
		@DisplayName("PG 결제가 있으면 그 상태를 pgStatus 와 detail 에 적는다")
		void withPgPayment() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.cancelRequestedStale(
				RUN_ID, pgPayment(IMP_UID, "PAID", 10000L), dbPayment(PaymentStatus.CANCEL_REQUESTED, 10000L),
				DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getType, PaymentReconciliationMismatch::getPgStatus,
					PaymentReconciliationMismatch::getPgAmount, PaymentReconciliationMismatch::getDbStatus,
					PaymentReconciliationMismatch::getDetail)
				.containsExactly(MismatchType.CANCEL_REQUESTED_STALE, "PAID", 10000L,
					PaymentStatus.CANCEL_REQUESTED, "환불 도중 CANCEL_REQUESTED 로 멈춰 있습니다. PG 상태=PAID");
		}

		@Test
		@DisplayName("PG 에서 찾지 못해 null 을 받으면 pg* 칸을 비우고 detail 에 '조회되지 않음' 을 적는다")
		void withoutPgPayment() {
			// when
			PaymentReconciliationMismatch mismatch = PaymentReconciliationMismatch.cancelRequestedStale(
				RUN_ID, null, dbPayment(PaymentStatus.CANCEL_REQUESTED, 10000L), DETECTED_AT);

			// then
			assertThat(mismatch)
				.extracting(PaymentReconciliationMismatch::getPgStatus, PaymentReconciliationMismatch::getPgAmount,
					PaymentReconciliationMismatch::getDetail)
				.containsExactly(null, null, "환불 도중 CANCEL_REQUESTED 로 멈춰 있습니다. PG 상태=조회되지 않음");
		}
	}
}
