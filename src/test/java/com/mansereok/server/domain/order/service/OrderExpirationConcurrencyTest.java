package com.mansereok.server.domain.order.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.service.PaymentConfirmService;
import com.mansereok.server.domain.payment.service.PaymentOrderService;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 만료가 다른 만료나 결제 완료와 겹쳐도 할인을 두 번 되돌리거나 결제된 주문을 만료로 덮어쓰지 않는지 실제 MySQL 로 확인한다.
 *
 * <p>만료(OrderExpirationService#expireIfStillPending)는 "지금 PENDING 인 주문만 EXPIRED 로" 바꾸는 조건부 UPDATE 가 1행을 바꿨을
 * 때만 할인을 되돌린다. 여러 서버의 스케줄러가 같은 주문을 동시에 만료하거나, 만료와 결제 완료가 겹치는 경우를 이 조건 하나가 막는다.
 * 어느 요청이 먼저 행을 잡을지는 매번 달라서 여러 번 되풀이한다.
 *
 * <p>만료와 결제 완료를 한 순간에 출발시키면, 결제 완료는 주문 행을 잠그기 전에 포트원 조회와 사용자 조회를 거치므로 로컬에서는
 * 거의 늘 만료가 먼저 행을 잡는다. 그래서 결제 완료가 먼저 행을 잡는 순서는 래치로 고정해 따로 본다(PaymentHoldsOrderRowFirst).
 * 조건부 UPDATE 의 "지금 PENDING 인가" 조건이 빠지면 결제된 주문을 덮어쓰는 것은 이 순서다.
 *
 * <p>스케줄러를 기다리지 않고 만료 서비스를 직접 부른다. 만료 서비스는 만든 시각을 보지 않으므로 created_at 은 옮기지 않는다. 옮기면
 * 컨텍스트가 뜰 때와 30분마다 도는 만료 스케줄러가 이 주문을 함께 집어 결과가 흔들린다.
 *
 * <p>주문 만들기·만료·결제 완료는 실제 서비스를 부르고, 밖으로 나가는 호출(포트원 조회, Discord)만 목이다. DB 에 남은 사실은 JPA
 * 캐시를 거치지 않고 SQL 로 센다. 데이터는 실행마다 다른 키(runId)로 만들고 그 키로 만든 사용자·코드·상품의 행만 지운다.
 */
class OrderExpirationConcurrencyTest extends PaymentMySqlTest {

	// 여러 서버의 스케줄러가 같은 주문을 만료하는 경우. HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int EXPIRING_THREADS = 8;
	private static final int PRICE = 10000;
	private static final int CODE_DISCOUNT = 1000;
	private static final int PRICE_WITH_CODE = 9000;

	@Autowired
	private OrderExpirationService orderExpirationService;
	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private DiscountCodeRepository discountCodeRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "expire_race_" + runId;
	private final String discountCode = "EXPIRE-RACE-" + runId;

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createBuyerProductAndCode() {
		userId = userRepository.save(User.create(username, "만료경합", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("만료 경합 테스트 상품 " + runId).price(PRICE).build()).getId();
		discountCodeRepository.save(DiscountCodeFixture.fixedAmount(CODE_DISCOUNT).withoutId()
			.code(discountCode).maxUses(100).build());
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		String paymentsOfThisUser = "SELECT id FROM payments WHERE user_id = ?";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfThisUser + ")", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfThisUser + ")",
			userId);
		jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM discount_codes WHERE code = ?", discountCode);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
	}

	@Nested
	@DisplayName("할인 코드로 만든 결제 대기 주문 두 건 중 하나를")
	class SameOrderExpiredByManyThreads {

		@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
		@DisplayName("8개 스레드가 동시에 만료하면 한 스레드만 true 를 받아 그 주문이 EXPIRED 가 되고, 사용 횟수는 한 번만 줄어 남은 결제 대기 주문 수(1)와 같다")
		void onlyOneThreadExpiresAndRestoresOnce() {
			// given: 같은 코드로 주문 두 건을 만들어 사용 횟수가 2 다. 한 건은 결제 대기로 남기고 다른 한 건을 만료한다.
			OrderCreateResponse orderStayingPending = paymentOrderService.createOrder(username, orderRequestWithCode());
			OrderCreateResponse orderToExpire = paymentOrderService.createOrder(username, orderRequestWithCode());

			// when
			List<CallResult<Boolean>> results = ConcurrentCalls.runAtTheSameTime(EXPIRING_THREADS,
				() -> orderExpirationService.expireIfStillPending(orderToExpire.getOrderId()));

			// then: 요청마다 결과를 확인한다
			String resultsPerThread = describe(results);
			assertThat(results).as("모든 스레드가 예외 없이 끝났다. %s", resultsPerThread)
				.allSatisfy(result -> assertThat(result.error()).isNull());
			assertThat(results).filteredOn(result -> Boolean.TRUE.equals(result.value()))
				.as("만료 처리했다고 답한 스레드. %s", resultsPerThread)
				.hasSize(1);

			// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다
			assertThat(orderStatus(orderToExpire.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(orderStatus(orderStayingPending.getMerchantUid())).isEqualTo("PENDING");
			assertThat(currentUses()).as("할인 코드 사용 횟수. %s", resultsPerThread).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("할인 코드로 만든 결제 대기 주문에 만료와 결제 완료가")
	class ExpiryAndPaymentAtTheSameTime {

		private final String paymentId = "pay_expire_race_" + runId;

		@RepeatedTest(value = 50, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
		@DisplayName("동시에 오면 둘 다 예외 없이 끝나고 주문은 PAID·결제 한 건·결과지 한 건으로 남으며, 사용 횟수는 결제 완료 주문 수(1)와 같다")
		void bothFinishAndOrderEndsPaidOnce() {
			// given
			OrderCreateResponse order = paymentOrderService.createOrder(username, orderRequestWithCode());
			given(portOneClient.getPayment(paymentId))
				.willReturn(paidResponse(paymentId, order.getMerchantUid(), PRICE_WITH_CODE));

			// when: 0번은 만료, 1번은 결제 완료. 만료가 먼저 행을 잡으면 결제 완료는 만료된 주문을 늦은 결제로 확정하고 할인을
			//       다시 쓴다. 결제 완료가 먼저면 만료는 PAID 를 보고 건너뛴다(이 순서는 아래 PaymentHoldsOrderRowFirst 가 고정해 본다).
			List<CallResult<Object>> results = ConcurrentCalls.runAtTheSameTime(2, index -> index == 0
				? () -> orderExpirationService.expireIfStillPending(order.getOrderId())
				: () -> paymentConfirmService.complete(username, completeRequest(paymentId, order.getMerchantUid()))
					.getStatus());

			// then: 만료는 먼저 행을 잡았으면 true, 결제 완료가 먼저였으면 false 를 받는다. 둘 다 예외 없이 끝난다.
			String resultsPerRequest = "만료=" + describe(results.get(0)) + ", 결제 완료=" + describe(results.get(1));
			assertThat(results.get(0).error()).as("만료 예외. %s", resultsPerRequest).isNull();
			assertThat(results.get(1).error()).as("결제 완료 예외. %s", resultsPerRequest).isNull();
			assertThat(results.get(1).value()).as("결제 완료가 돌려준 주문 상태. %s", resultsPerRequest)
				.isEqualTo(OrderStatus.PAID);

			// then
			assertThat(orderStatus(order.getMerchantUid())).as("주문 상태. %s", resultsPerRequest).isEqualTo("PAID");
			assertThat(paymentRows(order.getMerchantUid())).as("결제 행. %s", resultsPerRequest).isEqualTo(1);
			assertThat(initialResultRows(order.getMerchantUid())).as("결과지 행. %s", resultsPerRequest).isEqualTo(1);
			assertThat(currentUses()).as("할인 코드 사용 횟수. %s", resultsPerRequest).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("할인 코드로 만든 결제 대기 주문의 결제 완료가 주문 행을 잠그고 PAID 로 바꾼 뒤 커밋하기 전에 만료가 오면")
	class PaymentHoldsOrderRowFirst {

		private final String paymentId = "pay_expire_late_" + runId;
		// 커밋 전에 멈춘 결제 완료와 그 잠금을 기다리는 만료가 함께 돈다.
		private final ExecutorService executor = Executors.newFixedThreadPool(2);
		private final CountDownLatch paymentHoldsOrderRow = new CountDownLatch(1);
		private final CountDownLatch releasePayment = new CountDownLatch(1);

		@AfterEach
		void finishPausedPayment() throws InterruptedException {
			// 단언이 도중에 실패해도 멈춰 둔 결제 완료를 풀어 트랜잭션을 끝낸다. 바깥 클래스의 뒤 정리는 이보다 뒤에 돈다.
			releasePayment.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 모두 끝났다").isTrue();
		}

		@Test
		@DisplayName("만료는 결제 완료의 커밋을 기다렸다가 PAID 를 보고 false 를 받고, 주문은 PAID·결제 한 건으로 남으며 사용 횟수는 줄지 않는다")
		void expiryWaitsAndSkipsPaidOrder() throws Exception {
			// given: 테스트가 연 트랜잭션 안에서 결제 완료를 끝내고 커밋 직전에 멈춘다. 결제 완료의 트랜잭션은 전파가 REQUIRED 라
			//        테스트 트랜잭션에 참여하므로, 멈춘 동안 주문 행 잠금이 풀리지 않는다.
			OrderCreateResponse order = paymentOrderService.createOrder(username, orderRequestWithCode());
			given(portOneClient.getPayment(paymentId))
				.willReturn(paidResponse(paymentId, order.getMerchantUid(), PRICE_WITH_CODE));
			Future<OrderStatus> payment = executor.submit(() -> new TransactionTemplate(transactionManager)
				.execute(status -> {
					OrderStatus paid = paymentConfirmService.complete(username,
						completeRequest(paymentId, order.getMerchantUid())).getStatus();
					paymentHoldsOrderRow.countDown();
					awaitRelease();
					return paid;
				}));
			assertThat(paymentHoldsOrderRow.await(10, SECONDS)).as("결제 완료가 커밋 직전에 멈췄다").isTrue();

			// when: 만료가 주문 행 잠금을 기다리기 시작하면 결제 완료를 커밋시킨다
			Future<Boolean> expiry = executor.submit(() -> orderExpirationService.expireIfStillPending(order.getOrderId()));
			await().atMost(Duration.ofSeconds(10)).until(() -> expiry.isDone() || lockWaitsOn("orders") > 0);
			boolean expiryWaitedForOrderRow = !expiry.isDone();
			releasePayment.countDown();

			// then
			assertThat(expiryWaitedForOrderRow).as("만료가 결제 완료의 커밋 전까지 주문 행 잠금을 기다렸다").isTrue();
			assertThat(payment.get(30, SECONDS)).as("결제 완료가 돌려준 주문 상태").isEqualTo(OrderStatus.PAID);
			assertThat(expiry.get(30, SECONDS)).as("만료 처리 여부").isFalse();
			assertThat(orderStatus(order.getMerchantUid())).isEqualTo("PAID");
			assertThat(paymentRows(order.getMerchantUid())).as("결제 행").isEqualTo(1);
			assertThat(currentUses()).as("할인 코드 사용 횟수").isEqualTo(1);
		}

		private void awaitRelease() {
			try {
				assertThat(releasePayment.await(30, SECONDS)).as("테스트가 30초 안에 결제 완료를 풀어 준다").isTrue();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("결제 완료가 풀려나기를 기다리다 중단됐다", e);
			}
		}
	}

	private OrderCreateRequest orderRequestWithCode() {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setDiscountCode(discountCode);
		return request;
	}

	private PaymentCompleteRequest completeRequest(String paymentId, String merchantUid) {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(merchantUid);
		return request;
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

	private String orderStatus(String merchantUid) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private Integer currentUses() {
		return jdbcTemplate.queryForObject("SELECT current_uses FROM discount_codes WHERE code = ?",
			Integer.class, discountCode);
	}

	private int paymentRows(String merchantUid) {
		return countByMerchantUid("SELECT COUNT(*) FROM payments WHERE merchant_uid = ?", merchantUid);
	}

	/** 초기 결과는 상품에 따라 results 와 compatibility_results 중 한 곳에만 생기므로 두 표를 더한다. */
	private int initialResultRows(String merchantUid) {
		String paymentsOfThisOrder = "SELECT id FROM payments WHERE merchant_uid = ?";
		return countByMerchantUid("SELECT COUNT(*) FROM results WHERE payment_id IN (" + paymentsOfThisOrder + ")",
			merchantUid)
			+ countByMerchantUid("SELECT COUNT(*) FROM compatibility_results WHERE payment_id IN ("
			+ paymentsOfThisOrder + ")", merchantUid);
	}

	private int countByMerchantUid(String sql, String merchantUid) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, merchantUid);
		return count == null ? 0 : count;
	}

	/** 실패 메시지에 넣을 스레드별 결과. 예: "스레드별 결과 [true, false, false, ...]" */
	private static String describe(List<? extends CallResult<?>> results) {
		return results.stream().map(OrderExpirationConcurrencyTest::describe)
			.collect(joining(", ", "스레드별 결과 [", "]"));
	}

	private static String describe(CallResult<?> result) {
		return result.succeeded() ? String.valueOf(result.value())
			: result.error().getClass().getSimpleName() + "(" + result.error().getMessage() + ")";
	}
}
