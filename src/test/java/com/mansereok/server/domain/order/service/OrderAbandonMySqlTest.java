package com.mansereok.server.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/**
 * 결제창에서 나온 주문의 쿠폰이 만료 스케줄러를 기다리지 않고 바로 돌아오는지, 그 뒤 결제가 늦게 들어오면 쿠폰을 다시 잡아 확정하고
 * 다른 주문이 쿠폰을 가져갔으면 결제를 취소하는지 실제 MySQL 로 확인한다. 쿠폰 사용 여부와 주문 상태는 JPA 캐시를 거치지 않고 SQL 로 읽는다.
 */
class OrderAbandonMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	private static final int PRICE_WITH_COUPON = 7000;

	@Autowired
	private OrderAbandonService orderAbandonService;
	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private CouponRepository couponRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String buyer = "abandon_" + runId;
	private final String stranger = "abandon_other_" + runId;

	private Long buyerId;
	private Long strangerId;
	private Long subCategoryId;
	private Long couponId;
	private OrderCreateResponse order;

	@BeforeEach
	void createOrderWithCoupon() {
		buyerId = saveUser(buyer);
		strangerId = saveUser(stranger);
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("결제창 이탈 테스트 상품 " + runId).price(PRICE).build()).getId();
		couponId = couponRepository.save(CouponFixture.fixedAmount(PRICE - PRICE_WITH_COUPON).withoutId()
			.userId(buyerId).name("결제창 이탈 쿠폰 " + runId).build()).getId();

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setCouponId(couponId);
		order = paymentOrderService.createOrder(buyer, request);
		assertThat(couponIsUsed()).as("준비 단계: 주문이 쿠폰을 쥐었다").isTrue();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		String paymentsOfBuyer = "SELECT id FROM payments WHERE user_id = ?";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfBuyer + ")", buyerId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfBuyer + ")",
			buyerId);
		jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", buyerId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", buyerId);
		jdbcTemplate.update("DELETE FROM coupons WHERE user_id = ?", buyerId);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(buyerId);
		userRepository.deleteById(strangerId);
	}

	@Test
	@DisplayName("결제창에서 나오면 주문은 EXPIRED 가 되고 쿠폰은 바로 다시 쓸 수 있으며, 같은 알림이 또 와도 그대로다")
	void abandonReleasesCouponAtOnce() {
		// when
		orderAbandonService.abandon(order.getOrderId(), buyer);
		orderAbandonService.abandon(order.getOrderId(), buyer);

		// then
		assertThat(orderStatus()).isEqualTo("EXPIRED");
		assertThat(couponIsUsed()).isFalse();
	}

	@Test
	@DisplayName("이탈을 알린 뒤 결제가 늦게 확정되면 주문은 PAID 가 되고 쿠폰은 다시 사용 처리된다")
	void latePaymentAfterAbandonClaimsCouponAgain() {
		// given
		orderAbandonService.abandon(order.getOrderId(), buyer);
		String paymentId = "pay_abandon_" + runId;
		given(portOneClient.getPayment(paymentId)).willReturn(paid(paymentId));

		// when
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(order.getMerchantUid());
		paymentConfirmService.complete(buyer, request);

		// then
		assertThat(orderStatus()).isEqualTo("PAID");
		assertThat(couponIsUsed()).isTrue();
	}

	@Test
	@DisplayName("이탈로 풀린 쿠폰을 다른 주문에 쓴 뒤 처음 주문을 결제하면, 처음 결제는 확정하지 않고 포트원에서 취소해 쿠폰 한 장으로 두 번 할인받지 못한다")
	void reusingReleasedCouponAndPayingAbandonedOrderIsCancelled() {
		// given: 이탈로 풀린 쿠폰으로 주문 B 를 만든다
		orderAbandonService.abandon(order.getOrderId(), buyer);
		OrderCreateRequest requestB = new OrderCreateRequest();
		requestB.setSubCategoryId(subCategoryId);
		requestB.setCouponId(couponId);
		OrderCreateResponse orderB = paymentOrderService.createOrder(buyer, requestB);
		String paymentId = "pay_abandon_reuse_" + runId;
		given(portOneClient.getPayment(paymentId)).willReturn(paid(paymentId));

		// when & then: 이탈한 주문을 할인가로 결제해도 확정되지 않는다
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(order.getMerchantUid());
		assertThatThrownBy(() -> paymentConfirmService.complete(buyer, request))
			.isInstanceOf(PaymentException.class);
		assertThat(orderStatus()).isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			orderB.getMerchantUid())).isEqualTo("PENDING");
		assertThat(couponIsUsed()).as("B 가 쥔 쿠폰").isTrue();
		then(portOneClient).should().cancelPayment(eq(paymentId), anyString());
	}

	@Test
	@DisplayName("결제가 끝난 주문이면 거절하고 쿠폰은 사용 상태로 둔다")
	void paidOrderIsNotReleased() {
		// given
		String paymentId = "pay_paid_" + runId;
		given(portOneClient.getPayment(paymentId)).willReturn(paid(paymentId));
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(order.getMerchantUid());
		paymentConfirmService.complete(buyer, request);

		// when & then
		assertThatThrownBy(() -> orderAbandonService.abandon(order.getOrderId(), buyer))
			.isInstanceOf(OrderStateException.class);
		assertThat(orderStatus()).isEqualTo(OrderStatus.PAID.name());
		assertThat(couponIsUsed()).isTrue();
	}

	@Test
	@DisplayName("남의 주문이면 거절하고 주문과 쿠폰을 그대로 둔다")
	void strangerCannotRelease() {
		// when & then
		assertThatThrownBy(() -> orderAbandonService.abandon(order.getOrderId(), stranger))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(orderStatus()).isEqualTo("PENDING");
		assertThat(couponIsUsed()).isTrue();
	}

	private Long saveUser(String username) {
		return userRepository.save(User.create(username, "이탈", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
	}

	private boolean couponIsUsed() {
		return Boolean.TRUE.equals(
			jdbcTemplate.queryForObject("SELECT is_used FROM coupons WHERE id = ?", Boolean.class, couponId));
	}

	private String orderStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			order.getMerchantUid());
	}

	private PortOnePaymentResponse paid(String paymentId) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal((long) PRICE_WITH_COUPON);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus("PAID");
		response.setAmount(amount);
		response.setCustomData("{\"merchantUid\":\"" + order.getMerchantUid() + "\"}");
		return response;
	}
}
