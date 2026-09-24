package com.mansereok.server.domain.payment.reconciliation.service;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
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
import org.springframework.stereotype.Component;

/**
 * PG 목록과 DB 결제를 대조해 불일치를 만든다. 조회도 저장도 하지 않아 규칙만 읽으면 되고, 규칙 하나가 메서드
 * 하나다.
 *
 * <p>PG 쪽 기준은 impUid(포트원 paymentId)다. 양쪽에 다 있으면 금액과 상태를 보고, 한쪽에만 있으면 그 사실
 * 자체가 불일치다. 다만 DB 에만 있는 건은 "창 밖에서 상태가 바뀌어 목록에 안 잡힌 것"일 수 있어, 서비스가 미리
 * 단건 조회한 결과({@link PgLookup})를 받아 판단한다.
 */
@Component
public class PaymentReconciler {

	/** 단건 조회 결과가 아예 없는 경우의 사유. 서비스가 DB 전용 건마다 결과를 채워 주므로 방어적인 기본값이다. */
	private static final String NO_LOOKUP_MESSAGE = "PG 단건 조회 결과가 없습니다.";

	/**
	 * @param runId                    불일치가 속할 대사 실행 id
	 * @param pgPayments               창 안에서 상태가 바뀐 PG 거래
	 * @param windowPayments           창 안에 만들어진 DB 결제
	 * @param cancelRequestedPayments  상태가 CANCEL_REQUESTED 인 DB 결제 (창과 무관)
	 * @param pgLookups                DB 에만 있는 impUid 의 단건 조회 결과
	 */
	public List<PaymentReconciliationMismatch> reconcile(
		Long runId,
		List<PortOnePaymentResponse> pgPayments,
		List<Payment> windowPayments,
		List<Payment> cancelRequestedPayments,
		Map<String, PgLookup> pgLookups,
		LocalDateTime detectedAt
	) {
		Map<String, PortOnePaymentResponse> pgByImpUid = indexPgPayments(pgPayments);
		Map<String, Payment> dbByImpUid = indexDbPayments(windowPayments, cancelRequestedPayments);
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

	/** 창 안의 결제와 CANCEL_REQUESTED 결제는 겹칠 수 있어 impUid 로 합친다. */
	private Map<String, Payment> indexDbPayments(List<Payment> windowPayments,
		List<Payment> cancelRequestedPayments) {
		Map<String, Payment> indexed = new LinkedHashMap<>();
		for (Payment payment : windowPayments) {
			putUnlessFree(indexed, payment);
		}
		for (Payment payment : cancelRequestedPayments) {
			putUnlessFree(indexed, payment);
		}
		return indexed;
	}

	private void putUnlessFree(Map<String, Payment> indexed, Payment payment) {
		if (isFreePayment(payment)) {
			return;
		}
		indexed.putIfAbsent(payment.getImpUid(), payment);
	}

	private void checkMissingInDb(Map<String, PortOnePaymentResponse> pgByImpUid,
		Map<String, Payment> dbByImpUid, MismatchCollector collector) {
		for (PortOnePaymentResponse pgPayment : pgByImpUid.values()) {
			if (!dbByImpUid.containsKey(pgPayment.getId())) {
				collector.addMissingInDb(pgPayment);
			}
		}
	}

	/**
	 * 대조할 PG 결제를 고른다. 목록에 없으면 단건 조회 결과를 보고, 없거나 실패면 그 사실을 기록한 뒤 null 을
	 * 돌려준다. 이후 비교는 PG 값이 없으면 건너뛴다.
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
			case PgLookup.Found found -> found.payment();
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
		Long pgAmount = amountOf(pgPayment);
		if (pgAmount == null || pgAmount.equals(dbPayment.getAmount())) {
			return;
		}
		collector.addAmountMismatch(pgPayment, dbPayment);
	}

	/**
	 * 환불 진행 중(CANCEL_REQUESTED)은 포트원에 대응하는 상태가 없어 건너뛰고, 우리가 모르는 포트원 상태도
	 * 상태 불일치로 접지 않는다(금액만 비교한다).
	 */
	private void checkStatus(PortOnePaymentResponse pgPayment, Payment dbPayment,
		MismatchCollector collector) {
		if (pgPayment == null || dbPayment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
			return;
		}

		Optional<PaymentStatus> pgStatus = PaymentStatus.fromPortOneStatus(pgPayment.getStatus());
		if (pgStatus.isEmpty() || pgStatus.get() == dbPayment.getStatus()) {
			return;
		}
		collector.addStatusMismatch(pgPayment, dbPayment);
	}

	private void checkCancelRequested(PortOnePaymentResponse pgPayment, Payment dbPayment,
		MismatchCollector collector) {
		if (dbPayment.getStatus() != PaymentStatus.CANCEL_REQUESTED) {
			return;
		}
		collector.addCancelRequestedStale(pgPayment, dbPayment);
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

		void addMissingInDb(PortOnePaymentResponse pgPayment) {
			add(PaymentReconciliationMismatch.missingInDb(runId, pgPayment, detectedAt));
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
