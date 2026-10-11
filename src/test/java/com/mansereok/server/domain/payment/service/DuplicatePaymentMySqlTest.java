package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import com.mansereok.server.support.fixture.TestOrders;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 같은 주문에 서로 다른 결제 두 건이 동시에 확정을 시도해도, 주문은 한 결제로만 확정되고 다른 결제는 포트원에서 취소되는지 실제
 * MySQL 로 확인한다.
 *
 * <p>사용자가 같은 주문을 두 번 결제하면(모바일 리다이렉트 뒤 뒤로 가기, 탭 두 개 등) 결제 ID 가 다른 두 결제가 모두 승인된다.
 * 주문 행 잠금 때문에 두 요청은 차례로 확정을 시도하고, 뒤에 온 요청은 이미 다른 결제로 PAID 가 된 주문을 본다. 예전에는 이 요청이
 * 성공으로 끝나 두 번째 결제가 취소도 기록도 없이 남았다. 어느 요청이 먼저 잠금을 잡을지는 매번 달라서 여러 번 되풀이한다.
 * 완료 요청과 웹훅이 겹칠 때는 웹훅이 먼저 잠금을 잡는 일이 많아, 웹훅이 뒤에 와서 취소하는 경우는 순서를 고정한 테스트로 따로 본다.
 *
 * <p>포트원은 목이다. 두 결제 모두 customData 에 이 주문을 담은 승인 결제로 답하고, 취소는 성공한다. DB 에 남은 사실은 JPA 캐시를
 * 거치지 않고 SQL 로 센다. 데이터는 실행마다 다른 키(runId)로 만들고 그 키로만 지운다. 운영 채널 알림은 커밋 뒤 다른 스레드에서
 * 가므로, 자동 취소 알림이 한 번 간 뒤에도 잠시 지켜봐 뒤늦게 오는 두 번째 알림까지 잡는다.
 */
class DuplicatePaymentMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	private static final String CANCEL_REASON = "같은 주문의 중복 결제 자동 취소";
	private static final String DUPLICATE_PAYMENT_MESSAGE = "이미 결제가 끝난 주문입니다. 중복 결제는 자동으로 취소됩니다.";
	private static final String CANCELLED_ALERT = "이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 자동으로 취소했습니다.";
	private static final String CANCEL_FAILED_ALERT =
		"이미 결제가 끝난 주문에 결제가 한 번 더 승인됐는데 자동 취소에 실패했습니다. 포트원에서 손으로 취소해 주세요.";
	/** 자동 취소 알림이 한 번 간 뒤에도 알림 수가 그대로인지 지켜보는 시간. 알림은 요청이 끝나기 전에 모두 알림 풀에 들어간다. */
	private static final Duration ALERT_WATCH_TIME = Duration.ofMillis(300);

	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private PaymentWebhookService paymentWebhookService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private OrderRepository orderRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "duplicate_pay_" + runId;
	private final String merchantUid = "order_duplicate_pay_" + runId;
	private final String firstPaymentId = "pay_duplicate_first_" + runId;
	private final String secondPaymentId = "pay_duplicate_second_" + runId;

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createPendingOrderAndTwoApprovedPayments() {
		userId = userRepository.save(User.create(username, "중복결제", "password",
			username + "@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false))
			.getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("중복 결제 테스트 상품 " + runId).price(PRICE).build()).getId();
		orderRepository.save(TestOrders.order().merchantUid(merchantUid).userId(userId).subCategoryId(subCategoryId)
			.price(PRICE).buyer("중복결제", username + "@example.com").pending());

		given(portOneClient.getPayment(firstPaymentId)).willReturn(paidResponseForThisOrder(firstPaymentId));
		given(portOneClient.getPayment(secondPaymentId)).willReturn(paidResponseForThisOrder(secondPaymentId));
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		String paymentsOfThisOrder = "SELECT id FROM payments WHERE merchant_uid = ?";
		jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfThisOrder + ")", merchantUid);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfThisOrder + ")",
			merchantUid);
		jdbcTemplate.update("DELETE FROM payments WHERE merchant_uid = ?", merchantUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
	}

	@RepeatedTest(value = 20, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 주문에 결제 두 건의 결제 완료 요청이 동시에 오면 주문은 한 결제로만 PAID 가 되고 결제 행은 하나이며, 다른 결제는 포트원에서 한 번 취소되고 그 요청은 안내와 함께 거부된다")
	void twoPaymentsCompletedAtTheSameTime() {
		// given
		List<String> paymentIds = List.of(firstPaymentId, secondPaymentId);

		// when
		List<CallResult<Order>> results = ConcurrentCalls.runAtTheSameTime(2,
			index -> () -> paymentConfirmService.complete(username, completeRequest(paymentIds.get(index))));

		// then: DB 에는 먼저 잠금을 잡은 결제 하나만 남는다
		String confirmedPaymentId = confirmedPaymentIdInDb();
		assertThat(orderStatusInDb()).isEqualTo("PAID");
		assertThat(paymentIdsInDb()).as("결제 행").containsExactly(confirmedPaymentId);

		// then: 다른 결제는 한 번 취소된다
		String cancelledPaymentId = paymentIdCancelledOnce();
		assertThat(List.of(confirmedPaymentId, cancelledPaymentId))
			.as("확정된 결제와 취소된 결제는 서로 다른 두 결제다")
			.containsExactlyInAnyOrder(firstPaymentId, secondPaymentId);

		// then: 요청마다 결과를 확인한다. 확정한 요청은 PAID 주문을, 다른 요청은 안내를 받는다.
		assertThat(results).filteredOn(CallResult::succeeded).singleElement().satisfies(result -> {
			assertThat(result.value().getStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(result.value().getPaymentId()).isEqualTo(confirmedPaymentId);
		});
		assertThat(results).filteredOn(result -> !result.succeeded()).singleElement().satisfies(result ->
			assertThat(result.error()).isInstanceOf(PaymentException.class).hasMessage(DUPLICATE_PAYMENT_MESSAGE));

		// then
		assertOnlyAutoCancelledAlertSentOnce();
	}

	@RepeatedTest(value = 20, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 주문에 첫 결제의 결제 완료 요청과 두 번째 결제의 웹훅이 동시에 오면 결제 행은 하나이고 다른 결제는 한 번 취소되며, 웹훅은 예외 없이 끝나고 완료 요청은 자기 결제가 확정됐을 때만 성공한다")
	void completeAndWebhookOfDifferentPaymentsAtTheSameTime() {
		// given
		List<Callable<Object>> calls = List.of(
			() -> paymentConfirmService.complete(username, completeRequest(firstPaymentId)),
			() -> {
				paymentWebhookService.processWebhook(paidWebhookBody(secondPaymentId));
				return "웹훅 처리 끝";
			});

		// when
		List<CallResult<Object>> results = ConcurrentCalls.runAtTheSameTime(2, calls::get);

		// then
		String confirmedPaymentId = confirmedPaymentIdInDb();
		assertThat(orderStatusInDb()).isEqualTo("PAID");
		assertThat(paymentIdsInDb()).as("결제 행").containsExactly(confirmedPaymentId);
		assertThat(List.of(confirmedPaymentId, paymentIdCancelledOnce()))
			.as("확정된 결제와 취소된 결제는 서로 다른 두 결제다")
			.containsExactlyInAnyOrder(firstPaymentId, secondPaymentId);

		CallResult<Object> completeResult = results.get(0);
		CallResult<Object> webhookResult = results.get(1);
		assertThat(webhookResult.error()).as("웹훅은 어느 쪽이든 예외 없이 끝나 포트원에 200 을 돌려준다").isNull();
		assertThat(completeResult)
			.as("완료 요청은 자기 결제(첫 결제)가 확정됐으면 PAID 주문을 받고, 웹훅의 두 번째 결제가 확정됐으면 안내와 함께 거부된다")
			.satisfiesAnyOf(
				result -> {
					assertThat(confirmedPaymentId).isEqualTo(firstPaymentId);
					assertThat(result.error()).isNull();
					assertThat(result.value()).isInstanceOfSatisfying(Order.class,
						order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID));
				},
				result -> {
					assertThat(confirmedPaymentId).isEqualTo(secondPaymentId);
					assertThat(result.error()).isInstanceOf(PaymentException.class)
						.hasMessage(DUPLICATE_PAYMENT_MESSAGE);
				});
		assertOnlyAutoCancelledAlertSentOnce();
	}

	@Test
	@DisplayName("첫 결제로 확정된 주문에 두 번째 결제의 웹훅이 오면 예외 없이 끝나고, 결제 행은 첫 결제 하나로 남으며 두 번째 결제는 한 번 취소되고 운영 채널에 알림이 간다")
	void webhookOfSecondPaymentAfterFirstIsConfirmed() {
		// given
		Order confirmed = paymentConfirmService.complete(username, completeRequest(firstPaymentId));
		assertThat(confirmed.getStatus()).as("준비 단계: 첫 결제로 확정").isEqualTo(OrderStatus.PAID);

		// when
		paymentWebhookService.processWebhook(paidWebhookBody(secondPaymentId));

		// then
		assertThat(confirmedPaymentIdInDb()).isEqualTo(firstPaymentId);
		assertThat(paymentIdsInDb()).as("결제 행").containsExactly(firstPaymentId);
		assertThat(paymentIdCancelledOnce()).isEqualTo(secondPaymentId);
		assertOnlyAutoCancelledAlertSentOnce();
	}

	/**
	 * 운영 채널에 자동 취소 알림이 한 번 가고, 손으로 취소하라는 알림은 가지 않았는지 확인한다.
	 *
	 * <p>알림은 커밋 뒤 다른 스레드에서 가므로 조건이 맞을 때까지 기다린다. 한 번이 된 순간 끝내면 뒤늦게 오는 두 번째 알림을 놓치므로,
	 * 조건이 맞은 뒤에도 {@link #ALERT_WATCH_TIME} 동안 그대로인지 지켜본다(Awaitility 의 during).
	 */
	private void assertOnlyAutoCancelledAlertSentOnce() {
		await().during(ALERT_WATCH_TIME).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			then(discordNotificationService).should(times(1))
				.sendPaymentAnomalyNotification(eq(CANCELLED_ALERT), anyMap());
			then(discordNotificationService).should(never())
				.sendPaymentAnomalyNotification(eq(CANCEL_FAILED_ALERT), anyMap());
		});
	}

	private String confirmedPaymentIdInDb() {
		return jdbcTemplate.queryForObject("SELECT payment_id FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private String orderStatusInDb() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private List<String> paymentIdsInDb() {
		return jdbcTemplate.queryForList("SELECT imp_uid FROM payments WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	/** 포트원 취소가 정확히 한 번 불렸는지 확인하고, 취소한 결제 ID 를 돌려준다. */
	private String paymentIdCancelledOnce() {
		ArgumentCaptor<String> cancelledPaymentId = ArgumentCaptor.forClass(String.class);
		then(portOneClient).should(times(1)).cancelPayment(cancelledPaymentId.capture(), eq(CANCEL_REASON));
		return cancelledPaymentId.getValue();
	}

	private PaymentCompleteRequest completeRequest(String paymentId) {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(merchantUid);
		return request;
	}

	/** 서명 검증을 마친 뒤 서비스가 받는 예전 형식의 Paid 웹훅 본문. */
	private static String paidWebhookBody(String paymentId) {
		return "{\"tx_id\":\"tx_" + paymentId + "\",\"payment_id\":\"" + paymentId + "\",\"status\":\"Paid\"}";
	}

	/** customData 에 이 주문 번호를 담아 만든, 포트원에서 승인된 결제의 조회 응답. 금액은 주문과 같다. */
	private PortOnePaymentResponse paidResponseForThisOrder(String paymentId) {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal((long) PRICE);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus("PAID");
		response.setAmount(amount);
		response.setCustomData("{\"merchantUid\":\"" + merchantUid + "\"}");
		return response;
	}
}
