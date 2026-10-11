package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 만료(EXPIRED)된 주문이 늦게 결제될 때 "결제 대기·결제 완료 주문의 할인은 사용된 상태" 가 지켜지는지 실제 MySQL 로 확인한다.
 *
 * <p>만료는 쿠폰을 미사용으로, 할인 코드 사용 횟수를 1 줄여 둔다. 그 뒤 결제가 확정되면 할인을 다시 사용 처리해야 하고, 그사이 같은
 * 쿠폰을 다른 주문이 가져갔거나 할인 코드가 선착순 횟수에 닿았으면 확정하지 않고 포트원에서 결제를 취소하며 운영 채널에 알린다.
 *
 * <p>주문 생성·만료·결제 완료·환불은 실제 서비스를 부르고, 포트원과 Discord 만 목으로 바꾼다. 만료는 스케줄러를 기다리지 않고
 * 만료 서비스를 직접 부른다. 쿠폰 사용 여부와 사용 횟수는 JPA 캐시를 거치지 않고 SQL 로 읽는다. 데이터는 실행마다 다른 키(runId)로
 * 만들고 그 키로 만든 행만 지운다.
 *
 * <p>여기서는 작업을 하나씩 차례로 부른다. 늦은 결제 확정이 커밋되기 전에 다른 작업이 겹치는 경우는
 * {@link LatePaidDiscountOverlapMySqlTest} 가 확인한다.
 */
class LatePaidDiscountMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	private static final int COUPON_DISCOUNT = 3000;
	private static final int PRICE_WITH_COUPON = 7000;
	private static final int CODE_DISCOUNT = 1000;
	private static final int PRICE_WITH_CODE = 9000;

	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private OrderExpirationService orderExpirationService;
	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private DiscountCodeService discountCodeService;
	@Autowired
	private CouponService couponService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private DiscountCodeRepository discountCodeRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "late_paid_" + runId;
	private final String discountCode = "LATE-" + runId;

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createBuyerAndProduct() {
		userId = userRepository.save(User.create(username, "늦은결제", "password",
			username + "@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false))
			.getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("늦은 결제 테스트 상품 " + runId).price(PRICE).build()).getId();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
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
	}

	@Nested
	@DisplayName("쿠폰 X 로 만든 주문 A 가 만료되어 X 가 풀린 뒤")
	class CouponReleasedByExpiry {

		private Long couponId;
		private OrderCreateResponse orderA;

		@BeforeEach
		void createOrderWithCouponAndExpireIt() {
			couponId = couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
				.userId(userId).name("늦은 결제 쿠폰 " + runId).build()).getId();
			orderA = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
			assertThat(orderA.getAmount()).as("준비 단계: 쿠폰 할인가").isEqualTo(PRICE_WITH_COUPON);

			assertThat(orderExpirationService.expireIfStillPending(orderA.getOrderId()))
				.as("준비 단계: 주문 A 만료").isTrue();
			assertThat(couponIsUsed(couponId)).as("준비 단계: 만료가 쿠폰을 풀었다").isFalse();
		}

		@Test
		@DisplayName("A 가 늦게 결제되면 A 를 PAID 로 확정하고 쿠폰 X 를 다시 사용 처리한다")
		void latePaymentUsesCouponAgain() {
			// given
			String paymentA = "pay_late_a_" + runId;
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_COUPON));

			// when
			Order paid = paymentConfirmService.complete(username,
				completeRequest(paymentA, orderA.getMerchantUid()));

			// then
			assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("PAID");
			assertThat(couponIsUsed(couponId)).as("쿠폰 X 사용 여부").isTrue();
		}

		@Test
		@DisplayName("그사이 X 로 주문 B 를 만들었으면 A 의 늦은 결제는 확정하지 않고 포트원에서 취소하며 결제 이상 알림을 한 번 보낸다. A 는 EXPIRED, X 는 B 가 쥔 채 사용 상태다")
		void latePaymentIsCancelledWhenCouponTakenByPendingOrder() {
			// given: X 로 주문 B 를 만든 뒤 A 가 늦게 결제된다
			OrderCreateResponse orderB = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
			String paymentA = "pay_late_a_" + runId;
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_COUPON));

			// when & then
			assertThatThrownBy(() -> paymentConfirmService.complete(username,
				completeRequest(paymentA, orderA.getMerchantUid())))
				.isInstanceOf(PaymentException.class)
				.hasMessage(PaymentConfirmService.LATE_PAYMENT_WITHOUT_DISCOUNT_MESSAGE);
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(orderStatus(orderB.getMerchantUid())).isEqualTo("PENDING");
			assertThat(couponIsUsed(couponId)).as("B 가 쥔 쿠폰 X 사용 여부").isTrue();
			then(portOneClient).should().cancelPayment(paymentA, DuplicatePaymentCanceller.LATE_PAYMENT_CANCEL_REASON);
			assertCancelAlertSentOnce(orderA, paymentA);
		}

		@Test
		@DisplayName("X 로 만든 주문 B 가 결제까지 마친 뒤 A 가 늦게 결제되면 A 는 확정하지 않고 취소한다. B 는 PAID, X 는 B 가 쥔 채 사용 상태다")
		void latePaymentIsCancelledWhenCouponHeldByPaidOrder() {
			// given: X 로 만든 주문 B 가 결제를 마친다
			OrderCreateResponse orderB = paymentOrderService.createOrder(username, orderRequestWithCoupon(couponId));
			String paymentB = "pay_late_b_" + runId;
			given(portOneClient.getPayment(paymentB))
				.willReturn(paidResponse(paymentB, orderB.getMerchantUid(), PRICE_WITH_COUPON));
			paymentConfirmService.complete(username, completeRequest(paymentB, orderB.getMerchantUid()));
			String paymentA = "pay_late_a_" + runId;
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_COUPON));

			// when & then
			assertThatThrownBy(() -> paymentConfirmService.complete(username,
				completeRequest(paymentA, orderA.getMerchantUid())))
				.isInstanceOf(PaymentException.class);
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(orderStatus(orderB.getMerchantUid())).isEqualTo("PAID");
			assertThat(couponIsUsed(couponId)).as("결제 완료된 B 가 쥔 쿠폰 X 사용 여부").isTrue();
			then(portOneClient).should().cancelPayment(paymentA, DuplicatePaymentCanceller.LATE_PAYMENT_CANCEL_REASON);
		}
	}

	@Nested
	@DisplayName("할인 코드로 만든 주문 A 가 만료되어 사용 횟수가 줄어든 뒤")
	class DiscountCodeReleasedByExpiry {

		@Test
		@DisplayName("A 가 늦게 결제되면 사용 횟수를 다시 올려 결제 대기·결제 완료 주문 수(1)와 같게 둔다")
		void latePaymentCountsCodeUsageAgain() {
			// given
			saveDiscountCode(100);
			OrderCreateResponse orderA = createOrderWithCodeAndExpireIt();
			String paymentA = "pay_late_code_a_" + runId;
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_CODE));

			// when
			paymentConfirmService.complete(username, completeRequest(paymentA, orderA.getMerchantUid()));

			// then
			assertThat(currentUses()).as("할인 코드 사용 횟수").isEqualTo(1);
			assertThat(pendingOrPaidOrdersUsingCode()).as("결제 대기·결제 완료 주문 수").isEqualTo(1);
		}

		@Test
		@DisplayName("선착순 1명 코드의 자리를 그사이 주문 B 가 가져갔으면 A 의 늦은 결제는 확정하지 않고 취소한다. 사용 횟수는 B 몫 1 그대로다")
		void latePaymentPastLimitIsCancelled() {
			// given
			saveDiscountCode(1);
			OrderCreateResponse orderA = createOrderWithCodeAndExpireIt();
			paymentOrderService.createOrder(username, orderRequestWithCode());
			String paymentA = "pay_late_code_a_" + runId;
			given(portOneClient.getPayment(paymentA))
				.willReturn(paidResponse(paymentA, orderA.getMerchantUid(), PRICE_WITH_CODE));

			// when & then
			assertThatThrownBy(() -> paymentConfirmService.complete(username,
				completeRequest(paymentA, orderA.getMerchantUid())))
				.isInstanceOf(PaymentException.class);
			assertThat(orderStatus(orderA.getMerchantUid())).isEqualTo("EXPIRED");
			assertThat(currentUses()).as("할인 코드 사용 횟수").isEqualTo(1);
			assertThat(pendingOrPaidOrdersUsingCode()).as("결제 대기·결제 완료 주문 수").isEqualTo(1);
			then(portOneClient).should().cancelPayment(paymentA, DuplicatePaymentCanceller.LATE_PAYMENT_CANCEL_REASON);
			assertCancelAlertSentOnce(orderA, paymentA);
		}

		private OrderCreateResponse createOrderWithCodeAndExpireIt() {
			OrderCreateResponse order = paymentOrderService.createOrder(username, orderRequestWithCode());
			assertThat(order.getAmount()).as("준비 단계: 할인 코드 할인가").isEqualTo(PRICE_WITH_CODE);
			assertThat(orderExpirationService.expireIfStillPending(order.getOrderId()))
				.as("준비 단계: 주문 A 만료").isTrue();
			assertThat(currentUses()).as("준비 단계: 만료가 사용 횟수를 줄였다").isZero();
			return order;
		}
	}

	/**
	 * 잠금 전제를 가진 할인 사용·복구 메서드는 전파가 MANDATORY 라, 진행 중인 트랜잭션 없이 부르면 스프링이 거절하고 DB 를 바꾸지 않는다.
	 */
	@Nested
	@DisplayName("잠금을 쥔 트랜잭션 안에서 불러야 하는 메서드를 트랜잭션 밖에서 부르면")
	class CalledOutsideTransaction {

		private static final String NO_TRANSACTION_MESSAGE =
			"No existing transaction found for transaction marked with propagation 'mandatory'";

		@Test
		@DisplayName("할인 코드 사용 횟수 증가(incrementUsage)는 IllegalTransactionStateException 으로 거절하고 횟수를 바꾸지 않는다")
		void incrementUsageIsRejected() {
			// given: 잠금을 쥔 트랜잭션이 이미 끝난 엔티티
			DiscountCode detached = saveDiscountCode(100);

			// when & then
			assertThatThrownBy(() -> discountCodeService.incrementUsage(detached))
				.isInstanceOf(IllegalTransactionStateException.class)
				.hasMessage(NO_TRANSACTION_MESSAGE);
			assertThat(currentUses()).isZero();
		}

		@Test
		@DisplayName("늦은 결제 몫의 할인 코드 사용 횟수 증가(claimForPaidOrder)는 IllegalTransactionStateException 으로 거절하고 횟수를 바꾸지 않는다")
		void codeClaimForPaidOrderIsRejected() {
			// given
			saveDiscountCode(100);

			// when & then
			assertThatThrownBy(() -> discountCodeService.claimForPaidOrder(discountCode))
				.isInstanceOf(IllegalTransactionStateException.class)
				.hasMessage(NO_TRANSACTION_MESSAGE);
			assertThat(currentUses()).isZero();
		}

		@Test
		@DisplayName("늦은 결제 몫의 쿠폰 사용 처리(claimForPaidOrder)는 IllegalTransactionStateException 으로 거절하고 쿠폰을 미사용으로 둔다")
		void claimForPaidOrderIsRejected() {
			// given
			Long couponId = couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
				.userId(userId).build()).getId();

			// when & then
			assertThatThrownBy(() -> couponService.claimForPaidOrder(couponId))
				.isInstanceOf(IllegalTransactionStateException.class)
				.hasMessage(NO_TRANSACTION_MESSAGE);
			assertThat(couponIsUsed(couponId)).isFalse();
		}

		@Test
		@DisplayName("쿠폰 되돌리기(restoreCoupon)는 IllegalTransactionStateException 으로 거절하고 쿠폰을 사용 상태로 둔다")
		void restoreCouponIsRejected() {
			// given
			Long couponId = couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
				.userId(userId).usedAt(LocalDateTime.of(2026, 9, 1, 10, 0)).build()).getId();

			// when & then
			assertThatThrownBy(() -> couponService.restoreCoupon(couponId))
				.isInstanceOf(IllegalTransactionStateException.class)
				.hasMessage(NO_TRANSACTION_MESSAGE);
			assertThat(couponIsUsed(couponId)).isTrue();
		}
	}

	private void assertCancelAlertSentOnce(OrderCreateResponse order, String paymentId) {
		Map<String, String> expectedDetails = new LinkedHashMap<>();
		expectedDetails.put("주문 번호", order.getMerchantUid());
		expectedDetails.put("주문 ID", String.valueOf(order.getOrderId()));
		expectedDetails.put("취소한 결제 ID", paymentId);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(discordNotificationService).should(times(1)).sendPaymentAnomalyNotification(
				"만료된 주문에 결제가 늦게 들어왔는데 할인이 그사이 다른 주문에 쓰여 결제를 자동으로 취소했습니다.", expectedDetails));
	}

	private DiscountCode saveDiscountCode(int maxUses) {
		return discountCodeRepository.save(DiscountCodeFixture.fixedAmount(CODE_DISCOUNT).withoutId()
			.code(discountCode).maxUses(maxUses).build());
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
