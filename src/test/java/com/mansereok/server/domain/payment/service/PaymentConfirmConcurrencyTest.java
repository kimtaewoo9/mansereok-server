package com.mansereok.server.domain.payment.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import com.mansereok.server.support.fixture.TestOrders;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 같은 주문에 결제 완료 요청이 한꺼번에 몰려도 결제·결과지·알림이 한 번씩만 생기고 모든 요청이 PAID 를 받는지 실제 MySQL 로 확인한다.
 *
 * <p>결제 확정은 주문 행을 잠가(OrderRepository#findByMerchantUidWithLock) 읽고, 이미 PAID 면 같은 결제의 재요청으로 보고 그대로
 * 돌려준다. 잠금이 없으면 요청들이 모두 PENDING 을 읽고 결제를 저장하려다, payments.imp_uid UNIQUE 덕분에 결제 행은 하나로 남지만
 * 나머지 요청은 "이미 처리된 결제입니다." 로 실패한다. 결제 행 개수만 세면 이 회귀를 놓치므로 요청마다 결과를 확인한다.
 *
 * <p>잠금과 UNIQUE 는 DB 가 지키는 규칙이라 DB 는 진짜를 쓰고, 밖으로 나가는 호출(포트원 조회, Discord 알림)만 목이다. 데이터는
 * 실행마다 다른 키(runId)로 만들고 그 키로 만든 행만 지운다.
 */
class PaymentConfirmConcurrencyTest extends PaymentMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리느라 느려진다.
	private static final int REQUEST_COUNT = 10;
	private static final int PRICE = 10000;
	private static final String BUYER_NAME = "결제동시";

	@Autowired
	private PaymentConfirmService paymentConfirmService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private OrderRepository orderRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "confirm_race_" + runId;
	private final String email = username + "@example.com";
	private final String merchantUid = "order_confirm_race_" + runId;
	private final String paymentId = "pay_confirm_race_" + runId;

	private Long userId;
	private Long subCategoryId;

	@BeforeEach
	void createPendingOrder() {
		userId = userRepository.save(User.create(username, BUYER_NAME, "password", email,
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("결제 동시 확정 테스트 상품 " + runId).price(PRICE).build()).getId();
		orderRepository.save(TestOrders.order().merchantUid(merchantUid).userId(userId).subCategoryId(subCategoryId)
			.price(PRICE).buyer(BUYER_NAME, email).pending());
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

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 주문에 결제 완료 요청이 동시에 10번 와도 결제·결과지·알림은 한 번씩만 생기고 모든 요청이 PAID 를 받는다")
	void sameOrderCompletedConcurrently() {
		// given
		given(portOneClient.getPayment(paymentId)).willReturn(paidResponse());

		// when
		List<CallResult<OrderStatus>> results = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT,
			() -> paymentConfirmService.complete(username, completeRequest()).getStatus());

		// then: 예외를 삼키지 않고 모든 요청의 결과를 확인한다
		String resultsPerRequest = describe(results);
		assertThat(results).as("요청마다 예외 없이 PAID 를 받았다. %s", resultsPerRequest)
			.allSatisfy(result -> {
				assertThat(result.error()).isNull();
				assertThat(result.value()).isEqualTo(OrderStatus.PAID);
			});

		// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(countRows("SELECT COUNT(*) FROM payments WHERE merchant_uid = ?"))
			.as("결제 행. %s", resultsPerRequest).isEqualTo(1);
		assertThat(countRows("SELECT COUNT(*) FROM results WHERE payment_id IN "
			+ "(SELECT id FROM payments WHERE merchant_uid = ?)")
			+ countRows("SELECT COUNT(*) FROM compatibility_results WHERE payment_id IN "
			+ "(SELECT id FROM payments WHERE merchant_uid = ?)"))
			.as("결과지 행. %s", resultsPerRequest).isEqualTo(1);
		assertThat(orderStatus()).isEqualTo("PAID");

		// then: 알림은 커밋 뒤 다른 스레드에서 가므로 sleep 대신 조건이 맞을 때까지 기다린다. 이 실행의 구매자로 간 알림만 센다.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(discordNotificationService).should(times(1)).sendPaymentCompletedNotification(
				eq(BUYER_NAME), eq(email), eq((long) PRICE), any(), any(), any(), any()));
	}

	@Test
	@DisplayName("포트원 조회에서 멈춰 있던 요청은 다른 요청이 먼저 확정·커밋한 주문을 PAID 로 그대로 돌려받는다")
	void lateRequestSeesCommittedPaidOrder() throws Exception {
		// given: 첫 번째 포트원 조회만 래치에서 멈추게 해 순서를 고정한다(sleep 을 쓰지 않는다)
		CountDownLatch firstCallPaused = new CountDownLatch(1);
		CountDownLatch releaseFirstCall = new CountDownLatch(1);
		AtomicInteger portOneCalls = new AtomicInteger();
		given(portOneClient.getPayment(paymentId)).willAnswer(invocation -> {
			if (portOneCalls.getAndIncrement() == 0) {
				firstCallPaused.countDown();
				assertThat(releaseFirstCall.await(30, SECONDS)).as("테스트가 30초 안에 첫 요청을 풀어 준다").isTrue();
			}
			return paidResponse();
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Order> late = executor.submit(() -> paymentConfirmService.complete(username, completeRequest()));
			assertThat(firstCallPaused.await(10, SECONDS)).as("첫 요청이 포트원 조회에서 멈췄다").isTrue();

			// when: 두 번째 요청이 먼저 잠금을 잡고 확정·커밋한 뒤, 멈춰 있던 요청을 풀어 준다
			Order early = paymentConfirmService.complete(username, completeRequest());
			releaseFirstCall.countDown();
			Order lateResult = late.get(30, SECONDS);

			// then
			assertThat(early.getStatus()).as("먼저 확정한 요청").isEqualTo(OrderStatus.PAID);
			assertThat(lateResult.getStatus()).as("멈춰 있던 요청").isEqualTo(OrderStatus.PAID);
			assertThat(countRows("SELECT COUNT(*) FROM payments WHERE merchant_uid = ?")).as("결제 행").isEqualTo(1);
		} finally {
			// 단언이 실패해도 멈춘 요청을 풀어 트랜잭션을 끝낸 뒤 정리한다
			releaseFirstCall.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(60, SECONDS)).as("작업 스레드가 끝났다").isTrue();
		}
	}

	private int countRows(String sql) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, merchantUid);
		return count == null ? 0 : count;
	}

	private String orderStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	private PaymentCompleteRequest completeRequest() {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(paymentId);
		request.setMerchantUid(merchantUid);
		return request;
	}

	/** customData 에 주문 번호를 담아 만든 결제의 포트원 조회 응답. */
	private PortOnePaymentResponse paidResponse() {
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal((long) PRICE);
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus("PAID");
		response.setAmount(amount);
		response.setCustomData("{\"merchantUid\":\"" + merchantUid + "\"}");
		return response;
	}

	/** 실패 메시지에 넣을 요청별 결과. 예: "요청별 결과 [PAID, PaymentException(이미 처리된 결제입니다.), ...]" */
	private static String describe(List<? extends CallResult<?>> results) {
		return results.stream()
			.map(result -> result.succeeded() ? String.valueOf(result.value())
				: result.error().getClass().getSimpleName() + "(" + result.error().getMessage() + ")")
			.collect(joining(", ", "요청별 결과 [", "]"));
	}
}
