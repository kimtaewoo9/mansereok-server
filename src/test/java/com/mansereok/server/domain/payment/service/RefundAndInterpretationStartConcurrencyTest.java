package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;

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
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 같은 결제에 환불과 해석 시작이 순서를 정하지 않고 동시에 와도, 둘 중 한쪽만 성공하고 DB 가 이긴 쪽의 결과 하나로만 남는지 실제
 * MySQL 로 확인한다.
 *
 * <p>환불(PaymentRefundService)과 해석 시작(PaymentEntitlementService#startInterpretation)은 모두 결제 행을 먼저 잠가 이 행에서
 * 줄을 선다. 환불이 먼저면 해석 시작은 CANCEL_REQUESTED(또는 CANCELLED)를 보고 거부되고, 해석 시작이 먼저면 환불은 해석 중인
 * 결과를 보고 포트원 취소를 부르기 전에 거부된다. 어느 쪽도 이기지 못하거나 둘 다 성공하면, 포트원 환불은 됐는데 해석이 진행되거나
 * 결제가 CANCEL_REQUESTED 로 멈춰 남는다.
 *
 * <p>RefundAndInterpretationStartMySqlTest 는 래치로 순서를 고정해 두 경우를 하나씩 본다. 이 테스트는 순서를 정하지 않고 둘을 한
 * 순간에 출발시켜, 어느 쪽이 이기든 끝 상태가 허용한 두 가지 중 하나인지 되풀이해 본다.
 *
 * <p>결제·주문·초기 결과는 결제 확정을 거치지 않고 저장소로 바로 만든다. 상품 행은 필요 없다(해석 시작의 상품 대조에만 쓰인다).
 * 데이터는 실행마다 다른 키(runId)로 만들고 그 키로 만든 행만 지운다.
 */
class RefundAndInterpretationStartConcurrencyTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	// 일반 사주 상품. 초기 결과는 results 표에 생긴다.
	private static final Long PRODUCT_ID = 3L;
	private static final String REFUND_REASON = "단순 변심";

	private static final String REFUND_WON = "환불 성공, 해석 시작 거부(PaymentException: 유효한 결제 정보가 아닙니다.)"
		+ " / 결제 CANCELLED, 주문 CANCELLED, 초기 결과 없음, 포트원 취소 1번";
	private static final String INTERPRETATION_WON =
		"환불 거부(PaymentException: 이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.), 해석 시작 성공"
			+ " / 결제 PAID, 주문 PAID, 초기 결과 PROCESSING, 포트원 취소 0번";

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

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "refund_interpret_race_" + runId;
	private final String merchantUid = "order_refund_interpret_race_" + runId;
	private final String impUid = "pay_refund_interpret_race_" + runId;
	private final AtomicInteger portOneCancelCalls = new AtomicInteger();

	private Long userId;
	private Long paymentPkId;

	@BeforeEach
	void createPaidPaymentWithInitialResult() {
		userId = userRepository.save(User.create(username, "환불해석경합", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		Long orderId = orderRepository.save(Order.create(merchantUid, userId, PRODUCT_ID, PRICE, PRICE, null, null,
			OrderStatus.PAID, "환불해석경합", username + "@example.com")).getId();
		paymentPkId = paymentRepository.save(Payment.create(impUid, merchantUid, (long) PRICE, PaymentStatus.PAID,
			orderId, userId, PRODUCT_ID)).getId();
		resultRepository.save(Result.createInitial(userId, paymentPkId, "일반 사주 상품"));
		// 이 결제를 이 사유로 취소한 횟수만 센다. 인자가 다르면 세지 않아 끝 상태가 허용한 두 가지와 어긋난다.
		willAnswer(invocation -> {
			portOneCancelCalls.incrementAndGet();
			return null;
		}).given(portOneClient).cancelPayment(impUid, REFUND_REASON);
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		userRepository.deleteById(userId);
	}

	@RepeatedTest(value = 50, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 결제에 환불과 해석 시작이 동시에 오면 한쪽만 성공하고, 결제·주문·초기 결과·포트원 취소는 이긴 쪽의 끝 상태 하나로만 남는다")
	void onlyOneOfRefundAndInterpretationStartWins() {
		// when: 0번은 환불, 1번은 해석 시작
		List<CallResult<Boolean>> results = ConcurrentCalls.runAtTheSameTime(2, index -> index == 0
			? () -> refund()
			: () -> startInterpretation());

		// then: 요청별 결과와 DB 의 끝 상태를 한 줄로 적어, 허용한 두 끝 상태 중 하나인지 본다
		String outcome = "환불 " + describe(results.get(0)) + ", 해석 시작 " + describe(results.get(1))
			+ " / 결제 " + paymentStatus() + ", 주문 " + orderStatus() + ", 초기 결과 " + resultStatus()
			+ ", 포트원 취소 " + portOneCancelCalls.get() + "번";
		assertThat(outcome).isIn(REFUND_WON, INTERPRETATION_WON);
	}

	private boolean refund() {
		paymentRefundService.cancel(username, impUid, REFUND_REASON);
		return true;
	}

	/** 해석 API 가 일반 사주 상품에 넘기는 결과 변경(ResultService#updateStatusToProcessing)으로 해석을 시작한다. */
	private boolean startInterpretation() {
		paymentEntitlementService.startInterpretation(paymentPkId, username, PRODUCT_ID,
			resultService::updateStatusToProcessing);
		return true;
	}

	private String paymentStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE imp_uid = ?", String.class, impUid);
	}

	private String orderStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}

	/** 초기 결과의 상태. 환불로 지워졌으면 "없음". */
	private String resultStatus() {
		List<String> statuses = jdbcTemplate.queryForList("SELECT status FROM results WHERE payment_id = ?",
			String.class, paymentPkId);
		return statuses.isEmpty() ? "없음" : String.join("·", statuses);
	}

	/** 요청 하나의 결과. 예: "성공", "거부(PaymentException: 유효한 결제 정보가 아닙니다.)" */
	private static String describe(CallResult<?> result) {
		return result.succeeded() ? "성공"
			: "거부(" + result.error().getClass().getSimpleName() + ": " + result.error().getMessage() + ")";
	}
}
