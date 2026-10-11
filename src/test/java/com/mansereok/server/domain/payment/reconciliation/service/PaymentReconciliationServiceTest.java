package com.mansereok.server.domain.payment.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.reconciliation.entity.MismatchType;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationRun;
import com.mansereok.server.domain.payment.reconciliation.entity.ReconciliationStatus;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationMismatchRepository;
import com.mansereok.server.domain.payment.reconciliation.repository.PaymentReconciliationRunRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 대사 오케스트레이션 검증. 비교 규칙은 {@link PaymentReconcilerTest} 가 보므로 여기서는 진짜
 * {@link PaymentReconciler} 를 그대로 쓰고, 창 계산·조회 순서·run 마감·알림만 본다.
 *
 * <p>Clock 은 KST 가 아닌 UTC 로 고정한다. DB 창이 KST 로 굳어 있지 않고 JVM 시간대(clock.getZone())를
 * 따르는지 보기 위해서다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

	private static final LocalDate TARGET_DATE = LocalDate.of(2026, 9, 23);
	private static final Clock FIXED_CLOCK =
		Clock.fixed(Instant.parse("2026-09-24T05:00:00Z"), ZoneOffset.UTC);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 5, 0);
	private static final Instant WINDOW_FROM = Instant.parse("2026-09-22T15:00:00Z");
	private static final Instant WINDOW_UNTIL = Instant.parse("2026-09-23T15:00:00Z");
	private static final LocalDateTime DB_WINDOW_FROM = LocalDateTime.of(2026, 9, 22, 15, 0);
	private static final LocalDateTime DB_WINDOW_UNTIL = LocalDateTime.of(2026, 9, 23, 15, 0);
	private static final Long RUN_ID = 42L;
	private static final String IMP_UID = "pay_test_001";
	private static final long PRICE = 10000L;

	@Mock
	private PortOneClient portOneClient;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private PaymentReconciliationRunRepository runRepository;
	@Mock
	private PaymentReconciliationMismatchRepository mismatchRepository;
	@Mock
	private DiscordNotificationService discordNotificationService;
	@Mock
	private PlatformTransactionManager transactionManager;

	@Captor
	private ArgumentCaptor<List<PaymentReconciliationMismatch>> mismatchesCaptor;

	private PaymentReconciliationService paymentReconciliationService;

	@BeforeEach
	void setUp() {
		// TransactionTemplate 은 실제 인스턴스. 트랜잭션마다 새 TransactionStatus 를 돌려준다.
		given(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.willAnswer(invocation -> new SimpleTransactionStatus(true));
		// 저장한 run 에 PK 가 생긴 것처럼 보이게 한다. 불일치가 이 id 로 run 에 매달린다.
		given(runRepository.save(any(PaymentReconciliationRun.class)))
			.willAnswer(invocation -> {
				PaymentReconciliationRun run = invocation.getArgument(0);
				ReflectionTestUtils.setField(run, "id", RUN_ID);
				return run;
			});

		paymentReconciliationService = new PaymentReconciliationService(portOneClient,
			paymentRepository, runRepository, mismatchRepository, new PaymentReconciler(),
			discordNotificationService, FIXED_CLOCK, transactionManager);
	}

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

	private void givenPgPayments(PortOnePaymentResponse... pgPayments) {
		given(portOneClient.listPaymentsChangedBetween(WINDOW_FROM, WINDOW_UNTIL))
			.willReturn(List.of(pgPayments));
	}

	private void givenDbPayments(Payment... windowPayments) {
		given(paymentRepository.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
			DB_WINDOW_FROM, DB_WINDOW_UNTIL)).willReturn(List.of(windowPayments));
		given(paymentRepository.findAllByStatus(PaymentStatus.CANCEL_REQUESTED))
			.willReturn(List.of());
	}

	private List<PaymentReconciliationMismatch> savedMismatches() {
		verify(mismatchRepository).saveAll(mismatchesCaptor.capture());
		return mismatchesCaptor.getValue();
	}

	// ===== 창 계산과 창 밖 결제 =====

	@Test
	@DisplayName("대상 영업일을 KST 하루로 보고 PG 는 Instant 창, DB 는 JVM 시간대 창으로 조회한다")
	void reconcile_usesKstDayAsWindow() {
		givenPgPayments();
		givenDbPayments();

		paymentReconciliationService.reconcile(TARGET_DATE);

		verify(portOneClient).listPaymentsChangedBetween(WINDOW_FROM, WINDOW_UNTIL);
		verify(paymentRepository).findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
			DB_WINDOW_FROM, DB_WINDOW_UNTIL);
		verify(paymentRepository).findAllByStatus(PaymentStatus.CANCEL_REQUESTED);
		// PG 목록이 비어 있으면 impUid 조회는 부르지 않는다
		verify(paymentRepository, never()).findAllByImpUidIn(any());
	}

	@Test
	@DisplayName("전날 결제되고 대상일에 취소돼 창 밖 createdAt 으로만 있는 결제는 MISSING_IN_DB 를 내지 않는다")
	void reconcile_paymentCreatedBeforeWindow_isNotMissingInDb() {
		givenPgPayments(pgPayment(IMP_UID, "CANCELLED", PRICE));
		givenDbPayments(); // 창(createdAt) 조회에는 잡히지 않는다
		given(paymentRepository.findAllByImpUidIn(Set.of(IMP_UID)))
			.willReturn(List.of(dbPayment(IMP_UID, PaymentStatus.CANCELLED, PRICE)));

		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);

		assertThat(savedMismatches()).isEmpty();
		assertThat(run.getDbPaymentCount()).isEqualTo(1);
		verifyNoInteractions(discordNotificationService);
	}

	// ===== 정상 완료 =====

	@Test
	@DisplayName("불일치가 없으면 run 을 건수와 함께 COMPLETED 로 마감하고 Discord 는 부르지 않는다")
	void reconcile_noMismatch_completesWithoutNotification() {
		givenPgPayments(pgPayment(IMP_UID, "PAID", PRICE));
		givenDbPayments(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE));

		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);

		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.COMPLETED);
		assertThat(run.getTargetDate()).isEqualTo(TARGET_DATE);
		assertThat(run.getWindowFrom()).isEqualTo(DB_WINDOW_FROM);
		assertThat(run.getPgPaymentCount()).isEqualTo(1);
		assertThat(run.getDbPaymentCount()).isEqualTo(1);
		assertThat(run.getMismatchCount()).isZero();
		assertThat(run.getFinishedAt()).isEqualTo(NOW);
		assertThat(savedMismatches()).isEmpty();
		verifyNoInteractions(discordNotificationService);
	}

	@Test
	@DisplayName("불일치가 있으면 run 에 매달아 저장하고 Discord 로 보고한다")
	void reconcile_withMismatch_savesAndNotifies() {
		givenPgPayments(pgPayment(IMP_UID, "PAID", PRICE));
		givenDbPayments();

		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);

		List<PaymentReconciliationMismatch> mismatches = savedMismatches();
		assertThat(mismatches).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.MISSING_IN_DB);
		assertThat(mismatches.get(0).getRunId()).isEqualTo(RUN_ID);
		assertThat(run.getMismatchCount()).isEqualTo(1);
		verify(discordNotificationService).sendPaymentReconciliationReport(run, mismatches);
	}

	// ===== DB 에만 있는 건의 단건 조회 =====

	@Test
	@DisplayName("PG 목록에 없는 DB 결제는 단건 조회로 확인하고, 404 면 MISSING_IN_PG 로 기록한다")
	void reconcile_dbOnlyPayment_isLookedUpOneByOne() {
		givenPgPayments();
		givenDbPayments(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE));
		given(portOneClient.findPayment(IMP_UID)).willReturn(Optional.empty());

		paymentReconciliationService.reconcile(TARGET_DATE);

		verify(portOneClient).findPayment(IMP_UID);
		assertThat(savedMismatches()).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.MISSING_IN_PG);
	}

	@Test
	@DisplayName("단건 조회가 일시 장애면 PG_LOOKUP_FAILED 로 남기고 대사는 COMPLETED 로 끝낸다")
	void reconcile_lookupFailure_isRecordedAndReconciliationCompletes() {
		givenPgPayments();
		givenDbPayments(dbPayment(IMP_UID, PaymentStatus.PAID, PRICE));
		willThrow(new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다."))
			.given(portOneClient).findPayment(IMP_UID);

		PaymentReconciliationRun run = paymentReconciliationService.reconcile(TARGET_DATE);

		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.COMPLETED);
		assertThat(savedMismatches()).extracting(PaymentReconciliationMismatch::getType)
			.containsExactly(MismatchType.PG_LOOKUP_FAILED);
	}

	// ===== 실패 =====

	@Test
	@DisplayName("PG 다건 조회가 실패하면 run 을 FAILED 로 남기고 실패를 알린 뒤 예외를 다시 던진다")
	void reconcile_listFailure_marksRunFailedAndRethrows() {
		willThrow(new PortOneUnavailableException("결제 목록을 조회하는 중 일시적인 오류가 발생했습니다."))
			.given(portOneClient).listPaymentsChangedBetween(WINDOW_FROM, WINDOW_UNTIL);

		assertThatThrownBy(() -> paymentReconciliationService.reconcile(TARGET_DATE))
			.isInstanceOf(PortOneUnavailableException.class);

		ArgumentCaptor<PaymentReconciliationRun> runCaptor =
			ArgumentCaptor.forClass(PaymentReconciliationRun.class);
		verify(runRepository, times(2)).save(runCaptor.capture());
		PaymentReconciliationRun run = runCaptor.getValue();
		assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("결제 목록을 조회하는 중 일시적인 오류가 발생했습니다.");
		assertThat(run.getFinishedAt()).isEqualTo(NOW);
		verify(discordNotificationService).sendPaymentReconciliationReport(run, List.of());
		verifyNoInteractions(mismatchRepository);
	}
}
