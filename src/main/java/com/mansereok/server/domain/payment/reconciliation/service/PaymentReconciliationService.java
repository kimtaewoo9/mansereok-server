package com.mansereok.server.domain.payment.reconciliation.service;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.client.PortOneProperties;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.MismatchType;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationRun;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationMismatchRepository;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationRunRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 하루치 결제 대사. 포트원에 기록된 거래와 우리 DB 를 대조해 어긋난 건을 남기고 Discord 로 알린다.
 *
 * <p>고치지는 않는다. 자동 보정은 잘못 판단했을 때 돈이 움직여 버리므로, 대사는 사실만 기록하고 조치는 운영자가
 * 한다. 같은 날짜를 다시 대사하면 새 run 이 쌓이고 이전 run 은 감사 이력으로 남는다.
 *
 * <p>클래스 수준 {@code @Transactional} 을 쓰지 않고 {@link TransactionTemplate} 으로 경계를 명시한다. 포트원
 * 호출은 응답이 느릴 수 있어 DB 트랜잭션 안에서 하면 커넥션을 오래 쥐기 때문이다. 그래서 순서도
 * "run 시작 커밋 → (트랜잭션 밖) 포트원 조회 → 짧은 DB 읽기 → (트랜잭션 밖) 단건 조회 → 환불이 끝날 시간만큼 기다린 뒤 환불
 * 도중에만 보이는 불일치 재확인 → 결과 커밋" 이다.
 */
@Service
@Slf4j
public class PaymentReconciliationService {

	/** 대사는 KST 영업일 하루를 단위로 본다. 스케줄러도 같은 시간대로 "어제" 를 고른다. */
	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Duration WINDOW_LENGTH = Duration.ofDays(1);

	/**
	 * 포트원 취소 호출이 끝난 뒤 환불이 CANCELLED 를 커밋하기까지(트랜잭션 B) 잡아 두는 여유. 재확인 전에 기다리는 시간은 포트원 호출
	 * 한도에 이 여유를 더한 값이다.
	 */
	private static final Duration REFUND_COMMIT_MARGIN = Duration.ofSeconds(10);

	private final PortOneClient portOneClient;
	private final PaymentRepository paymentRepository;
	private final PaymentReconciliationRunRepository runRepository;
	private final PaymentReconciliationMismatchRepository mismatchRepository;
	private final PaymentReconciler reconciler;
	private final DiscordNotificationService discordNotificationService;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;
	private final Duration refundSettleWait;

	@Autowired
	public PaymentReconciliationService(
		PortOneClient portOneClient,
		PaymentRepository paymentRepository,
		PaymentReconciliationRunRepository runRepository,
		PaymentReconciliationMismatchRepository mismatchRepository,
		PaymentReconciler reconciler,
		DiscordNotificationService discordNotificationService,
		Clock clock,
		PlatformTransactionManager transactionManager,
		PortOneProperties portOneProperties
	) {
		this(portOneClient, paymentRepository, runRepository, mismatchRepository, reconciler,
			discordNotificationService, clock, transactionManager, refundSettleWaitOf(portOneProperties));
	}

	/**
	 * 테스트용 생성자. 재확인 전에 기다리는 시간을 직접 받는다. 단위 테스트는 0 을, 실제 MySQL 테스트는 몇 초를 넣는다.
	 */
	PaymentReconciliationService(
		PortOneClient portOneClient,
		PaymentRepository paymentRepository,
		PaymentReconciliationRunRepository runRepository,
		PaymentReconciliationMismatchRepository mismatchRepository,
		PaymentReconciler reconciler,
		DiscordNotificationService discordNotificationService,
		Clock clock,
		PlatformTransactionManager transactionManager,
		Duration refundSettleWait
	) {
		this.portOneClient = portOneClient;
		this.paymentRepository = paymentRepository;
		this.runRepository = runRepository;
		this.mismatchRepository = mismatchRepository;
		this.reconciler = reconciler;
		this.discordNotificationService = discordNotificationService;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 바깥 트랜잭션에 합류하면 RUNNING 커밋이 포트원 호출 전에 일어나지 않는다.
		this.transactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.refundSettleWait = refundSettleWait;
	}

	/**
	 * 환불이 CANCEL_REQUESTED 에 머물 수 있는 가장 긴 시간. 환불은 CANCEL_REQUESTED 를 커밋한 뒤 포트원 취소를 부르고(연결·응답
	 * 한도 안에서 끝나거나 실패한다), 이어서 CANCELLED 를 커밋하거나 PAID 로 되돌린다. 기본 설정이면 3초 + 10초 + 여유 10초 =
	 * 23초다. 포트원 한도를 늘리면 이 시간도 따라 늘어난다.
	 */
	static Duration refundSettleWaitOf(PortOneProperties portOneProperties) {
		return Duration.ofMillis(portOneProperties.connectTimeoutMs())
			.plusMillis(portOneProperties.readTimeoutMs())
			.plus(REFUND_COMMIT_MARGIN);
	}

	/**
	 * 대상 영업일(KST) 하루를 대사한다.
	 *
	 * @return 완료된 run
	 * @throws RuntimeException 조회·저장이 실패하면 run 을 FAILED 로 남기고 실패 알림을 보낸 뒤 그대로 던진다
	 */
	public PaymentReconciliationRun reconcile(LocalDate targetDate) {
		Instant from = targetDate.atStartOfDay(KST).toInstant();
		Instant until = from.plus(WINDOW_LENGTH);
		PaymentReconciliationRun run = startRun(targetDate, from, until);

		try {
			return reconcileWindow(run, from, until);
		} catch (RuntimeException e) {
			log.error("결제 대사 실패: targetDate={}", targetDate, e);
			markFailed(run, e);
			throw e;
		}
	}

	private PaymentReconciliationRun startRun(LocalDate targetDate, Instant from, Instant until) {
		PaymentReconciliationRun run = PaymentReconciliationRun.start(targetDate,
			toLocalDateTime(from), toLocalDateTime(until), now());
		return transactionTemplate.execute(status -> runRepository.save(run));
	}

	private PaymentReconciliationRun reconcileWindow(PaymentReconciliationRun run, Instant from,
		Instant until) {
		List<PortOnePaymentResponse> pgPayments =
			portOneClient.listPaymentsChangedBetween(from, until);
		List<Payment> dbPayments = findDbPayments(run, pgPayments);
		List<Payment> cancelRequestedPayments =
			paymentRepository.findAllByStatus(PaymentStatus.CANCEL_REQUESTED);

		Map<String, PgLookup> pgLookups =
			lookUpDbOnlyPayments(pgPayments, dbPayments, cancelRequestedPayments);
		List<PaymentReconciliationMismatch> firstReadMismatches = reconciler.reconcile(run.getId(),
			pgPayments, dbPayments, cancelRequestedPayments, pgLookups, now());
		List<PaymentReconciliationMismatch> mismatches =
			recheckMismatchesSeenDuringRefund(firstReadMismatches);

		int dbPaymentCount =
			PaymentReconciler.indexDbPayments(dbPayments, cancelRequestedPayments).size();
		saveResult(run, pgPayments.size(), dbPaymentCount, mismatches);
		log.info("결제 대사 완료: targetDate={}, pg={}건, db={}건, 불일치={}건", run.getTargetDate(),
			pgPayments.size(), dbPaymentCount, mismatches.size());

		notifyMismatches(run, mismatches);
		return run;
	}

	/**
	 * 대조할 DB 결제. 창(createdAt) 안에 만들어진 결제에, PG 목록에 잡힌 impUid 로 찾은 결제를 더한다.
	 *
	 * <p>PG 목록은 상태가 바뀐 시각 기준이라 전날 결제되고 대상일에 취소된 건이 들어 있는데, 이 건은 창 밖이라
	 * 창 조회만으로는 DB 에 없는 것처럼 보인다. 겹쳐 나온 결제는 뒤에서 impUid 로 한 번만 세진다.
	 */
	private List<Payment> findDbPayments(PaymentReconciliationRun run,
		List<PortOnePaymentResponse> pgPayments) {
		List<Payment> dbPayments = new ArrayList<>(
			paymentRepository.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
				run.getWindowFrom(), run.getWindowUntil()));

		Set<String> pgImpUids = impUidsOf(pgPayments);
		if (!pgImpUids.isEmpty()) {
			dbPayments.addAll(paymentRepository.findAllByImpUidIn(pgImpUids));
		}
		return dbPayments;
	}

	/**
	 * PG 목록에 없는 DB 결제를 하나씩 단건 조회한다. 목록은 창 안에서 상태가 바뀐 거래만 담고 있어서, 목록에
	 * 없다는 것만으로 "PG 에 없음" 이라고 단정하지 않는다. 조회 한 건이 실패해도 그 사실을 값으로 담고 대사를
	 * 계속한다.
	 */
	private Map<String, PgLookup> lookUpDbOnlyPayments(List<PortOnePaymentResponse> pgPayments,
		List<Payment> dbPayments, List<Payment> cancelRequestedPayments) {
		Set<String> pgImpUids = impUidsOf(pgPayments);

		List<Payment> candidates = new ArrayList<>(dbPayments);
		candidates.addAll(cancelRequestedPayments);

		Map<String, PgLookup> pgLookups = new HashMap<>();
		for (Payment payment : candidates) {
			String impUid = payment.getImpUid();
			if (pgImpUids.contains(impUid) || pgLookups.containsKey(impUid)
				|| payment.isFree()) {
				continue;
			}
			pgLookups.put(impUid, lookUp(impUid));
		}
		return pgLookups;
	}

	/**
	 * 환불 도중에만 잠깐 보일 수 있는 불일치를, 진행 중이던 환불이 끝날 시간만큼 기다린 뒤 다시 읽어 확인한다.
	 *
	 * <p>CANCEL_REQUESTED 는 환불이 몇 초 동안 정상적으로 거치는 상태이고, PG 목록과 DB 는 서로 다른 순간에 읽힌다. 그래서
	 * 대사가 환불 도중에 돌면 CANCEL_REQUESTED_STALE 이나 STATUS_MISMATCH(PG=PAID, DB=CANCELLED)가 보였다가 환불이 끝나면
	 * 사라진다. 첫 읽기 바로 뒤에 다시 읽으면 그 간격(수 ms~수백 ms)이 환불이 CANCEL_REQUESTED 에 머무는 시간보다 짧아 거의
	 * 걸러지지 않는다. 그래서 첫 읽기 때 진행 중이던 환불이 끝났을 만큼({@link #refundSettleWaitOf}) 기다린 뒤, 이 두 타입의 DB
	 * 결제를 impUid 로 다시 읽고 STATUS_MISMATCH 는 PG 도 단건 조회로 다시 읽는다. 무엇을 남길지는
	 * {@link PaymentReconciler#keepStillMismatched} 가 정한다. CANCEL_REQUESTED_STALE 은 DB 상태만 보므로 PG 를 다시
	 * 조회하지 않는다. 다시 읽을 불일치가 없으면 기다리지 않는다. 새벽 배치 스레드라 기다려도 사용자 요청은 늦어지지 않는다.
	 *
	 * <p>이 클래스에는 트랜잭션이 없어 리포지토리 호출마다 영속성 컨텍스트가 새로 열린다. 그래서 다시 읽으면 첫 읽기 뒤에 커밋된
	 * 상태가 보인다. 호출을 한 트랜잭션으로 묶으면 첫 읽기의 엔티티가 그대로 돌아와 재확인이 소용없어진다.
	 */
	private List<PaymentReconciliationMismatch> recheckMismatchesSeenDuringRefund(
		List<PaymentReconciliationMismatch> firstReadMismatches) {
		Set<String> dbRecheckImpUids = impUidsOfType(firstReadMismatches,
			Set.of(MismatchType.CANCEL_REQUESTED_STALE, MismatchType.STATUS_MISMATCH));
		if (dbRecheckImpUids.isEmpty()) {
			return firstReadMismatches;
		}

		waitForRefundsInProgress();
		Map<String, Payment> freshDbPayments = new HashMap<>();
		for (Payment payment : paymentRepository.findAllByImpUidIn(dbRecheckImpUids)) {
			freshDbPayments.putIfAbsent(payment.getImpUid(), payment);
		}
		Map<String, PgLookup> freshPgLookups = new HashMap<>();
		for (String impUid : impUidsOfType(firstReadMismatches,
			Set.of(MismatchType.STATUS_MISMATCH))) {
			freshPgLookups.put(impUid, lookUp(impUid));
		}

		List<PaymentReconciliationMismatch> mismatches =
			reconciler.keepStillMismatched(firstReadMismatches, freshDbPayments, freshPgLookups);
		logDroppedMismatches(firstReadMismatches, mismatches, freshDbPayments, freshPgLookups);
		return mismatches;
	}

	/**
	 * 첫 읽기 때 진행 중이던 환불이 끝날 때까지 기다린다. 중단되면 기다리지 못한 채 다시 읽으면 멈춘 환불로 잘못 알릴 수 있으므로,
	 * 인터럽트 표시를 되살리고 예외를 던져 run 을 FAILED 로 남긴다.
	 */
	private void waitForRefundsInProgress() {
		if (!refundSettleWait.isPositive()) {
			return;
		}
		try {
			Thread.sleep(refundSettleWait);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("진행 중인 환불이 끝나기를 기다리는 동안 대사가 중단되었습니다.", e);
		}
	}

	/**
	 * 재확인으로 뺀 불일치는 DB 에도 Discord 에도 남지 않는다. 잘못 뺀 경우를 운영자가 따라갈 수 있게 한 건씩 식별자와 두 번의
	 * 읽기 값을 로그로 남긴다.
	 */
	private void logDroppedMismatches(List<PaymentReconciliationMismatch> firstReadMismatches,
		List<PaymentReconciliationMismatch> keptMismatches, Map<String, Payment> freshDbPayments,
		Map<String, PgLookup> freshPgLookups) {
		for (PaymentReconciliationMismatch mismatch : firstReadMismatches) {
			if (keptMismatches.contains(mismatch)) {
				continue;
			}
			Payment freshDbPayment = freshDbPayments.get(mismatch.getImpUid());
			log.info("재확인으로 뺀 불일치: impUid={}, type={}, 첫 읽기 PG={}, DB={}, 다시 읽은 PG={}, DB={}",
				mismatch.getImpUid(), mismatch.getType(), mismatch.getPgStatus(), mismatch.getDbStatus(),
				freshPgStatusOf(freshPgLookups.get(mismatch.getImpUid())),
				freshDbPayment == null ? null : freshDbPayment.getStatus());
		}
	}

	private String freshPgStatusOf(PgLookup freshPgLookup) {
		return switch (freshPgLookup) {
			case null -> "다시 조회하지 않음";
			case PgLookup.Found found -> found.payment().getStatus();
			case PgLookup.NotFound ignored -> "조회되지 않음";
			case PgLookup.Failed ignored -> "조회 실패";
		};
	}

	private Set<String> impUidsOfType(List<PaymentReconciliationMismatch> mismatches,
		Set<MismatchType> types) {
		return mismatches.stream()
			.filter(mismatch -> types.contains(mismatch.getType()))
			.map(PaymentReconciliationMismatch::getImpUid)
			.collect(Collectors.toSet());
	}

	private Set<String> impUidsOf(List<PortOnePaymentResponse> pgPayments) {
		return pgPayments.stream()
			.map(PortOnePaymentResponse::getId)
			.collect(Collectors.toSet());
	}

	private PgLookup lookUp(String impUid) {
		try {
			return portOneClient.findPayment(impUid)
				.map(PgLookup::found)
				.orElseGet(PgLookup::notFound);
		} catch (RuntimeException e) {
			log.warn("PG 단건 조회 실패. 불일치로 기록하고 대사를 계속합니다: impUid={}", impUid, e);
			return PgLookup.failed(messageOf(e));
		}
	}

	/** 불일치 저장과 run 마감을 한 트랜잭션에 묶어, 보고한 건수와 남은 이력이 어긋나지 않게 한다. */
	private void saveResult(PaymentReconciliationRun run, int pgPaymentCount, int dbPaymentCount,
		List<PaymentReconciliationMismatch> mismatches) {
		transactionTemplate.executeWithoutResult(status -> {
			mismatchRepository.saveAll(mismatches);
			run.complete(pgPaymentCount, dbPaymentCount, mismatches.size(), now());
			runRepository.save(run);
		});
	}

	private void notifyMismatches(PaymentReconciliationRun run,
		List<PaymentReconciliationMismatch> mismatches) {
		if (mismatches.isEmpty()) {
			return;
		}
		discordNotificationService.sendPaymentReconciliationReport(run, mismatches);
	}

	/**
	 * run 을 FAILED 로 남기고 실패를 알린다. 호출자가 원래 예외를 그대로 던져야 하므로, 기록이 또 실패하면
	 * 로그만 남기고 원인을 덮지 않는다.
	 */
	private void markFailed(PaymentReconciliationRun run, RuntimeException cause) {
		// 저장이 실패해도 보고에는 실패 사유가 실려야 해서, 상태부터 바꾸고 저장한다.
		run.fail(messageOf(cause), now());
		try {
			transactionTemplate.executeWithoutResult(status -> runRepository.save(run));
		} catch (RuntimeException e) {
			log.error("대사 실패를 기록하지 못했습니다: targetDate={}", run.getTargetDate(), e);
		}
		discordNotificationService.sendPaymentReconciliationReport(run, List.of());
	}

	/** createdAt 이 JVM 시간대의 LocalDateTime 으로 기록되므로, DB 창도 같은 시간대로 옮겨야 맞는다. */
	private LocalDateTime toLocalDateTime(Instant instant) {
		return LocalDateTime.ofInstant(instant, clock.getZone());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	private String messageOf(RuntimeException e) {
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}
}
