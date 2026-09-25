package com.mansereok.server.domain.payment.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationRun;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationMismatchRepository;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationRunRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.support.PaymentMySqlTest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 대사가 환불 도중에 본 CANCEL_REQUESTED 결제를, 환불이 끝날 시간만큼 기다린 뒤 실제 MySQL 에서 다시 읽는지 확인한다.
 *
 * <p>운영에서 일어나는 순서를 그대로 만든다. 대사가 CANCEL_REQUESTED 결제를 읽고, 그 뒤 다른 스레드에서 환불의 마지막 커밋
 * (CANCELLED)이 일어난다. 이 커밋은 첫 읽기가 끝나고 0.5초 뒤다. 첫 읽기 바로 뒤에 다시 읽으면 이 커밋을 보지 못해 멈춘 환불로
 * 잘못 기록한다. 그래서 이 테스트는 "첫 읽기 뒤 기다리는 시간 안에 끝난 환불은 기록하지 않는다" 를 고정한다.
 *
 * <p>재확인은 리포지토리 호출마다 영속성 컨텍스트가 새로 열린다는 데에도 기댄다. 대사의 읽기를 한 트랜잭션으로 묶으면 첫 읽기에서
 * 올라온 엔티티가 그대로 돌아와, 환불이 끝난 결제도 CANCEL_REQUESTED 로 보인다. 목 리포지토리로는 이 차이를 볼 수 없어 실제 DB 로
 * 본다.
 *
 * <p>운영에서 기다리는 시간(기본 23초)은 테스트에 너무 길다. 그래서 테스트용 생성자로 2초를 넣은 서비스를 만들고, 나머지 협력 객체는
 * 스프링 빈을 그대로 쓴다. 대사는 CANCEL_REQUESTED 결제를 날짜 창과 상관없이 모두 읽으므로, 대상 날짜는 이 결제가 만들어진 날과
 * 겹치지 않는 과거 날짜를 쓴다. 데이터는 실행마다 다른 키로 만들고, 뒤 정리에서 그 키와 이번 대사 run 으로 만든 행만 지운다.
 */
class PaymentReconciliationRecheckMySqlTest extends PaymentMySqlTest {

	private static final LocalDate TARGET_DATE = LocalDate.of(2000, 1, 1);
	private static final long PRICE = 10000L;
	private static final Duration WAIT_BEFORE_RECHECK = Duration.ofSeconds(2);
	private static final long REFUND_FINISHES_AFTER_FIRST_READ_MILLIS = 500;

	@Autowired
	private PaymentRepository paymentRepository;
	@Autowired
	private PaymentReconciliationRunRepository runRepository;
	@Autowired
	private PaymentReconciliationMismatchRepository mismatchRepository;
	@Autowired
	private PaymentReconciler reconciler;
	@Autowired
	private Clock clock;
	@Autowired
	private PlatformTransactionManager transactionManager;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runKey = UUID.randomUUID().toString().substring(0, 8);
	private final String impUid = "pay_recheck_" + runKey;

	private PaymentReconciliationService paymentReconciliationService;
	private CompletableFuture<Void> refundFinished;
	private Long reconciliationRunId;

	@BeforeEach
	void createPaymentWhoseRefundIsInProgress() {
		paymentReconciliationService = new PaymentReconciliationService(portOneClient, paymentRepository,
			runRepository, mismatchRepository, reconciler, discordNotificationService, clock,
			transactionManager, WAIT_BEFORE_RECHECK);
		paymentRepository.save(Payment.create(impUid, "order_recheck_" + runKey, PRICE,
			PaymentStatus.CANCEL_REQUESTED, null, null, null));
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM payment_reconciliation_mismatches WHERE run_id = ? OR imp_uid = ?",
			reconciliationRunId, impUid);
		jdbcTemplate.update("DELETE FROM payment_reconciliation_runs WHERE id = ?", reconciliationRunId);
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
	}

	@Test
	@DisplayName("첫 읽기 0.5초 뒤 다른 스레드에서 환불이 끝나면, 기다린 뒤 다시 읽어 그 상태를 보고 CANCEL_REQUESTED_STALE 을 남기지 않는다")
	void refundFinishedWhileWaiting_isNotRecordedAsStale() throws Exception {
		// given: 단건 조회는 첫 DB 읽기가 끝난 뒤 불린다. 여기서 환불의 마지막 커밋을 다른 스레드에 0.5초 뒤로 예약하고 바로 돌아온다.
		given(portOneClient.findPayment(impUid)).willAnswer(invocation -> {
			refundFinished = CompletableFuture.runAsync(this::commitRefundCancelled,
				CompletableFuture.delayedExecutor(REFUND_FINISHES_AFTER_FIRST_READ_MILLIS,
					TimeUnit.MILLISECONDS));
			return Optional.of(pgPayment("PAID"));
		});

		// when
		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);
		reconciliationRunId = run.getId();

		// then
		assertThat(refundFinished).as("첫 읽기에서 CANCEL_REQUESTED 로 읽혀 단건 조회가 불렸다").isNotNull();
		refundFinished.get(5, TimeUnit.SECONDS);
		assertThat(recordedMismatchTypes()).isEmpty();
		assertThat(runStatus()).isEqualTo("COMPLETED");
	}

	@Test
	@DisplayName("기다린 뒤 다시 읽어도 환불이 CANCEL_REQUESTED 에 머물러 있으면 CANCEL_REQUESTED_STALE 한 건을 남긴다")
	void refundStillInProgress_isRecordedAsStale() {
		// given
		given(portOneClient.findPayment(impUid)).willReturn(Optional.of(pgPayment("PAID")));

		// when
		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);
		reconciliationRunId = run.getId();

		// then
		assertThat(recordedMismatchTypes()).containsExactly("CANCEL_REQUESTED_STALE");
		assertThat(runStatus()).isEqualTo("COMPLETED");
	}

	/** 환불 트랜잭션 B 가 결제를 CANCELLED 로 커밋하는 것을 흉내 낸다. */
	private void commitRefundCancelled() {
		jdbcTemplate.update("UPDATE payments SET status = 'CANCELLED' WHERE imp_uid = ?", impUid);
	}

	/** 이번 run 에서 이 결제로 남은 불일치 타입. JPA 캐시를 거치지 않고 SQL 로 읽는다. */
	private List<String> recordedMismatchTypes() {
		return jdbcTemplate.queryForList(
			"SELECT type FROM payment_reconciliation_mismatches WHERE run_id = ? AND imp_uid = ?",
			String.class, reconciliationRunId, impUid);
	}

	private String runStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM payment_reconciliation_runs WHERE id = ?",
			String.class, reconciliationRunId);
	}

	private PortOnePaymentResponse pgPayment(String status) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(PRICE);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(impUid);
		response.setStatus(status);
		response.setAmount(amount);
		return response;
	}
}
