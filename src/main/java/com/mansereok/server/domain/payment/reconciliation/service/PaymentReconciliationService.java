package com.mansereok.server.domain.payment.reconciliation.service;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
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
 * "run 시작 커밋 → (트랜잭션 밖) 포트원 조회 → 짧은 DB 읽기 → (트랜잭션 밖) 단건 조회 → 결과 커밋" 이다.
 */
@Service
@Slf4j
public class PaymentReconciliationService {

	/** 대사는 KST 영업일 하루를 단위로 본다. 스케줄러도 같은 시간대로 "어제" 를 고른다. */
	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Duration WINDOW_LENGTH = Duration.ofDays(1);

	private final PortOneClient portOneClient;
	private final PaymentRepository paymentRepository;
	private final PaymentReconciliationRunRepository runRepository;
	private final PaymentReconciliationMismatchRepository mismatchRepository;
	private final PaymentReconciler reconciler;
	private final DiscordNotificationService discordNotificationService;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public PaymentReconciliationService(
		PortOneClient portOneClient,
		PaymentRepository paymentRepository,
		PaymentReconciliationRunRepository runRepository,
		PaymentReconciliationMismatchRepository mismatchRepository,
		PaymentReconciler reconciler,
		DiscordNotificationService discordNotificationService,
		Clock clock,
		PlatformTransactionManager transactionManager
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
		List<Payment> windowPayments =
			paymentRepository.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
				run.getWindowFrom(), run.getWindowUntil());
		List<Payment> cancelRequestedPayments =
			paymentRepository.findAllByStatus(PaymentStatus.CANCEL_REQUESTED);

		Map<String, PgLookup> pgLookups =
			lookUpDbOnlyPayments(pgPayments, windowPayments, cancelRequestedPayments);
		List<PaymentReconciliationMismatch> mismatches = reconciler.reconcile(run.getId(),
			pgPayments, windowPayments, cancelRequestedPayments, pgLookups, now());

		saveResult(run, pgPayments.size(), windowPayments.size(), mismatches);
		log.info("결제 대사 완료: targetDate={}, pg={}건, db={}건, 불일치={}건", run.getTargetDate(),
			pgPayments.size(), windowPayments.size(), mismatches.size());

		notifyMismatches(run, mismatches);
		return run;
	}

	/**
	 * DB 에만 있는 결제를 하나씩 단건 조회한다. 창 밖에서 만들어졌거나 상태가 안 바뀌어 목록에 안 잡혔을 수
	 * 있어서, 목록에 없다는 것만으로 "PG 에 없음" 이라고 단정하지 않는다. 조회 한 건이 실패해도 그 사실을 값으로
	 * 담고 대사를 계속한다.
	 */
	private Map<String, PgLookup> lookUpDbOnlyPayments(List<PortOnePaymentResponse> pgPayments,
		List<Payment> windowPayments, List<Payment> cancelRequestedPayments) {
		Set<String> pgImpUids = pgPayments.stream()
			.map(PortOnePaymentResponse::getId)
			.collect(Collectors.toSet());

		List<Payment> dbPayments = new ArrayList<>(windowPayments);
		dbPayments.addAll(cancelRequestedPayments);

		Map<String, PgLookup> pgLookups = new HashMap<>();
		for (Payment payment : dbPayments) {
			String impUid = payment.getImpUid();
			if (pgImpUids.contains(impUid) || pgLookups.containsKey(impUid)
				|| PaymentReconciler.isFreePayment(payment)) {
				continue;
			}
			pgLookups.put(impUid, lookUp(impUid));
		}
		return pgLookups;
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
		try {
			transactionTemplate.executeWithoutResult(status -> {
				run.fail(messageOf(cause), now());
				runRepository.save(run);
			});
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
