package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 포트원 결제 한 건을 두 주문에 붙이지 못하는지 실제 MySQL 로 확인한다.
 *
 * <p>막으려는 공격: 브라우저에서 결제 pay_A 를 한 번 만들어 주문 A 를 확정한 뒤, 같은 금액의 주문 B 에 "pay_A#1" 로 결제 완료를
 * 보낸다. 예전에는 조회 주소가 '#' 에서 잘려 포트원이 pay_A 를 돌려줬고, 중복 검사(payments.imp_uid)는 요청 문자열 "pay_A#1"
 * 로 해서 걸리지 않아 B 도 PAID 가 됐다. 여기서는 포트원 목이 "pay_A#1" 에도 pay_A 를 돌려주게 해 그 상황을 재현한다.
 * 공격자는 customData 없이 결제를 만들어 주문 번호 대조도 건너뛰었으므로, 그런 결제로는 어느 주문도 확정되지 않는지도 본다.
 *
 * <p>결제 행 개수와 주문 상태는 JPA 캐시를 거치지 않고 SQL 로 센다. 데이터는 실행마다 다른 키(runId)로 만들고 그 키로만 지운다.
 */
class PaymentIdBindingMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;

	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private OrderRepository orderRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "id_binding_" + runId;
	private final String orderA = "order_id_binding_a_" + runId;
	private final String orderB = "order_id_binding_b_" + runId;
	private final String paymentA = "pay_id_binding_" + runId;

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createBuyerAndTwoPendingOrdersOfSamePrice() {
		userId = userRepository.save(User.create(username, "결제대조", "password",
			username + "@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false))
			.getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("결제 ID 대조 테스트 상품 " + runId).price(PRICE).build()).getId();
		for (String merchantUid : List.of(orderA, orderB)) {
			orderRepository.save(Order.create(merchantUid, userId, subCategoryId, PRICE, PRICE, null,
				null, OrderStatus.PENDING, "결제대조", username + "@example.com"));
		}
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		String paymentsOfTheseOrders = "SELECT id FROM payments WHERE merchant_uid IN (?, ?)";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfTheseOrders + ")",
			orderA, orderB);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN ("
			+ paymentsOfTheseOrders + ")", orderA, orderB);
		jdbcTemplate.update("DELETE FROM payments WHERE merchant_uid IN (?, ?)", orderA, orderB);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid IN (?, ?)", orderA, orderB);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
	}

	@Test
	@DisplayName("주문 A 를 결제 pay_A 로 확정한 뒤 같은 금액의 주문 B 에 'pay_A#1' 로 결제 완료를 보내면 결제 ID 불일치로 거부되고, 결제 행은 A 의 하나뿐이며 B 는 PENDING 으로 남는다")
	void variantPaymentIdCannotBeAttachedToSecondOrder() {
		// given: 포트원 목은 "pay_A#1" 도 pay_A 로 해석해 돌려준다(예전에 조회 주소가 '#' 에서 잘리던 상황)
		PortOnePaymentResponse paidForOrderA = paidResponse(paymentA, orderA);
		given(portOneClient.getPayment(paymentA)).willReturn(paidForOrderA);
		given(portOneClient.getPayment(paymentA + "#1")).willReturn(paidForOrderA);
		confirmOrderAWithPaymentA();

		// when
		Throwable rejected = catchThrowable(
			() -> paymentConfirmService.complete(username, completeRequest(paymentA + "#1", orderB)));

		// then
		assertThat(rejected)
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보의 결제 ID가 일치하지 않습니다.");
		assertOnlyOrderAHoldsPaymentA();
	}

	@Test
	@DisplayName("주문 A 를 결제 pay_A 로 확정한 뒤 같은 금액의 주문 B 에 'pay_A' 그대로 결제 완료를 보내면 이미 처리된 결제로 거부되고, 결제 행은 A 의 하나뿐이며 B 는 PENDING 으로 남는다")
	void samePaymentIdCannotBeAttachedToSecondOrder() {
		// given
		given(portOneClient.getPayment(paymentA)).willReturn(paidResponse(paymentA, orderA));
		confirmOrderAWithPaymentA();

		// when
		Throwable rejected = catchThrowable(
			() -> paymentConfirmService.complete(username, completeRequest(paymentA, orderB)));

		// then
		assertThat(rejected)
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 처리된 결제입니다.");
		assertOnlyOrderAHoldsPaymentA();
	}

	@Test
	@DisplayName("customData 없이 만든 결제 pay_A 로 주문 A 에 결제 완료를 보내면 주문 번호 대조를 건너뛰지 않고 거부해, 결제 행이 생기지 않고 A 는 PENDING 으로 남는다")
	void paymentWithoutCustomDataConfirmsNoOrder() {
		// given: 예전 공격은 customData 없이 결제를 만들어 주문 번호 대조를 건너뛰었다
		PortOnePaymentResponse withoutCustomData = paidResponse(paymentA, orderA);
		withoutCustomData.setCustomData(null);
		given(portOneClient.getPayment(paymentA)).willReturn(withoutCustomData);

		// when
		Throwable rejected = catchThrowable(
			() -> paymentConfirmService.complete(username, completeRequest(paymentA, orderA)));

		// then
		assertThat(rejected)
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보에 주문 번호가 없습니다.");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM payments WHERE merchant_uid IN (?, ?)", Integer.class, orderA, orderB))
			.as("두 주문에 붙은 결제 행").isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM orders WHERE merchant_uid = ?", String.class, orderA))
			.as("주문 A 상태").isEqualTo("PENDING");
	}

	private void confirmOrderAWithPaymentA() {
		Order confirmed = paymentConfirmService.complete(username, completeRequest(paymentA, orderA));
		assertThat(confirmed.getStatus()).as("준비 단계: 주문 A 확정").isEqualTo(OrderStatus.PAID);
	}

	/** DB 에 남은 사실을 SQL 로 확인한다. 결제 행은 A 의 pay_A 하나, B 는 결제 없이 PENDING. */
	private void assertOnlyOrderAHoldsPaymentA() {
		assertThat(jdbcTemplate.queryForList(
			"SELECT merchant_uid, imp_uid FROM payments WHERE merchant_uid IN (?, ?)", orderA, orderB))
			.as("두 주문에 붙은 결제 행")
			.containsExactly(Map.of("merchant_uid", orderA, "imp_uid", paymentA));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM orders WHERE merchant_uid = ?", String.class, orderB))
			.as("주문 B 상태").isEqualTo("PENDING");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT payment_id FROM orders WHERE merchant_uid = ?", String.class, orderB))
			.as("주문 B 에 적힌 결제 ID").isNull();
	}

	private PaymentCompleteRequest completeRequest(String paymentId, String merchantUid) {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(merchantUid);
		return request;
	}

	/** customData 에 주문 번호를 담아 만든 결제의 포트원 조회 응답. 금액은 두 주문과 같다. */
	private PortOnePaymentResponse paidResponse(String paymentId, String merchantUidInCustomData) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal((long) PRICE);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus("PAID");
		response.setAmount(amount);
		response.setCustomData("{\"merchantUid\":\"" + merchantUidInCustomData + "\"}");
		return response;
	}
}
