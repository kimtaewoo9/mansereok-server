package com.mansereok.server.domain.payment.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 쿠폰·할인 코드를 되돌리거나 다시 쓰는 작업이 커밋되기 전에 다른 작업이 겹칠 때, 교착 없이 모두 끝나고 무엇이 남는지 실제 MySQL 로
 * 확인한다.
 *
 * <ul>
 *   <li>만료 뒤 늦게 결제된 주문 A 의 확정과, 같은 쿠폰 X 를 쥔 주문 B 의 만료·환불이 겹치는 경우. A 의 확정이 X 를 다시 쓰지 못한 채
 *   커밋하기 전에 B 가 X 를 풀면, 결제 완료된 A 가 쥔 X 가 미사용으로 남을 수 있다. 그때는 A 의 확정이 결제 이상 알림을 한 번
 *   보낸다(OrderDiscountRestorer#restore 설명).</li>
 *   <li>쿠폰이 서로 다른 두 쿠폰 주문의 환불과 환불, 환불과 만료가 겹치는 경우. 쿠폰을 쥔 다른 주문이 있는지 잠그지 않고 읽으므로
 *   서로를 기다리지 않는다.</li>
 *   <li>할인 코드 D 를 잠근 주문 생성과 D 로 만든 주문 A 의 늦은 결제가 겹치는 경우.</li>
 * </ul>
 *
 * <p>순서는 sleep 이 아니라 래치로 고정한다. 먼저 시작하는 작업은 테스트가 연 트랜잭션 안에서 서비스를 부르고, 커밋하기 전(또는 잠금
 * 하나를 잡은 직후)에 래치에서 멈춘다. 서비스의 트랜잭션은 전파가 REQUIRED 라 테스트가 연 트랜잭션에 참여하므로, 멈춘 동안 서비스가
 * 건 잠금이 풀리지 않고 남는다. 환불처럼 자기 트랜잭션(REQUIRES_NEW)을 따로 여는 작업은, 그 작업이 잠글 행 하나를 테스트가 먼저
 * 잠가 멈춰 세운다. 나중 작업이 이 스키마에서 잠금을 기다리기 시작했거나 이미 끝났는지는 performance_schema 로 확인한 뒤 먼저 시작한
 * 작업을 풀어 준다.
 *
 * <p>orders.merchant_uid 에 인덱스가 있어야 이 겹침이 운영 DB 에서처럼 일어난다. 인덱스가 없으면 결제 확정의 주문 잠금 조회가 orders 를
 * 전부 잠가 두 작업이 차례로 선다. 엔티티에 인덱스 선언이 없어 ddl-auto 로 만든 테스트 DB 에는 인덱스가 없으므로, 없을 때는 테스트마다
 * 운영 DB(schema.sql)처럼 UNIQUE 인덱스를 만들고 테스트가 끝나면 지운다. 테스트가 도중에 죽어 인덱스가 남으면
 * {@value #MERCHANT_UID_INDEX_FOR_THIS_TEST} 를 손으로 지운다.
 */
class LatePaidDiscountOverlapMySqlTest extends PaymentMySqlTest {

	private static final String MERCHANT_UID_INDEX_FOR_THIS_TEST = "uk_orders_merchant_uid_overlap_test";
	private static final int PRICE = 10000;
	private static final int COUPON_DISCOUNT = 3000;
	private static final int PRICE_WITH_COUPON = 7000;
	private static final int CODE_DISCOUNT = 1000;
	private static final int PRICE_WITH_CODE = 9000;
	private static final String COUPON_TAKEN_ALERT = "만료 뒤 결제된 주문의 쿠폰을 다른 주문이 이미 쓰고 있습니다. 결제는 확정했습니다.";

	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private OrderExpirationService orderExpirationService;
	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private PaymentRefundService paymentRefundService;
	@Autowired
	private DiscountCodeService discountCodeService;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private DiscountCodeRepository discountCodeRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "late_overlap_" + runId;
	private final String discountCode = "OVERLAP-" + runId;
	private final String paymentA = "pay_overlap_a_" + runId;

	// 먼저 시작해 멈추는 작업, 나중 작업, 그리고 환불을 멈춰 세울 때 행을 잠가 두는 테스트 트랜잭션까지 셋이 함께 돈다.
	private final ExecutorService executor = Executors.newFixedThreadPool(3);
	private final CountDownLatch firstHoldsLocks = new CountDownLatch(1);
	private final CountDownLatch releaseFirst = new CountDownLatch(1);

	private Long userId;
	private Long subCategoryId;
	private boolean createdMerchantUidIndex;

	@BeforeEach
	void createBuyerAndProduct() {
		userId = userRepository.save(User.create(username, "겹침", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("늦은 결제 겹침 테스트 상품 " + runId).price(PRICE).build()).getId();
	}

	@BeforeEach
	void indexMerchantUidLikeProduction() {
		Integer merchantUidIndexes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
			+ "WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'merchant_uid' "
			+ "AND seq_in_index = 1", Integer.class);
		if (merchantUidIndexes != null && merchantUidIndexes == 0) {
			jdbcTemplate.execute("CREATE UNIQUE INDEX " + MERCHANT_UID_INDEX_FOR_THIS_TEST + " ON orders (merchant_uid)");
			createdMerchantUidIndex = true;
		}
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() throws InterruptedException {
		// 단언이 도중에 실패해도 멈춰 둔 작업을 풀어 트랜잭션을 끝낸 뒤에 지운다. 잠금이 남아 있으면 DELETE 가 기다린다.
		releaseFirst.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 모두 끝났다").isTrue();

		String paymentsOfThisUser = "SELECT id FROM payments WHERE user_id = ?";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfThisUser + ")", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfThisUser + ")",
			userId);
		jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM coupons WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM discount_codes WHERE code = ?", discountCode);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
		if (createdMerchantUidIndex) {
			jdbcTemplate.execute("DROP INDEX " + MERCHANT_UID_INDEX_FOR_THIS_TEST + " ON orders");
		}
	}

	@Nested
	@DisplayName("쿠폰 X 로 만든 주문 A 가 만료되고 X 로 주문 B 를 만든 뒤")
	class CouponTakenByAnotherOrder {

		private Long couponId;
		private OrderCreateResponse orderA;
		private OrderCreateResponse orderB;

		@BeforeEach
		void expireOrderAThenCreateOrderBWithSameCoupon() {
			couponId = couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
				.userId(userId).name("겹침 쿠폰 " + runId).build()).getId();
			orderA = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
			assertThat(orderExpirationService.expireIfStillPending(orderA.getOrderId()))
				.as("준비 단계: 주문 A 만료").isTrue();
			orderB = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
			assertThat(couponIsUsed(couponId)).as("준비 단계: B 가 X 를 썼다").isTrue();
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_COUPON));
		}

		@Test
		@DisplayName("A 의 늦은 결제 확정이 X 를 다시 쓰지 못한 채 커밋하기 전에 B 를 만료하면, 교착 없이 둘 다 끝나고 A 의 확정이 결제 이상 알림을 한 번 보낸다")
		void expiryOfBDuringUncommittedLatePaymentEndsWithOneAnomalyAlert() throws Exception {
			// given: A 의 늦은 결제 확정이 X 를 다시 쓰지 못한 채 끝났지만 아직 커밋하지 않았다
			Future<OrderStatus> latePaymentOfA = runInTransactionAndHoldBeforeCommit(() ->
				paymentConfirmService.complete(username, completeRequest(paymentA, orderA.getMerchantUid())).getStatus());
			assertThat(firstHoldsLocks.await(10, SECONDS)).as("A 의 확정이 커밋 직전에 멈췄다").isTrue();

			// when: B 를 만료하고, B 가 잠금을 기다리기 시작하면 A 를 커밋시킨다
			Future<Boolean> expiryOfB = executor.submit(
				() -> orderExpirationService.expireIfStillPending(orderB.getOrderId()));
			awaitWaitingForLockOrDone(expiryOfB);
			releaseFirst.countDown();

			// then: B 의 만료는 커밋 전인 A 를 EXPIRED 로 읽어 X 를 풀 수 있다(OrderDiscountRestorer#restore 의 남은 한계).
			//       그래서 X 의 상태 대신, 운영자가 X 를 확인하도록 A 의 확정이 보낸 알림을 본다.
			Outcome<OrderStatus> latePayment = outcomeOf(latePaymentOfA);
			Outcome<Boolean> expiry = outcomeOf(expiryOfB);
			assertThat(latePayment.error()).as("A 의 늦은 결제 확정 예외").isNull();
			assertThat(expiry.error()).as("B 의 만료 예외").isNull();
			assertThat(latePayment.value()).isEqualTo(OrderStatus.PAID);
			assertThat(expiry.value()).as("B 만료 처리 여부").isTrue();
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("PAID");
			assertThat(orderStatus(orderB.getMerchantUid())).isEqualTo("EXPIRED");
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				then(discordNotificationService).should(times(1))
					.sendPaymentAnomalyNotification(COUPON_TAKEN_ALERT, couponTakenAlertDetailsOfA()));
		}

		@Test
		@DisplayName("B 가 결제를 마친 뒤 A 의 늦은 결제 확정이 X 를 다시 쓰지 못한 채 커밋하기 전에 B 를 환불하면, 교착 없이 둘 다 끝나고 A 의 확정이 결제 이상 알림을 한 번 보낸다")
		void refundOfBDuringUncommittedLatePaymentEndsWithOneAnomalyAlert() throws Exception {
			// given: B 가 결제를 마쳤고, A 의 늦은 결제 확정이 X 를 다시 쓰지 못한 채 끝났지만 아직 커밋하지 않았다
			String paymentB = "pay_overlap_b_" + runId;
			given(portOneClient.getPayment(paymentB))
				.willReturn(paidResponse(paymentB, orderB.getMerchantUid(), PRICE_WITH_COUPON));
			paymentConfirmService.complete(username, completeRequest(paymentB, orderB.getMerchantUid()));
			Future<OrderStatus> latePaymentOfA = runInTransactionAndHoldBeforeCommit(() ->
				paymentConfirmService.complete(username, completeRequest(paymentA, orderA.getMerchantUid())).getStatus());
			assertThat(firstHoldsLocks.await(10, SECONDS)).as("A 의 확정이 커밋 직전에 멈췄다").isTrue();

			// when: B 를 환불하고, 환불이 잠금을 기다리기 시작하면 A 를 커밋시킨다
			Future<Boolean> refundOfB = executor.submit(() -> refund(paymentB));
			awaitWaitingForLockOrDone(refundOfB);
			releaseFirst.countDown();

			// then: 환불의 쿠폰 복구는 커밋 전인 A 를 EXPIRED 로 읽어 X 를 풀 수 있다(OrderDiscountRestorer#restore 의 남은 한계).
			//       그래서 X 의 상태 대신, 운영자가 X 를 확인하도록 A 의 확정이 보낸 알림을 본다.
			Outcome<OrderStatus> latePayment = outcomeOf(latePaymentOfA);
			Outcome<Boolean> refund = outcomeOf(refundOfB);
			assertThat(latePayment.error()).as("A 의 늦은 결제 확정 예외").isNull();
			assertThat(refund.error()).as("B 의 환불 예외").isNull();
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("PAID");
			assertThat(orderStatus(orderB.getMerchantUid())).isEqualTo("CANCELLED");
			assertThat(paymentStatus(paymentB)).isEqualTo("CANCELLED");
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				then(discordNotificationService).should(times(1))
					.sendPaymentAnomalyNotification(COUPON_TAKEN_ALERT, couponTakenAlertDetailsOfA()));
		}

		@Test
		@DisplayName("A 의 늦은 결제 확정이 주문 행만 잠그고 X 를 다시 쓰기 전에 B 를 만료해도, 교착 없이 둘 다 끝나고 X 는 사용 상태로 남는다")
		void expiryOfBAndLatePaymentOfAFinishWithoutDeadlock() throws Exception {
			// given: A 의 늦은 결제 확정이 주문 행을 잠갔고, 아직 쿠폰 행은 잠그지 않았다
			Future<OrderStatus> latePaymentOfA = lockOrderRowThenCompleteAfterRelease(orderA.getMerchantUid(), () ->
				paymentConfirmService.complete(username, completeRequest(paymentA, orderA.getMerchantUid())).getStatus());
			assertThat(firstHoldsLocks.await(10, SECONDS)).as("A 의 확정이 주문 행을 잠그고 멈췄다").isTrue();

			// when: B 를 만료하고, B 가 잠금을 기다리기 시작하면 A 의 확정을 이어 간다
			Future<Boolean> expiryOfB = executor.submit(
				() -> orderExpirationService.expireIfStillPending(orderB.getOrderId()));
			awaitWaitingForLockOrDone(expiryOfB);
			releaseFirst.countDown();

			// then
			Outcome<OrderStatus> latePayment = outcomeOf(latePaymentOfA);
			Outcome<Boolean> expiry = outcomeOf(expiryOfB);
			assertThat(latePayment.error()).as("A 의 늦은 결제 확정 예외").isNull();
			assertThat(expiry.error()).as("B 의 만료 예외").isNull();
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("PAID");
			assertThat(orderStatus(orderB.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(couponIsUsed(couponId)).as("결제 완료된 A 가 쥔 쿠폰 X 사용 여부").isTrue();
		}

		/** A 의 늦은 결제 확정이 B 가 쥔 X 를 다시 쓰지 못했을 때 보내는 결제 이상 알림의 내용. */
		private Map<String, String> couponTakenAlertDetailsOfA() {
			Map<String, String> details = new LinkedHashMap<>();
			details.put("주문 번호", orderA.getMerchantUid());
			details.put("주문 ID", String.valueOf(orderA.getOrderId()));
			details.put("결제 ID", paymentA);
			details.put("쿠폰 ID", String.valueOf(couponId));
			return details;
		}
	}

	@Nested
	@DisplayName("쿠폰 X1 로 만든 주문 R1 이 결제를 마쳤고 R1 의 환불이 주문 행을 잠근 채 멈춘 동안")
	class RestoresOfOrdersWithDifferentCoupons {

		private final String paymentR1 = "pay_overlap_r1_" + runId;
		private Long couponX1;
		private Long couponX2;
		private OrderCreateResponse orderR1;

		@BeforeEach
		void payOrderR1WithCouponX1() {
			couponX1 = saveCoupon("X1");
			couponX2 = saveCoupon("X2");
			orderR1 = payOrderWithCoupon(couponX1, paymentR1);
		}

		@Test
		@DisplayName("쿠폰 X2 로 결제를 마친 R2 를 환불하면, R2 는 R1 을 기다리지 않고 끝나고 교착 없이 둘 다 환불되어 X1·X2 가 모두 풀린다")
		void refundsOfTwoCouponOrdersDoNotWaitForEachOther() throws Exception {
			// given: X2 로 만든 R2 도 결제를 마쳤고, R1 의 환불이 주문 행을 잠근 채 멈췄다
			String paymentR2 = "pay_overlap_r2_" + runId;
			OrderCreateResponse orderR2 = payOrderWithCoupon(couponX2, paymentR2);
			Future<Boolean> refundOfR1 = startRefundPausedWhileHoldingOrderRow(paymentR1);

			// when: R2 를 환불하고, R2 가 끝나거나 잠금을 기다리기 시작하면 R1 을 이어 가게 한다
			Future<Boolean> refundOfR2 = executor.submit(() -> refund(paymentR2));
			awaitWaitingBehindPausedWorkOrDone(refundOfR2);
			boolean r2FinishedWhileR1Paused = refundOfR2.isDone();
			releaseFirst.countDown();

			// then
			Outcome<Boolean> refundR1 = outcomeOf(refundOfR1);
			Outcome<Boolean> refundR2 = outcomeOf(refundOfR2);
			assertThat(refundR1.error()).as("R1 의 환불 예외").isNull();
			assertThat(refundR2.error()).as("R2 의 환불 예외").isNull();
			assertThat(r2FinishedWhileR1Paused).as("R2 의 환불이 멈춘 R1 을 기다리지 않고 끝났다").isTrue();
			assertThat(paymentStatus(paymentR1)).isEqualTo("CANCELLED");
			assertThat(paymentStatus(paymentR2)).isEqualTo("CANCELLED");
			assertThat(orderStatus(orderR1.getMerchantUid())).isEqualTo("CANCELLED");
			assertThat(orderStatus(orderR2.getMerchantUid())).isEqualTo("CANCELLED");
			assertThat(couponIsUsed(couponX1)).as("R1 이 쓴 쿠폰 X1 사용 여부").isFalse();
			assertThat(couponIsUsed(couponX2)).as("R2 가 쓴 쿠폰 X2 사용 여부").isFalse();
		}

		@Test
		@DisplayName("쿠폰 X2 로 만든 결제 대기 주문 E 를 만료하면, E 는 R1 을 기다리지 않고 끝나고 교착 없이 환불과 만료가 둘 다 끝나 X1·X2 가 모두 풀린다")
		void refundAndExpiryOfTwoCouponOrdersDoNotWaitForEachOther() throws Exception {
			// given: X2 로 만든 E 는 결제 대기이고, R1 의 환불이 주문 행을 잠근 채 멈췄다
			OrderCreateResponse orderE = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponX2));
			Future<Boolean> refundOfR1 = startRefundPausedWhileHoldingOrderRow(paymentR1);

			// when: E 를 만료하고, E 가 끝나거나 잠금을 기다리기 시작하면 R1 을 이어 가게 한다
			Future<Boolean> expiryOfE = executor.submit(
				() -> orderExpirationService.expireIfStillPending(orderE.getOrderId()));
			awaitWaitingBehindPausedWorkOrDone(expiryOfE);
			boolean eFinishedWhileR1Paused = expiryOfE.isDone();
			releaseFirst.countDown();

			// then
			Outcome<Boolean> refundR1 = outcomeOf(refundOfR1);
			Outcome<Boolean> expiryE = outcomeOf(expiryOfE);
			assertThat(refundR1.error()).as("R1 의 환불 예외").isNull();
			assertThat(expiryE.error()).as("E 의 만료 예외").isNull();
			assertThat(eFinishedWhileR1Paused).as("E 의 만료가 멈춘 R1 을 기다리지 않고 끝났다").isTrue();
			assertThat(expiryE.value()).as("E 만료 처리 여부").isTrue();
			assertThat(paymentStatus(paymentR1)).isEqualTo("CANCELLED");
			assertThat(orderStatus(orderR1.getMerchantUid())).isEqualTo("CANCELLED");
			assertThat(orderStatus(orderE.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(couponIsUsed(couponX1)).as("R1 이 쓴 쿠폰 X1 사용 여부").isFalse();
			assertThat(couponIsUsed(couponX2)).as("E 가 쓴 쿠폰 X2 사용 여부").isFalse();
		}
	}

	@Nested
	@DisplayName("할인 코드 D 로 만든 주문 A 가 만료된 뒤")
	class DiscountCodeReleasedByExpiry {

		@Test
		@DisplayName("D 를 잠근 새 주문 생성이 커밋하기 전에 A 가 늦게 결제되어도, 교착 없이 둘 다 끝나고 사용 횟수는 결제 대기·결제 완료 주문 수(2)와 같다")
		void orderCreationAndLatePaymentWithSameCodeFinishWithoutDeadlock() throws Exception {
			// given: D 로 만든 A 가 만료되었고, 새 주문 생성이 D 를 잠근 채 멈췄다
			discountCodeRepository.save(DiscountCodeFixture.fixedAmount(CODE_DISCOUNT).withoutId()
				.code(discountCode).maxUses(100).build());
			OrderCreateResponse orderA = paymentOrderService.createOrder(username, orderRequestWithCode());
			assertThat(orderExpirationService.expireIfStillPending(orderA.getOrderId()))
				.as("준비 단계: 주문 A 만료").isTrue();
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_CODE));
			Future<OrderCreateResponse> newOrder = lockDiscountCodeThenCreateOrderAfterRelease();
			assertThat(firstHoldsLocks.await(10, SECONDS)).as("새 주문 생성이 D 를 잠그고 멈췄다").isTrue();

			// when: A 가 늦게 결제되고, 확정이 잠금을 기다리기 시작하면 새 주문 생성을 이어 간다
			Future<OrderStatus> latePaymentOfA = executor.submit(() ->
				paymentConfirmService.complete(username, completeRequest(paymentA, orderA.getMerchantUid())).getStatus());
			awaitWaitingForLockOrDone(latePaymentOfA);
			releaseFirst.countDown();

			// then
			Outcome<OrderCreateResponse> creation = outcomeOf(newOrder);
			Outcome<OrderStatus> latePayment = outcomeOf(latePaymentOfA);
			assertThat(creation.error()).as("새 주문 생성 예외").isNull();
			assertThat(latePayment.error()).as("A 의 늦은 결제 확정 예외").isNull();
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("PAID");
			assertThat(currentUses()).as("할인 코드 사용 횟수").isEqualTo(2);
			assertThat(pendingOrPaidOrdersUsingCode()).as("결제 대기·결제 완료 주문 수").isEqualTo(2);
		}
	}

	/**
	 * 테스트가 연 트랜잭션에서 work 를 끝낸 뒤 커밋하기 전에 멈춘다. work 가 부른 서비스는 이 트랜잭션에 참여하므로, 멈춘 동안 서비스가
	 * 건 잠금이 남는다. {@link #releaseFirst} 가 열리면 커밋한다.
	 */
	private <T> Future<T> runInTransactionAndHoldBeforeCommit(Supplier<T> work) {
		return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			T result = work.get();
			firstHoldsLocks.countDown();
			awaitRelease();
			return result;
		}));
	}

	/**
	 * 결제 확정이 가장 먼저 하는 일(주문 행 잠금 조회)만 해 두고 멈춘다. {@link #releaseFirst} 가 열리면 같은 트랜잭션에서 확정을 이어
	 * 가고 커밋한다.
	 */
	private <T> Future<T> lockOrderRowThenCompleteAfterRelease(String merchantUid, Supplier<T> completion) {
		return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			orderRepository.findByMerchantUidWithLock(merchantUid).orElseThrow();
			firstHoldsLocks.countDown();
			awaitRelease();
			return completion.get();
		}));
	}

	/**
	 * 주문 생성이 가장 먼저 하는 잠금(할인 코드 행)만 해 두고 멈춘다. {@link #releaseFirst} 가 열리면 같은 트랜잭션에서 주문 생성을
	 * 이어 가고 커밋한다.
	 */
	private Future<OrderCreateResponse> lockDiscountCodeThenCreateOrderAfterRelease() {
		return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			discountCodeService.validateAndCalculateDiscountForPayment(discountCode, PRICE, subCategoryId);
			firstHoldsLocks.countDown();
			awaitRelease();
			return paymentOrderService.createOrder(username, orderRequestWithCode());
		}));
	}

	/**
	 * 결제 paymentId 의 환불을 시작하되, 환불의 검증 단계(PaymentRefundService 의 트랜잭션 A)가 결제·주문 행을 잠근 뒤 초기 결과 행을
	 * 잠가 읽으려는 곳에서, 즉 포트원 취소를 부르기 전에 멈추게 한다. 환불은 자기 트랜잭션을 따로 열므로, 그 결제의 초기 결과 행을 테스트가
	 * 연 트랜잭션에서 먼저 잠가 두는 방식으로 멈춰 세운다. {@link #releaseFirst} 가 열리면 테스트 트랜잭션이 커밋해 잠금을 풀고, 환불은
	 * 이어서 끝난다.
	 */
	private Future<Boolean> startRefundPausedWhileHoldingOrderRow(String paymentId) throws InterruptedException {
		Long paymentPkId = jdbcTemplate.queryForObject("SELECT id FROM payments WHERE imp_uid = ?", Long.class,
			paymentId);
		executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// 상품에 따라 초기 결과는 results 와 compatibility_results 중 한 곳에만 있다. 둘 다 잠가 어느 쪽이든 환불이 멈추게 한다.
			jdbcTemplate.queryForList("SELECT id FROM results WHERE payment_id = ? FOR UPDATE", paymentPkId);
			jdbcTemplate.queryForList("SELECT id FROM compatibility_results WHERE payment_id = ? FOR UPDATE",
				paymentPkId);
			firstHoldsLocks.countDown();
			awaitRelease();
		}));
		assertThat(firstHoldsLocks.await(10, SECONDS)).as("테스트가 초기 결과 행을 잠갔다").isTrue();
		Future<Boolean> refund = executor.submit(() -> refund(paymentId));
		await().atMost(Duration.ofSeconds(10)).until(() -> refund.isDone() || lockWaitsInThisSchema() > 0);
		assertThat(refund.isDone()).as("환불이 초기 결과 행 잠금에서 멈췄다").isFalse();
		return refund;
	}

	private void awaitRelease() {
		try {
			assertThat(releaseFirst.await(30, SECONDS)).as("테스트가 30초 안에 먼저 작업을 풀어 준다").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("먼저 시작한 작업이 풀려나기를 기다리다 중단됐다", e);
		}
	}

	/**
	 * 나중 작업이 이 스키마의 행 잠금을 기다리기 시작했거나 이미 끝날 때까지 기다린다. 어느 쪽인지는 가리지 않고, 결과는 끝난 뒤의
	 * DB 상태로 판단한다.
	 */
	private void awaitWaitingForLockOrDone(Future<?> second) {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> second.isDone() || lockWaitsInThisSchema() > 0);
	}

	/**
	 * 먼저 시작한 작업이 테스트가 잠근 행을 이미 기다리고 있을 때(대기 1건), 나중 작업이 끝나거나 잠금을 기다리기 시작해 대기가 2건이 될
	 * 때까지 기다린다.
	 */
	private void awaitWaitingBehindPausedWorkOrDone(Future<?> second) {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> second.isDone() || lockWaitsInThisSchema() >= 2);
	}

	private int lockWaitsInThisSchema() {
		Integer waits = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
			+ "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
			+ "WHERE l.OBJECT_SCHEMA = DATABASE()", Integer.class);
		return waits == null ? 0 : waits;
	}

	private static <T> Outcome<T> outcomeOf(Future<T> future) throws Exception {
		try {
			return new Outcome<>(future.get(30, SECONDS), null);
		} catch (ExecutionException e) {
			return new Outcome<>(null, e.getCause());
		}
	}

	private record Outcome<T>(T value, Throwable error) {

	}

	private Long saveCoupon(String label) {
		return couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
			.userId(userId).name("겹침 쿠폰 " + label + " " + runId).build()).getId();
	}

	/** 쿠폰으로 주문을 만들고 결제 paymentId 로 결제를 마친다. */
	private OrderCreateResponse payOrderWithCoupon(Long couponId, String paymentId) {
		OrderCreateResponse order = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
		given(portOneClient.getPayment(paymentId))
			.willReturn(paidResponse(paymentId, order.getMerchantUid(), PRICE_WITH_COUPON));
		paymentConfirmService.complete(username, completeRequest(paymentId, order.getMerchantUid()));
		return order;
	}

	private boolean refund(String paymentId) {
		paymentRefundService.cancel(username, paymentId, "단순 변심");
		return true;
	}

	private OrderCreateRequest orderRequestWithCoupon(Long couponId) {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setCouponId(couponId);
		return request;
	}

	private OrderCreateRequest orderRequestWithCode() {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setDiscountCode(discountCode);
		return request;
	}

	private boolean couponIsUsed(Long couponId) {
		return Boolean.TRUE.equals(
			jdbcTemplate.queryForObject("SELECT is_used FROM coupons WHERE id = ?", Boolean.class, couponId));
	}

	private String orderStatus(String merchantUid) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private String paymentStatus(String paymentId) {
		return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE imp_uid = ?", String.class, paymentId);
	}

	private Integer currentUses() {
		return jdbcTemplate.queryForObject("SELECT current_uses FROM discount_codes WHERE code = ?",
			Integer.class, discountCode);
	}

	private Integer pendingOrPaidOrdersUsingCode() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE applied_discount_code = ? "
			+ "AND status IN ('PENDING', 'PAID')", Integer.class, discountCode);
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
}
