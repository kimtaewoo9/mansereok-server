package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.PaymentMySqlTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * open-in-view 를 끈 설정에서, 환불 API 가 포트원 취소를 기다리는 동안 DB 커넥션을 풀에 돌려 두는지 실제 MySQL 로 확인한다.
 *
 * <p>환불(PaymentRefundService)은 트랜잭션 A 를 커밋한 뒤 트랜잭션 밖에서 포트원 취소를 부른다. open-in-view 가 켜져 있으면 웹
 * 요청은 A 에서 잡은 커넥션을 요청이 끝날 때까지 쥐어, 포트원을 기다리는 동안에도 풀의 한 자리를 차지한다. 이 설정은 웹 요청에만
 * 걸리므로 서비스를 직접 부르지 않고, MockMvc 로 로그인(JWT)과 CSRF 검사를 거치는 실제 요청을 보낸다.
 *
 * <p>포트원 취소 스텁 안에서 풀이 빌려준 커넥션 수를 읽는다. 같은 자리에서 open-in-view 를 켜면 1 이 나온다. 이 스택의 운영 설정은
 * 아직 기본값(켜짐)이고, 끄는 일은 develop 스택에서 한다.
 *
 * <p>설정이 달라 스프링이 이 테스트용 컨텍스트를 따로 띄운다. 로컬 MySQL 커넥션을 적게 쓰도록 풀을 2개로 줄인다(테스트 준비의 조회와
 * 요청 하나면 충분하다).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"spring.jpa.open-in-view=false",
	"spring.datasource.hikari.maximum-pool-size=2"
})
class RefundConnectionHoldMySqlTest extends PaymentMySqlTest {

	private static final int PRICE = 10000;
	// 일반 사주 상품. 초기 결과가 results 표에 생긴다.
	private static final Long PRODUCT_ID = 3L;
	private static final String REFUND_REASON = "단순 변심";
	// CookieCsrfTokenRepository 는 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더가 같은지만 본다.
	private static final String CSRF_TOKEN = "refund-connection-hold-csrf-token";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JwtUtil jwtUtil;
	@Autowired
	private HikariDataSource dataSource;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PaymentRepository paymentRepository;
	@Autowired
	private ResultRepository resultRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "connection_hold_" + runId;
	private final String merchantUid = "order_connection_hold_" + runId;
	private final String impUid = "pay_connection_hold_" + runId;

	private Long userId;

	@BeforeEach
	void createPaidPaymentBeforeInterpretation() {
		userId = userRepository.save(User.create(username, "커넥션", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		Long orderId = orderRepository.save(Order.create(merchantUid, userId, PRODUCT_ID, PRICE, PRICE, null,
			null, OrderStatus.PAID, "커넥션", username + "@example.com")).getId();
		Long paymentPkId = paymentRepository.save(Payment.create(impUid, merchantUid, (long) PRICE,
			PaymentStatus.PAID, orderId, userId, PRODUCT_ID)).getId();
		resultRepository.save(Result.createInitial(userId, paymentPkId, "일반 사주 상품"));
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		userRepository.deleteById(userId);
	}

	@Test
	@DisplayName("open-in-view 가 꺼져 있으면 환불 API 는 포트원 취소를 기다리는 동안 DB 커넥션을 하나도 쥐지 않는다")
	void refundHoldsNoConnectionWhileWaitingForPortOneCancel() throws Exception {
		// given: 포트원 취소 호출 안에서 풀이 빌려준 커넥션 수를 읽는다
		AtomicReference<Integer> activeConnectionsDuringCancel = new AtomicReference<>();
		willAnswer(invocation -> {
			activeConnectionsDuringCancel.set(connectionPool().getActiveConnections());
			return null;
		}).given(portOneClient).cancelPayment(impUid, REFUND_REASON);
		// 컨텍스트가 뜰 때 한 번 도는 주문 만료 스케줄러 같은 다른 스레드가 커넥션을 다 돌려준 뒤에 요청을 보낸다
		await().atMost(Duration.ofSeconds(10)).until(() -> connectionPool().getActiveConnections() == 0);

		// when
		MockHttpServletResponse response = mockMvc.perform(cancelRequestFromLoggedInUser()).andReturn().getResponse();

		// then
		assertThat(response.getStatus()).as("환불 응답 상태").isEqualTo(200);
		assertThat(activeConnectionsDuringCancel.get()).as("포트원 취소를 기다리는 동안 풀이 빌려준 커넥션 수").isZero();
	}

	private HikariPoolMXBean connectionPool() {
		return dataSource.getHikariPoolMXBean();
	}

	/**
	 * 로그인한 사용자가 브라우저에서 보내는 환불 요청. 액세스 토큰과 CSRF 쿠키·헤더를 함께 싣는다.
	 */
	private MockHttpServletRequestBuilder cancelRequestFromLoggedInUser() {
		String accessToken = jwtUtil.generateAccessToken(username, Map.of("role", "ROLE_USER"));
		return post("/api/payment/cancel")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN))
			.header("X-XSRF-TOKEN", CSRF_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"paymentId": "%s", "reason": "%s"}
				""".formatted(impUid, REFUND_REASON));
	}
}
