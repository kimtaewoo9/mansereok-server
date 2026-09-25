package com.mansereok.server.domain.payment.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.PaymentMySqlTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 같은 결제에 환불과 해석 시작이 겹칠 때, 결제 행을 먼저 잡은 쪽만 성공하는지 실제 MySQL 로 확인한다.
 *
 * <p>환불(PaymentRefundService)은 트랜잭션 A 에서 결제 행 → 주문 행 → 결과 행을 잠가 결과가 INPUT_REQUIRED 인지 보고
 * CANCEL_REQUESTED 를 커밋한 뒤, 포트원 취소를 부르고, 트랜잭션 B 에서 CANCELLED 로 확정하며 초기 결과를 지운다. 해석
 * 시작(PaymentEntitlementService#startInterpretation)은 결제 행을 잠근 채 PAID·소유자·상품을 확인하고 같은 트랜잭션에서
 * 결과를 PROCESSING 으로 바꾼다. 둘 다 결제 행을 먼저 잠그므로 이 행에서 줄을 선다.
 *
 * <p>순서는 sleep 이 아니라 스텁과 래치로 고정한다. 환불이 먼저인 경우는 포트원 취소 스텁 안에서 해석을 시작한다. 해석 시작이 먼저인
 * 경우는 넘긴 결과 변경 안에서 래치로 멈춰 결제 행 잠금을 쥔 채로 두고, 환불이 그 잠금을 기다리기 시작한 것을
 * performance_schema 로 확인한 뒤 풀어 준다.
 *
 * <p>결제·주문·초기 결과는 결제 확정을 거치지 않고 저장소로 바로 만든다. 결제 확정은 상품 id 에 따라 결과를 results 와
 * compatibility_results 중 한 곳에 만드는데, 테스트 상품의 id 는 실행마다 달라진다. 여기서는 늘 일반 사주 결과(results)를 쓴다.
 */
class RefundVsInterpretationOrderTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	// 결제한 상품. 해석 시작의 상품 대조에만 쓰이고 상품 행은 필요 없다.
	private static final Long PAID_PRODUCT_ID = 3L;
	private static final String REFUND_REASON = "단순 변심";

	@Autowired
	private PaymentRefundService paymentRefundService;
	@Autowired
	private PaymentEntitlementService paymentEntitlementService;
	@Autowired
	private ResultService resultService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PaymentRepository paymentRepository;
	@Autowired
	private ResultRepository resultRepository;
	@Autowired
	private EntityManagerFactory entityManagerFactory;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "refund_vs_interpret_" + runId;
	private final String merchantUid = "order_refund_vs_interpret_" + runId;
	private final String impUid = "pay_refund_vs_interpret_" + runId;

	// 결제 행 잠금을 쥔 채 멈추는 해석 시작과, 그 잠금을 기다리는 환불이 함께 돈다.
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	private final CountDownLatch interpretationHoldsPaymentLock = new CountDownLatch(1);
	private final CountDownLatch releaseInterpretation = new CountDownLatch(1);

	private Long userId;
	private Long paymentPkId;

	@BeforeEach
	void createPaidPaymentWithInitialResult() {
		userId = userRepository.save(User.create(username, "환불해석", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		Long orderId = orderRepository.save(Order.create(merchantUid, userId, PAID_PRODUCT_ID, PRICE, PRICE, null,
			null, OrderStatus.PAID, "환불해석", username + "@example.com")).getId();
		paymentPkId = paymentRepository.save(Payment.create(impUid, merchantUid, (long) PRICE, PaymentStatus.PAID,
			orderId, userId, PAID_PRODUCT_ID)).getId();
		resultRepository.save(Result.createInitial(userId, paymentPkId, "환불 해석 순서 테스트 상품"));
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() throws InterruptedException {
		// 단언이 도중에 실패해도 멈춰 둔 해석 시작을 풀어 트랜잭션을 끝낸 뒤에 지운다. 잠금이 남아 있으면 DELETE 가 기다린다.
		releaseInterpretation.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 모두 끝났다").isTrue();

		jdbcTemplate.update("DELETE FROM results WHERE payment_id = ?", paymentPkId);
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		userRepository.deleteById(userId);
	}

	@Nested
	@DisplayName("환불이 먼저 결제 행을 잡으면")
	class RefundFirst {

		@Test
		@DisplayName("환불이 CANCEL_REQUESTED 를 커밋하고 포트원 취소를 기다리는 동안 해석을 시작하면 거부되고, 환불은 CANCELLED 로 끝나 초기 결과가 지워진다")
		void interpretationDuringPortOneCancelIsRejected() {
			// given: 포트원 취소 호출 안에서, 즉 환불 A 가 커밋한 뒤 B 가 시작하기 전에 해석을 시작한다
			AtomicReference<Throwable> startDuringCancel = new AtomicReference<>();
			willAnswer(invocation -> {
				startDuringCancel.set(catchThrowable(() -> startInterpretation(resultService::updateStatusToProcessing)));
				return null;
			}).given(portOneClient).cancelPayment(impUid, REFUND_REASON);

			// when
			Throwable refundError = catchThrowable(() -> refund());

			// then
			assertThat(refundError).as("환불 예외").isNull();
			assertThat(startDuringCancel.get())
				.as("포트원 취소를 기다리는 동안의 해석 시작")
				.isInstanceOf(PaymentException.class)
				.hasMessage("유효한 결제 정보가 아닙니다.");
			assertThat(paymentStatus()).isEqualTo("CANCELLED");
			assertThat(orderStatus()).isEqualTo("CANCELLED");
			assertThat(resultRows()).as("초기 결과 행").isZero();
		}
	}

	@Nested
	@DisplayName("해석 시작이 먼저 결제 행을 잡으면")
	class InterpretationFirst {

		@Test
		@DisplayName("해석 시작이 커밋할 때까지 환불은 결제 행 잠금을 기다리고, 커밋 뒤에는 해석이 진행됐다는 이유로 거부되어 포트원 취소를 부르지 않는다")
		void refundWaitsForPaymentRowThenIsRejected() throws Exception {
			// given: 해석 시작이 결제 행을 잠그고 결과를 PROCESSING 으로 바꾼 뒤, 커밋하기 전에 멈췄다
			Future<Boolean> interpretation = executor.submit(() -> {
				startInterpretation(paymentPk -> {
					resultService.updateStatusToProcessing(paymentPk);
					interpretationHoldsPaymentLock.countDown();
					awaitRelease();
				});
				return true;
			});
			assertThat(interpretationHoldsPaymentLock.await(10, SECONDS)).as("해석 시작이 결제 행을 잠그고 멈췄다").isTrue();

			// when: 환불을 시작하고, 환불이 결제 행 잠금을 기다리기 시작하면 해석 시작을 커밋시킨다
			Future<Boolean> refund = executor.submit(() -> refund());
			await().atMost(Duration.ofSeconds(10)).until(() -> refund.isDone() || lockWaitsOnPaymentsTable() > 0);
			boolean refundWaitedForPaymentRow = !refund.isDone();
			releaseInterpretation.countDown();

			// then
			assertThat(refundWaitedForPaymentRow).as("환불이 해석 시작의 커밋 전까지 결제 행 잠금을 기다렸다").isTrue();
			Throwable interpretationError = errorOf(interpretation);
			Throwable refundError = errorOf(refund);
			assertThat(interpretationError).as("해석 시작 예외").isNull();
			assertThat(refundError)
				.as("환불 예외")
				.isInstanceOf(PaymentException.class)
				.hasMessage("이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.");
			then(portOneClient).should(never()).cancelPayment(any(), any());
			assertThat(paymentStatus()).isEqualTo("PAID");
			assertThat(orderStatus()).isEqualTo("PAID");
			assertThat(resultStatus()).isEqualTo("PROCESSING");
		}
	}

	@Nested
	@DisplayName("open-in-view 처럼 환불의 두 트랜잭션이 EntityManager 하나를 함께 쓰면")
	class RefundWithOneEntityManager {

		@Test
		@DisplayName("포트원 취소를 기다리는 사이 결과가 COMPLETED 로 커밋되면, B 는 A 에서 읽어 둔 낡은 INPUT_REQUIRED 가 아니라 DB 상태를 보고 결과를 지우지 않는다")
		void finalizeStepJudgesByDatabaseNotStaleEntity() {
			// given: 포트원 취소를 기다리는 사이 다른 경로(예: INPUT_REQUIRED 인 채 돌던 해석)가 결과를 COMPLETED 로 커밋한다
			willAnswer(invocation -> {
				jdbcTemplate.update("UPDATE results SET status = 'COMPLETED' WHERE payment_id = ?", paymentPkId);
				return null;
			}).given(portOneClient).cancelPayment(impUid, REFUND_REASON);

			// when
			Throwable refundError = refundWithOneEntityManagerLikeOpenInView();

			// then: B 가 실패해 롤백되고, 결제는 수동 확인 대상인 CANCEL_REQUESTED 로 남는다
			assertThat(refundError)
				.as("환불 예외")
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 Result 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. paymentId(PK)="
					+ paymentPkId);
			assertThat(resultStatus()).as("결과 행이 남아 있다").isEqualTo("COMPLETED");
			assertThat(paymentStatus()).isEqualTo("CANCEL_REQUESTED");
			assertThat(orderStatus()).isEqualTo("PAID");
		}

		/**
		 * OpenEntityManagerInViewInterceptor 가 요청마다 하는 일처럼, 이 스레드에 EntityManager 하나를 묶어 둔 채 환불한다. 그러면
		 * 환불의 트랜잭션 A 와 B 가 같은 EntityManager 를 쓰고, A 에서 읽은 결과 엔티티가 B 까지 남는다.
		 */
		private Throwable refundWithOneEntityManagerLikeOpenInView() {
			EntityManager entityManager = entityManagerFactory.createEntityManager();
			TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(entityManager));
			try {
				return catchThrowable(() -> refund());
			} finally {
				TransactionSynchronizationManager.unbindResource(entityManagerFactory);
				entityManager.close();
			}
		}
	}

	private void startInterpretation(Consumer<Long> markResultProcessing) {
		paymentEntitlementService.startInterpretation(paymentPkId, username, PAID_PRODUCT_ID, markResultProcessing);
	}

	private boolean refund() {
		paymentRefundService.cancel(username, impUid, REFUND_REASON);
		return true;
	}

	private void awaitRelease() {
		try {
			assertThat(releaseInterpretation.await(30, SECONDS)).as("테스트가 30초 안에 해석 시작을 풀어 준다").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("해석 시작이 풀려나기를 기다리다 중단됐다", e);
		}
	}

	/**
	 * 이 스키마의 payments 표에서 행 잠금을 기다리는 요청 수.
	 */
	private int lockWaitsOnPaymentsTable() {
		Integer waits = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
			+ "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
			+ "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'payments'", Integer.class);
		return waits == null ? 0 : waits;
	}

	private static Throwable errorOf(Future<?> future) throws Exception {
		try {
			future.get(30, SECONDS);
			return null;
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}

	private String paymentStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE imp_uid = ?", String.class, impUid);
	}

	private String orderStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private String resultStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM results WHERE payment_id = ?", String.class,
			paymentPkId);
	}

	private int resultRows() {
		Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM results WHERE payment_id = ?",
			Integer.class, paymentPkId);
		return rows == null ? 0 : rows;
	}
}
