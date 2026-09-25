package com.mansereok.server.domain.payment.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.dto.response.WebhookCustomData;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import com.mansereok.server.domain.payment.service.MerchantUidGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PG 목록과 DB 결제를 대조해 불일치를 만든다. 조회도 저장도 하지 않아 규칙만 읽으면 되고, 규칙 하나가 메서드
 * 하나다.
 *
 * <p>PG 쪽 기준은 impUid(포트원 paymentId)다. 양쪽에 다 있으면 금액과 상태를 보고, 한쪽에만 있으면 그 사실
 * 자체가 불일치다. 다만 PG 목록은 창 안에서 상태가 바뀐 거래만 담고 있어서, 목록에 없다고 곧바로 "PG 에 없음"
 * 은 아니다. 그래서 DB 에만 있는 건은 서비스가 미리 단건 조회한 결과({@link PgLookup})를 받아 판단한다.
 *
 * <p>단건 조회 결과도 그대로 믿지 않는다. 돌아온 PG 결제 ID 가 DB 결제 ID 와 글자까지 같을 때만 대조 상대로 쓰고, 다르면
 * PG_ID_MISMATCH 로 기록한다. DB 의 impUid 는 UNIQUE 라 PG 결제 하나에 DB 결제 둘 이상이 붙는 길은 이것뿐이다. 그래서 이
 * 검사가 그런 경우를 따로 세지 않아도, ID 가 같은 결제 하나를 뺀 나머지 DB 결제마다 한 건씩 기록한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentReconciler {

	/** 단건 조회 결과가 아예 없는 경우의 사유. 서비스가 DB 전용 건마다 결과를 채워 주므로 방어적인 기본값이다. */
	private static final String NO_LOOKUP_MESSAGE = "PG 단건 조회 결과가 없습니다.";

	/** PG 에만 있는 거래의 customData 에서 주문 번호를 읽는 데 쓴다. */
	private final ObjectMapper objectMapper;

	/**
	 * @param runId                    불일치가 속할 대사 실행 id
	 * @param pgPayments               창 안에서 상태가 바뀐 PG 거래
	 * @param dbPayments               대조 대상 DB 결제 (창 안에 만들어진 결제 + PG 목록의 impUid 로 찾은 결제)
	 * @param cancelRequestedPayments  상태가 CANCEL_REQUESTED 인 DB 결제 (창과 무관)
	 * @param pgLookups                DB 에만 있는 impUid 의 단건 조회 결과
	 */
	public List<PaymentReconciliationMismatch> reconcile(
		Long runId,
		List<PortOnePaymentResponse> pgPayments,
		List<Payment> dbPayments,
		List<Payment> cancelRequestedPayments,
		Map<String, PgLookup> pgLookups,
		LocalDateTime detectedAt
	) {
		Map<String, PortOnePaymentResponse> pgByImpUid = indexPgPayments(pgPayments);
		Map<String, Payment> dbByImpUid = indexDbPayments(dbPayments, cancelRequestedPayments);
		MismatchCollector collector = new MismatchCollector(runId, detectedAt);

		checkMissingInDb(pgByImpUid, dbByImpUid, collector);
		for (Payment dbPayment : dbByImpUid.values()) {
			PortOnePaymentResponse pgPayment =
				resolvePgPayment(dbPayment, pgByImpUid, pgLookups, collector);
			checkAmount(pgPayment, dbPayment, collector);
			checkStatus(pgPayment, dbPayment, collector);
			checkCancelRequested(pgPayment, dbPayment, collector);
		}
		return collector.toList();
	}

	/**
	 * 무료 결제는 포트원에 거래 자체가 없으므로 모든 비교에서 뺀다. 대사 서비스도 이 규칙으로 단건 조회 대상을
	 * 고른다.
	 */
	public static boolean isFreePayment(Payment payment) {
		return isFreePayment(payment.getImpUid(), payment.getAmount());
	}

	private static boolean isFreePayment(String impUid, Long amount) {
		return (impUid != null && impUid.startsWith(MerchantUidGenerator.FREE_PREFIX))
			|| (amount != null && amount == 0L);
	}

	private Map<String, PortOnePaymentResponse> indexPgPayments(
		List<PortOnePaymentResponse> pgPayments) {
		Map<String, PortOnePaymentResponse> indexed = new LinkedHashMap<>();
		for (PortOnePaymentResponse pgPayment : pgPayments) {
			if (isFreePayment(pgPayment.getId(), amountOf(pgPayment))) {
				continue;
			}
			indexed.putIfAbsent(pgPayment.getId(), pgPayment);
		}
		return indexed;
	}

	/**
	 * 대조 대상 DB 결제를 impUid 로 모은다. 무료 결제는 빼고, 여러 조회에 겹쳐 나온 결제는 한 번만 담는다.
	 * 대사 서비스가 "대조한 DB 결제 건수" 를 셀 때도 같은 규칙을 써야 해서 밖에서도 부를 수 있게 뒀다.
	 */
	public static Map<String, Payment> indexDbPayments(List<Payment> dbPayments,
		List<Payment> cancelRequestedPayments) {
		Map<String, Payment> indexed = new LinkedHashMap<>();
		for (Payment payment : dbPayments) {
			putUnlessFree(indexed, payment);
		}
		for (Payment payment : cancelRequestedPayments) {
			putUnlessFree(indexed, payment);
		}
		return indexed;
	}

	private static void putUnlessFree(Map<String, Payment> indexed, Payment payment) {
		if (isFreePayment(payment)) {
			return;
		}
		indexed.putIfAbsent(payment.getImpUid(), payment);
	}

	private void checkMissingInDb(Map<String, PortOnePaymentResponse> pgByImpUid,
		Map<String, Payment> dbByImpUid, MismatchCollector collector) {
		for (PortOnePaymentResponse pgPayment : pgByImpUid.values()) {
			if (!dbByImpUid.containsKey(pgPayment.getId())) {
				collector.addMissingInDb(pgPayment, merchantUidOf(pgPayment));
			}
		}
	}

	/**
	 * 결제 확정(PaymentVerifier)과 같은 규칙으로 customData 에서 주문 번호를 꺼낸다. 대사는 형식이 어긋나도 멈추지 않고,
	 * 주문 번호를 비운 채 그 사실을 detail 에 남긴다.
	 */
	private String merchantUidOf(PortOnePaymentResponse pgPayment) {
		return WebhookCustomData.tryParse(pgPayment.getCustomData(), objectMapper)
			.map(WebhookCustomData::merchantUid)
			.orElse(null);
	}

	/**
	 * 대조할 PG 결제를 고른다. 목록에 없으면 단건 조회 결과를 보고, 없거나 실패면 그 사실을 기록한 뒤 null 을
	 * 돌려준다. 이후 비교는 PG 값이 없으면 건너뛴다.
	 *
	 * <p>단건 조회가 다른 결제 ID 의 PG 결제를 돌려주면 이 DB 결제의 PG 값이 아니므로 금액·상태를 비교하지 않고
	 * PG_ID_MISMATCH 만 남긴다. 조회 주소가 잘려 "pay_A#1" 조회에 pay_A 가 돌아온 경우 같은 결제로 보면, PG 에는 한 건인
	 * 결제가 DB 에는 두 건으로 남아 있어도 불일치 0건이 된다.
	 */
	private PortOnePaymentResponse resolvePgPayment(Payment dbPayment,
		Map<String, PortOnePaymentResponse> pgByImpUid, Map<String, PgLookup> pgLookups,
		MismatchCollector collector) {
		PortOnePaymentResponse pgPayment = pgByImpUid.get(dbPayment.getImpUid());
		if (pgPayment != null) {
			return pgPayment;
		}

		PgLookup lookup = pgLookups.getOrDefault(dbPayment.getImpUid(),
			PgLookup.failed(NO_LOOKUP_MESSAGE));
		return switch (lookup) {
			case PgLookup.Found found when isSamePayment(found.payment(), dbPayment) -> found.payment();
			case PgLookup.Found found -> {
				collector.addPgIdMismatch(found.payment(), dbPayment);
				yield null;
			}
			case PgLookup.NotFound ignored -> {
				collector.addMissingInPg(dbPayment);
				yield null;
			}
			case PgLookup.Failed failed -> {
				collector.addPgLookupFailed(dbPayment, failed.message());
				yield null;
			}
		};
	}

	private void checkAmount(PortOnePaymentResponse pgPayment, Payment dbPayment,
		MismatchCollector collector) {
		if (pgPayment == null) {
			return;
		}

		Long pgAmount = amountOf(pgPayment);
		if (pgAmount == null || pgAmount.equals(dbPayment.getAmount())) {
			return;
		}
		collector.addAmountMismatch(pgPayment, dbPayment);
	}

	private void checkStatus(PortOnePaymentResponse pgPayment, Payment dbPayment,
		MismatchCollector collector) {
		if (pgPayment == null || !hasStatusMismatch(pgPayment, dbPayment)) {
			return;
		}
		collector.addStatusMismatch(pgPayment, dbPayment);
	}

	/**
	 * 환불 진행 중(CANCEL_REQUESTED)은 포트원에 대응하는 상태가 없어 건너뛰고, 우리가 모르는 포트원 상태도
	 * 상태 불일치로 접지 않는다(금액만 비교한다). 첫 대조에서만 쓴다. 저장 직전 재확인은 CANCEL_REQUESTED 를 건너뛰지 않는다
	 * ({@link #keepStillMismatched}).
	 */
	private static boolean hasStatusMismatch(PortOnePaymentResponse pgPayment, Payment dbPayment) {
		if (dbPayment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
			return false;
		}
		Optional<PaymentStatus> pgStatus = PaymentStatus.fromPortOneStatus(pgPayment.getStatus());
		return pgStatus.isPresent() && pgStatus.get() != dbPayment.getStatus();
	}

	private void checkCancelRequested(PortOnePaymentResponse pgPayment, Payment dbPayment,
		MismatchCollector collector) {
		if (dbPayment.getStatus() != PaymentStatus.CANCEL_REQUESTED) {
			return;
		}
		collector.addCancelRequestedStale(pgPayment, dbPayment);
	}

	/**
	 * 첫 대조에서 나온 불일치 가운데, 서비스가 저장 직전에 다시 읽은 값으로 봐도 여전히 불일치인 것만 남긴다.
	 *
	 * <p>환불은 CANCEL_REQUESTED 커밋 → 포트원 취소 → CANCELLED 커밋 순서로 몇 초 동안 진행되고, 대사는 PG 목록과 DB 를
	 * 서로 다른 순간에 읽는다. 그래서 환불 도중에 대사가 돌면 CANCEL_REQUESTED_STALE 이나 STATUS_MISMATCH 가 잠깐 보였다가
	 * 환불이 끝나면 사라진다. 이 두 타입은 다시 읽은 값으로 불일치가 사라졌는지 보고, 사라졌으면 뺀다.
	 *
	 * <ul>
	 *   <li>CANCEL_REQUESTED_STALE: 다시 읽은 DB 결제가 아직 CANCEL_REQUESTED 일 때만 남긴다.</li>
	 *   <li>STATUS_MISMATCH: 다시 조회한 PG 상태와 다시 읽은 DB 상태가 같아졌을 때만 뺀다. 우리가 모르는 PG 상태는 같아졌다고
	 *   확인하지 못한 것이라 남긴다.</li>
	 * </ul>
	 *
	 * <p>STATUS_MISMATCH 는 다시 읽은 DB 가 CANCEL_REQUESTED 여도 빼지 않는다. CANCEL_REQUESTED 는 PAID 에서만 되므로, 이때
	 * 첫 읽기는 DB=PAID 이고 PG 는 PAID 가 아니었다(예를 들어 CANCELLED). 그런데 PG 목록은 DB 보다 먼저 읽고, 환불은
	 * CANCEL_REQUESTED 커밋이 포트원 취소보다 앞선다. 그래서 첫 DB 읽기 뒤에 시작한 환불로는 그보다 먼저 본 PG=CANCELLED 를
	 * 설명할 수 없다. 포트원 취소가 시간 초과로 실패한 것처럼 보여 PAID 로 되돌렸는데 실제로는 환불된 결제에, 사용자가 환불을 다시
	 * 시도한 경우가 그렇다. 이때 빼면 진짜 불일치를 잃는다.
	 *
	 * <p>다시 읽은 값이 없으면(DB 결제를 못 찾음, PG 재조회 실패, PG 가 다른 결제 ID 를 돌려줌) 첫 판단을 그대로 둔다. 불일치가
	 * 사라졌다고 확인한 경우만 빼야 진짜 불일치를 놓치지 않는다. 다른 타입은 환불이 끝나도 사라지지 않으므로 그대로 둔다.
	 *
	 * @param freshDbPayments 다시 읽은 DB 결제 (impUid 기준)
	 * @param freshPgLookups  다시 단건 조회한 PG 결제 (impUid 기준). STATUS_MISMATCH 만 쓴다.
	 */
	public List<PaymentReconciliationMismatch> keepStillMismatched(
		List<PaymentReconciliationMismatch> firstReadMismatches, Map<String, Payment> freshDbPayments,
		Map<String, PgLookup> freshPgLookups) {
		return firstReadMismatches.stream()
			.filter(mismatch -> isStillMismatched(mismatch, freshDbPayments.get(mismatch.getImpUid()),
				freshPgLookups.get(mismatch.getImpUid())))
			.toList();
	}

	private boolean isStillMismatched(PaymentReconciliationMismatch mismatch, Payment freshDbPayment,
		PgLookup freshPgLookup) {
		if (freshDbPayment == null) {
			return true;
		}
		return switch (mismatch.getType()) {
			case CANCEL_REQUESTED_STALE -> freshDbPayment.getStatus() == PaymentStatus.CANCEL_REQUESTED;
			case STATUS_MISMATCH -> samePaymentFound(freshPgLookup, freshDbPayment)
				.flatMap(freshPgPayment -> PaymentStatus.fromPortOneStatus(freshPgPayment.getStatus()))
				.map(freshPgStatus -> freshPgStatus != freshDbPayment.getStatus())
				.orElse(true);
			default -> true;
		};
	}

	/** 단건 조회가 DB 결제와 같은 결제 ID 의 PG 결제를 찾았으면 그 결제를 돌려준다. */
	private static Optional<PortOnePaymentResponse> samePaymentFound(PgLookup lookup,
		Payment dbPayment) {
		if (lookup instanceof PgLookup.Found found && isSamePayment(found.payment(), dbPayment)) {
			return Optional.of(found.payment());
		}
		return Optional.empty();
	}

	/** PG 결제 ID 가 DB 결제 ID 와 글자까지 같은지 본다. PG 결제 ID 가 없으면 다른 결제로 본다. */
	private static boolean isSamePayment(PortOnePaymentResponse pgPayment, Payment dbPayment) {
		return pgPayment.getId() != null && pgPayment.getId().equals(dbPayment.getImpUid());
	}

	private static Long amountOf(PortOnePaymentResponse pgPayment) {
		if (pgPayment == null || pgPayment.getAmount() == null) {
			return null;
		}
		return pgPayment.getAmount().getTotal();
	}

	/**
	 * 같은 impUid 에 같은 타입을 한 번만 담는 수집기. 창 조회와 CANCEL_REQUESTED 조회에 같은 결제가 걸려도
	 * 보고가 두 줄이 되지 않게 한다.
	 */
	private static final class MismatchCollector {

		private final Long runId;
		private final LocalDateTime detectedAt;
		private final Set<String> recorded = new HashSet<>();
		private final List<PaymentReconciliationMismatch> mismatches = new ArrayList<>();

		private MismatchCollector(Long runId, LocalDateTime detectedAt) {
			this.runId = runId;
			this.detectedAt = detectedAt;
		}

		void addMissingInDb(PortOnePaymentResponse pgPayment, String merchantUid) {
			add(PaymentReconciliationMismatch.missingInDb(runId, pgPayment, merchantUid, detectedAt));
		}

		void addMissingInPg(Payment dbPayment) {
			add(PaymentReconciliationMismatch.missingInPg(runId, dbPayment, detectedAt));
		}

		void addAmountMismatch(PortOnePaymentResponse pgPayment, Payment dbPayment) {
			add(PaymentReconciliationMismatch.amountMismatch(runId, pgPayment, dbPayment,
				detectedAt));
		}

		void addStatusMismatch(PortOnePaymentResponse pgPayment, Payment dbPayment) {
			add(PaymentReconciliationMismatch.statusMismatch(runId, pgPayment, dbPayment,
				detectedAt));
		}

		void addCancelRequestedStale(PortOnePaymentResponse pgPayment, Payment dbPayment) {
			add(PaymentReconciliationMismatch.cancelRequestedStale(runId, pgPayment, dbPayment,
				detectedAt));
		}

		void addPgLookupFailed(Payment dbPayment, String failureMessage) {
			add(PaymentReconciliationMismatch.pgLookupFailed(runId, dbPayment, failureMessage,
				detectedAt));
		}

		void addPgIdMismatch(PortOnePaymentResponse pgPayment, Payment dbPayment) {
			add(PaymentReconciliationMismatch.pgIdMismatch(runId, pgPayment, dbPayment, detectedAt));
		}

		private void add(PaymentReconciliationMismatch mismatch) {
			if (recorded.add(mismatch.getImpUid() + "|" + mismatch.getType())) {
				mismatches.add(mismatch);
			}
		}

		private List<PaymentReconciliationMismatch> toList() {
			return List.copyOf(mismatches);
		}
	}
}
