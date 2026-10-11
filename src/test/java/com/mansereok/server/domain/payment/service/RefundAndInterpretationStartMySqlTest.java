package com.mansereok.server.domain.payment.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>초기 결과는 상품에 따라 results(일반 사주)와 compatibility_results(궁합) 중 한 곳에만 있어, 같은 경우를 두 표에서 모두
 * 돌린다({@link ResultTable}). 궁합 결제는 환불이 결과 행이 없는 results 표를 잠그지 않는지도 본다. 잠그면 다른 결제의 초기 결과
 * INSERT 가 환불이 끝날 때까지 기다린다.
 *
 * <p>결제·주문·초기 결과는 결제 확정을 거치지 않고 저장소로 바로 만든다. 결제 확정은 상품 행을 읽어 표를 고르는데, 여기서는 상품 행
 * 없이 결제·주문에 상품 id 만 적는다.
 */
class RefundAndInterpretationStartMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
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
	private CompatibilityResultRepository compatibilityResultRepository;
	@Autowired
	private EntityManagerFactory entityManagerFactory;
	@Autowired
	private PlatformTransactionManager transactionManager;

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
	void createRequester() {
		userId = userRepository.save(User.create(username, "환불해석", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() throws InterruptedException {
		// 단언이 도중에 실패해도 멈춰 둔 해석 시작을 풀어 트랜잭션을 끝낸 뒤에 지운다. 잠금이 남아 있으면 DELETE 가 기다린다.
		releaseInterpretation.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 모두 끝났다").isTrue();

		// 결과 행은 모두 이 실행의 사용자로 만든다(잠금 범위를 보는 테스트가 다음 결제 자리에 만든 행 포함).
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		userRepository.deleteById(userId);
	}

	@Nested
	@DisplayName("환불이 먼저 결제 행을 잡으면")
	class RefundFirst {

		@ParameterizedTest(name = "[{index}] 초기 결과 표 {0}")
		@EnumSource(ResultTable.class)
		@DisplayName("환불이 CANCEL_REQUESTED 를 커밋하고 포트원 취소를 기다리는 동안 해석을 시작하면 거부되고, 환불은 CANCELLED 로 끝나 초기 결과가 지워진다")
		void interpretationDuringPortOneCancelIsRejected(ResultTable table) {
			// given: 포트원 취소 호출 안에서, 즉 환불 A 가 커밋한 뒤 B 가 시작하기 전에 해석을 시작한다
			givenPaidPaymentWithInitialResult(table);
			AtomicReference<Throwable> startDuringCancel = new AtomicReference<>();
			willAnswer(invocation -> {
				startDuringCancel.set(catchThrowable(() -> startInterpretation(table, markProcessing(table))));
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
			assertThat(resultRows(table)).as("초기 결과 행").isZero();
		}
	}

	@Nested
	@DisplayName("해석 시작이 먼저 결제 행을 잡으면")
	class InterpretationFirst {

		@ParameterizedTest(name = "[{index}] 초기 결과 표 {0}")
		@EnumSource(ResultTable.class)
		@DisplayName("해석 시작이 커밋할 때까지 환불은 결제 행 잠금을 기다리고, 커밋 뒤에는 해석이 진행됐다는 이유로 거부되어 포트원 취소를 부르지 않는다")
		void refundWaitsForPaymentRowThenIsRejected(ResultTable table) throws Exception {
			// given: 해석 시작이 결제 행을 잠그고 결과를 PROCESSING 으로 바꾼 뒤, 커밋하기 전에 멈췄다
			givenPaidPaymentWithInitialResult(table);
			Future<Boolean> interpretation = executor.submit(() -> {
				startInterpretation(table, paymentPk -> {
					markProcessing(table).accept(paymentPk);
					interpretationHoldsPaymentLock.countDown();
					awaitRelease();
				});
				return true;
			});
			assertThat(interpretationHoldsPaymentLock.await(10, SECONDS)).as("해석 시작이 결제 행을 잠그고 멈췄다").isTrue();

			// when: 환불을 시작하고, 환불이 결제 행 잠금을 기다리기 시작하면 해석 시작을 커밋시킨다
			Future<Boolean> refund = executor.submit(() -> refund());
			await().atMost(Duration.ofSeconds(10)).until(() -> refund.isDone() || lockWaitsOn("payments") > 0);
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
			assertThat(resultStatus(table)).isEqualTo("PROCESSING");
		}
	}

	@Nested
	@DisplayName("open-in-view 처럼 환불의 두 트랜잭션이 EntityManager 하나를 함께 쓰면")
	class RefundWithOneEntityManager {

		@ParameterizedTest(name = "[{index}] 초기 결과 표 {0}")
		@CsvSource(textBlock = """
			# 초기 결과 표,        삭제를 거부하는 결과 이름
			RESULTS,               Result
			COMPATIBILITY_RESULTS, CompatibilityResult
			""")
		@DisplayName("포트원 취소를 기다리는 사이 결과가 COMPLETED 로 커밋되면, B 는 A 에서 읽어 둔 낡은 INPUT_REQUIRED 가 아니라 DB 상태를 보고 결과를 지우지 않는다")
		void finalizeStepJudgesByDatabaseNotStaleEntity(ResultTable table, String resultName) {
			// given: 포트원 취소를 기다리는 사이 다른 경로(예: INPUT_REQUIRED 인 채 돌던 해석)가 결과를 COMPLETED 로 커밋한다
			givenPaidPaymentWithInitialResult(table);
			willAnswer(invocation -> {
				jdbcTemplate.update("UPDATE " + table.tableName + " SET status = 'COMPLETED' WHERE payment_id = ?",
					paymentPkId);
				return null;
			}).given(portOneClient).cancelPayment(impUid, REFUND_REASON);

			// when
			Throwable refundError = refundWithOneEntityManagerLikeOpenInView();

			// then: B 가 실패해 롤백되고, 결제는 수동 확인 대상인 CANCEL_REQUESTED 로 남는다
			assertThat(refundError)
				.as("환불 예외")
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 %s 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. paymentId(PK)=%s",
					resultName, paymentPkId);
			assertThat(resultStatus(table)).as("결과 행이 남아 있다").isEqualTo("COMPLETED");
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

	@Nested
	@DisplayName("궁합 결제를 환불하는 트랜잭션이 커밋하기 전이면")
	class CompatibilityRefundLockScope {

		@Test
		@DisplayName("환불 A 가 궁합 결과의 상태를 잠가 읽은 뒤에도 다음 결제의 초기 결과 INSERT 는 results 표에서 기다리지 않는다")
		void readingStatusLeavesResultsTableUnlocked() {
			// given
			givenPaidPaymentWithInitialResult(ResultTable.COMPATIBILITY_RESULTS);

			// when
			boolean inserted = nextPaymentResultInsertedWhileRefundStepHoldsLocks(
				() -> resultService.findStatusByPaymentId(paymentPkId));

			// then
			assertThat(inserted).as("환불 단계가 커밋하기 전에 다음 결제의 초기 결과 INSERT 가 5초 안에 끝났다").isTrue();
		}

		@Test
		@DisplayName("환불 B 가 궁합 초기 결과를 지운 뒤에도 다음 결제의 초기 결과 INSERT 는 results 표에서 기다리지 않는다")
		void deletingInitialResultLeavesResultsTableUnlocked() {
			// given
			givenPaidPaymentWithInitialResult(ResultTable.COMPATIBILITY_RESULTS);

			// when
			boolean inserted = nextPaymentResultInsertedWhileRefundStepHoldsLocks(
				() -> resultService.deleteInitialResult(paymentPkId));

			// then
			assertThat(inserted).as("환불 단계가 커밋하기 전에 다음 결제의 초기 결과 INSERT 가 5초 안에 끝났다").isTrue();
		}

		/**
		 * 테스트가 연 트랜잭션에서 환불 단계(refundStep)를 부르고, 커밋하기 전에 다른 스레드에서 다음 결제의 초기 결과를 results 에
		 * 넣어 본다. 환불 단계가 건 잠금은 이 트랜잭션이 끝날 때까지 남는다. INSERT 가 5초 안에 끝났는지를 돌려준다.
		 *
		 * <p>트랜잭션은 늘 되돌린다. 되돌리면 잠금이 풀려, 기다리던 INSERT 도 끝난 뒤 뒤 정리에서 지워진다.
		 */
		private boolean nextPaymentResultInsertedWhileRefundStepHoldsLocks(Runnable refundStep) {
			return new TransactionTemplate(transactionManager).execute(status -> {
				status.setRollbackOnly();
				refundStep.run();
				Future<Result> insert = executor.submit(() -> resultRepository.save(
					Result.createInitial(userId, nextPaymentPkId(), "다음 결제의 일반 사주 상품")));
				try {
					insert.get(5, SECONDS);
					return true;
				} catch (TimeoutException e) {
					return false;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("다음 결제의 초기 결과 INSERT 를 기다리다 중단됐다", e);
				} catch (ExecutionException e) {
					throw new IllegalStateException("다음 결제의 초기 결과 INSERT 가 실패했다", e.getCause());
				}
			});
		}

		/**
		 * 다음 결제가 받을 PK 자리. results.payment_id 에는 외래 키가 없어 결제 행 없이 초기 결과만 만든다. payment_id 는 UNIQUE 라,
		 * 행이 없는 paymentPkId 를 잠가 읽거나 조건부로 지우면 REPEATABLE READ 에서 이 자리까지 간격이 잠긴다.
		 */
		private Long nextPaymentPkId() {
			return paymentPkId + 1;
		}
	}

	/**
	 * 초기 결과가 생기는 표. 결제 확정은 궁합 상품(4, 6, 7, 10, 11, 14, 15, 19)이면 compatibility_results 에, 나머지 상품이면
	 * results 에 초기 결과를 만든다.
	 */
	enum ResultTable {
		RESULTS("results", 3L, ResultService::updateStatusToProcessing),
		COMPATIBILITY_RESULTS("compatibility_results", 19L, ResultService::updateCompatibilityStatusToProcessing);

		private final String tableName;
		// 결제·주문에 적는 결제 상품. 해석 시작의 상품 대조에만 쓰이고 상품 행은 필요 없다.
		private final Long productId;
		// 해석 시작이 넘기는 결과 변경. 컨트롤러가 상품 종류에 따라 고르는 메서드와 같다.
		private final BiConsumer<ResultService, Long> markProcessing;

		ResultTable(String tableName, Long productId, BiConsumer<ResultService, Long> markProcessing) {
			this.tableName = tableName;
			this.productId = productId;
			this.markProcessing = markProcessing;
		}
	}

	/**
	 * 요청자의 결제 완료 주문·결제와 정보 입력 전(INPUT_REQUIRED) 초기 결과를 만든다. 초기 결과는 상품에 맞는 표 한 곳에만 둔다.
	 */
	private void givenPaidPaymentWithInitialResult(ResultTable table) {
		Long orderId = orderRepository.save(Order.create(merchantUid, userId, table.productId, PRICE, PRICE, null,
			null, OrderStatus.PAID, "환불해석", username + "@example.com")).getId();
		paymentPkId = paymentRepository.save(Payment.create(impUid, merchantUid, (long) PRICE, PaymentStatus.PAID,
			orderId, userId, table.productId)).getId();
		switch (table) {
			case RESULTS -> resultRepository.save(Result.createInitial(userId, paymentPkId, "일반 사주 상품"));
			case COMPATIBILITY_RESULTS -> compatibilityResultRepository.save(
				CompatibilityResult.createInitial(userId, paymentPkId, "궁합 상품"));
		}
	}

	private void startInterpretation(ResultTable table, Consumer<Long> markResultProcessing) {
		paymentEntitlementService.startInterpretation(paymentPkId, username, table.productId, markResultProcessing);
	}

	private Consumer<Long> markProcessing(ResultTable table) {
		return paymentPk -> table.markProcessing.accept(resultService, paymentPk);
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

	private String resultStatus(ResultTable table) {
		return jdbcTemplate.queryForObject("SELECT status FROM " + table.tableName + " WHERE payment_id = ?",
			String.class, paymentPkId);
	}

	private int resultRows(ResultTable table) {
		Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table.tableName + " WHERE payment_id = ?",
			Integer.class, paymentPkId);
		return rows == null ? 0 : rows;
	}
}
