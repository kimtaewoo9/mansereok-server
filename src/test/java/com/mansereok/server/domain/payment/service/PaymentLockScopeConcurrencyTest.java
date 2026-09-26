package com.mansereok.server.domain.payment.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 한 주문의 결제 확정이 잠금을 쥔 채 멈춰 있어도, 그 잠금과 상관없는 다른 주문의 확정·새 주문 만들기는 기다리지 않고 끝나는지 실제
 * MySQL 로 확인한다(잠금 범위).
 *
 * <p>결제 확정은 주문 번호로 주문 행을 잠그고(OrderRepository#findByMerchantUidWithLock), 만료 뒤 늦게 결제된 주문이면 할인 코드
 * 행도 코드로 잠근다(DiscountCodeRepository#findByCodeForUpdate). 잠그는 컬럼에 인덱스가 없으면 InnoDB 는 훑은 모든 행과 그 사이
 * 틈까지 잠가, 기능 테스트는 통과해도 사람이 몰릴 때 모든 결제와 주문 만들기가 한 줄로 늘어선다. orders.merchant_uid 는
 * uk_orders_merchant_uid, discount_codes.code 는 컬럼의 UNIQUE 가 이 인덱스다.
 *
 * <p>주문 A 의 확정을 트랜잭션 안에서, 즉 주문 행(과 늦은 결제면 할인 코드 행)을 잠근 뒤 초기 결과를 만들기 직전에 래치로 멈춰 세운다.
 * 초기 결과를 만드는 ResultService 를 스파이로 바꿔 첫 호출만 멈추고, 나머지 호출과 멈춘 호출의 이어짐은 진짜 메서드를 부른다. 멈춘
 * 동안 다른 요청을 보내 {@value #QUICK_FINISH_SECONDS}초 안에 끝나는지 본다. 잠금을 기다리게 되면 InnoDB 잠금 대기 한도(기본 50초)까지
 * 멈춰 있으므로 둘이 뚜렷이 갈린다. 멈춘 자리가 정말 잠금을 쥐고 있는지는 같은 행을 잠가 읽는 요청이 기다리는 것으로 함께 확인한다.
 *
 * <p>스파이를 두면 목 조합이 다른 MySQL 테스트와 달라져 스프링이 이 테스트용 컨텍스트를 따로 띄운다. 로컬 MySQL 커넥션을 적게 쓰도록
 * 풀을 6개로 줄인다. 한 번에 쓰는 커넥션은 멈춘 확정, 다른 요청, 잠금 대기를 세는 조회, 커밋 뒤 알림 리스너로 넷 이하다.
 *
 * <p>데이터는 실행마다 다른 키(runId)로 만들고 그 키로 만든 사용자·코드·상품의 행만 지운다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=6")
class PaymentLockScopeConcurrencyTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	private static final int CODE_DISCOUNT = 1000;
	private static final int PRICE_WITH_CODE = 9000;
	private static final long QUICK_FINISH_SECONDS = 5;

	@MockitoSpyBean
	private ResultService resultService;
	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private OrderExpirationService orderExpirationService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private DiscountCodeRepository discountCodeRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "lock_scope_" + runId;
	private final String codeHeldByA = "LOCK-SCOPE-A-" + runId;
	private final String otherCode = "LOCK-SCOPE-B-" + runId;
	private final String paymentOfA = "pay_lock_scope_a_" + runId;

	// 멈춘 주문 A 의 확정과, 그동안 보내는 다른 요청이 함께 돈다.
	private final ExecutorService executor = Executors.newFixedThreadPool(2);
	private final CountDownLatch firstHoldsLocks = new CountDownLatch(1);
	private final CountDownLatch releaseFirst = new CountDownLatch(1);
	private final AtomicBoolean firstCallPaused = new AtomicBoolean(false);

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createBuyerAndProductAndPauseFirstInitialResult() {
		userId = userRepository.save(User.create(username, "잠금범위", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("잠금 범위 테스트 상품 " + runId).price(PRICE).build()).getId();
		// 초기 결과 만들기는 결제 확정 트랜잭션 안, 주문 행(과 늦은 결제면 할인 코드 행)을 잠근 뒤에 불린다. 첫 호출만 멈춘다.
		willAnswer(invocation -> {
			if (firstCallPaused.compareAndSet(false, true)) {
				firstHoldsLocks.countDown();
				awaitRelease();
			}
			return invocation.callRealMethod();
		}).given(resultService).createInitialResult(any(), any());
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() throws InterruptedException {
		// 단언이 도중에 실패해도 멈춰 둔 확정을 풀어 트랜잭션을 끝낸 뒤에 지운다. 잠금이 남아 있으면 DELETE 가 기다린다.
		releaseFirst.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 모두 끝났다").isTrue();

		String paymentsOfThisUser = "SELECT id FROM payments WHERE user_id = ?";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfThisUser + ")", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfThisUser + ")",
			userId);
		jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM discount_codes WHERE code IN (?, ?)", codeHeldByA, otherCode);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
	}

	@Nested
	@DisplayName("주문 A 의 결제 확정이 A 의 주문 행을 잠근 채 초기 결과를 만들기 직전에 멈춘 동안")
	class WhileConfirmOfOrderAHoldsItsOrderRow {

		private final String paymentOfB = "pay_lock_scope_b_" + runId;
		private OrderCreateResponse orderA;
		private OrderCreateResponse orderB;
		private Future<OrderStatus> confirmOfA;

		/**
		 * 주문 B 는 A 의 확정을 멈추기 전에 만든다. 멈춘 뒤에 만들면, 잠금이 넓을 때 B 를 만드는 준비 단계가 시간을 재지 않은 채
		 * A 가 풀릴 때까지 기다려 버려 B 의 확정은 늘 빨리 끝난다.
		 */
		@BeforeEach
		void createOrdersThenPauseConfirmOfOrderA() throws InterruptedException {
			orderA = paymentOrderService.createOrder(username, orderRequest(null));
			orderB = paymentOrderService.createOrder(username, orderRequest(null));
			confirmOfA = startConfirmPausedBeforeInitialResult(orderA, paymentOfA, PRICE);
		}

		@Test
		@DisplayName("다른 주문 B 의 결제 확정은 A 를 기다리지 않고 5초 안에 PAID 로 끝난다")
		void confirmOfOtherOrderDoesNotWait() throws Exception {
			// given
			given(portOneClient.getPayment(paymentOfB))
				.willReturn(paidResponse(paymentOfB, orderB.getMerchantUid(), PRICE));

			// when
			Future<OrderStatus> confirmOfB = executor.submit(() -> complete(paymentOfB, orderB.getMerchantUid()));

			// then
			assertThat(resultWithinQuickFinish(confirmOfB, "주문 B 의 결제 확정")).isEqualTo(OrderStatus.PAID);
		}

		@Test
		@DisplayName("새 주문 만들기(orders INSERT)는 A 를 기다리지 않고 5초 안에 끝난다")
		void newOrderDoesNotWait() throws Exception {
			// when
			Future<OrderCreateResponse> newOrder = executor.submit(
				() -> paymentOrderService.createOrder(username, orderRequest(null)));

			// then
			assertThat(resultWithinQuickFinish(newOrder, "새 주문 만들기").getAmount()).isEqualTo(PRICE);
		}

		@Test
		@DisplayName("같은 주문 A 에 결제 완료를 다시 보내면 A 의 확정이 커밋할 때까지 주문 행 잠금을 기다렸다가 PAID 를 받는다")
		void confirmOfSameOrderWaitsForOrderRow() throws Exception {
			// when
			Future<OrderStatus> confirmAgain = executor.submit(() -> complete(paymentOfA, orderA.getMerchantUid()));
			boolean waitedForLock = waitsForLockInThisSchema(confirmAgain);
			releaseFirst.countDown();

			// then: 멈춘 자리가 A 의 주문 행 잠금을 쥐고 있었다
			assertThat(waitedForLock).as("같은 주문의 결제 완료 재요청이 A 의 커밋 전까지 잠금을 기다렸다").isTrue();
			assertThat(confirmOfA.get(30, SECONDS)).as("주문 A 의 확정").isEqualTo(OrderStatus.PAID);
			assertThat(confirmAgain.get(30, SECONDS)).as("같은 결제의 재요청").isEqualTo(OrderStatus.PAID);
		}
	}

	@Nested
	@DisplayName("할인 코드 C1 으로 만든 주문 A 가 만료된 뒤 늦게 결제되어, 그 확정이 C1 행을 잠근 채 초기 결과를 만들기 직전에 멈춘 동안")
	class WhileLatePaymentOfOrderAHoldsItsDiscountCodeRow {

		private Future<OrderStatus> confirmOfA;

		@BeforeEach
		void pauseLatePaymentOfOrderA() throws InterruptedException {
			discountCodeRepository.save(DiscountCodeFixture.fixedAmount(CODE_DISCOUNT).withoutId()
				.code(codeHeldByA).maxUses(100).build());
			discountCodeRepository.save(DiscountCodeFixture.fixedAmount(CODE_DISCOUNT).withoutId()
				.code(otherCode).maxUses(100).build());
			OrderCreateResponse orderA = paymentOrderService.createOrder(username, orderRequest(codeHeldByA));
			assertThat(orderExpirationService.expireIfStillPending(orderA.getOrderId()))
				.as("준비 단계: 주문 A 만료").isTrue();
			confirmOfA = startConfirmPausedBeforeInitialResult(orderA, paymentOfA, PRICE_WITH_CODE);
		}

		@Test
		@DisplayName("다른 할인 코드 C2 로 새 주문을 만들면 A 를 기다리지 않고 5초 안에 끝난다")
		void newOrderWithOtherCodeDoesNotWait() throws Exception {
			// when
			Future<OrderCreateResponse> newOrder = executor.submit(
				() -> paymentOrderService.createOrder(username, orderRequest(otherCode)));

			// then
			assertThat(resultWithinQuickFinish(newOrder, "C2 로 새 주문 만들기").getAmount())
				.isEqualTo(PRICE_WITH_CODE);
			assertThat(currentUses(otherCode)).as("C2 사용 횟수").isEqualTo(1);
		}

		@Test
		@DisplayName("같은 할인 코드 C1 으로 새 주문을 만들면 A 의 확정이 커밋할 때까지 C1 행 잠금을 기다렸다가 만들고, C1 사용 횟수는 결제 완료 A 와 새 주문을 더한 2 다")
		void newOrderWithSameCodeWaitsForCodeRow() throws Exception {
			// when
			Future<OrderCreateResponse> newOrder = executor.submit(
				() -> paymentOrderService.createOrder(username, orderRequest(codeHeldByA)));
			boolean waitedForLock = waitsForLockInThisSchema(newOrder);
			releaseFirst.countDown();

			// then: 멈춘 자리가 C1 행 잠금을 쥐고 있었다
			assertThat(waitedForLock).as("C1 으로 새 주문 만들기가 A 의 커밋 전까지 잠금을 기다렸다").isTrue();
			assertThat(confirmOfA.get(30, SECONDS)).as("주문 A 의 늦은 결제 확정").isEqualTo(OrderStatus.PAID);
			assertThat(newOrder.get(30, SECONDS).getAmount()).as("C1 으로 만든 새 주문의 금액").isEqualTo(PRICE_WITH_CODE);
			assertThat(currentUses(codeHeldByA)).as("C1 사용 횟수").isEqualTo(2);
		}
	}

	/**
	 * 주문 order 의 결제 확정을 다른 스레드에서 시작하고, 확정이 트랜잭션 안에서 초기 결과를 만들기 직전에 멈출 때까지 기다린다.
	 * {@link #releaseFirst} 가 열리면 확정을 이어 가고 커밋한다.
	 */
	private Future<OrderStatus> startConfirmPausedBeforeInitialResult(OrderCreateResponse order, String paymentId,
		long amount) throws InterruptedException {
		given(portOneClient.getPayment(paymentId)).willReturn(paidResponse(paymentId, order.getMerchantUid(), amount));
		Future<OrderStatus> confirm = executor.submit(() -> complete(paymentId, order.getMerchantUid()));
		assertThat(firstHoldsLocks.await(10, SECONDS)).as("주문 A 의 확정이 초기 결과를 만들기 직전에 멈췄다").isTrue();
		return confirm;
	}

	private OrderStatus complete(String paymentId, String merchantUid) {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(merchantUid);
		return paymentConfirmService.complete(username, request).getStatus();
	}

	/**
	 * 멈춘 확정과 상관없는 요청의 결과를 {@value #QUICK_FINISH_SECONDS}초까지만 기다린다. 넘기면 멈춘 확정이 쥔 잠금을 기다린 것으로
	 * 보고 실패한다.
	 */
	private static <T> T resultWithinQuickFinish(Future<T> future, String what)
		throws InterruptedException, ExecutionException {
		try {
			return future.get(QUICK_FINISH_SECONDS, SECONDS);
		} catch (TimeoutException e) {
			throw new AssertionError("[" + what + "] " + QUICK_FINISH_SECONDS
				+ "초 안에 끝나지 않았다. 멈춘 주문 A 의 확정이 쥔 잠금을 기다린 것으로 본다.", e);
		}
	}

	/**
	 * 요청이 이 스키마의 행 잠금을 기다리기 시작했는지 돌려준다. 끝나 버렸거나 10초 안에 기다리기 시작하지 않으면 false 다.
	 */
	private boolean waitsForLockInThisSchema(Future<?> request) {
		await().atMost(Duration.ofSeconds(10)).until(() -> request.isDone() || lockWaitsInThisSchema() > 0);
		return !request.isDone() && lockWaitsInThisSchema() > 0;
	}

	private int lockWaitsInThisSchema() {
		Integer waits = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
			+ "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
			+ "WHERE l.OBJECT_SCHEMA = DATABASE()", Integer.class);
		return waits == null ? 0 : waits;
	}

	private void awaitRelease() {
		try {
			assertThat(releaseFirst.await(30, SECONDS)).as("테스트가 30초 안에 멈춘 확정을 풀어 준다").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("멈춘 확정이 풀려나기를 기다리다 중단됐다", e);
		}
	}

	private OrderCreateRequest orderRequest(String discountCode) {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setDiscountCode(discountCode);
		return request;
	}

	private Integer currentUses(String code) {
		return jdbcTemplate.queryForObject("SELECT current_uses FROM discount_codes WHERE code = ?", Integer.class,
			code);
	}

	/** customData 에 주문 번호를 담아 만든 결제의 포트원 조회 응답. */
	private PortOnePaymentResponse paidResponse(String paymentId, String merchantUid, long total) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus("PAID");
		response.setAmount(amount);
		response.setCustomData("{\"merchantUid\":\"" + merchantUid + "\"}");
		return response;
	}
}
