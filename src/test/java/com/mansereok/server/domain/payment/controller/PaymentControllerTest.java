package com.mansereok.server.domain.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.client.PortOneWebhookVerifier;
import com.mansereok.server.domain.payment.service.PaymentConfirmService;
import com.mansereok.server.domain.payment.service.PaymentOrderService;
import com.mansereok.server.domain.payment.service.PaymentQueryService;
import com.mansereok.server.domain.payment.service.PaymentRefundService;
import com.mansereok.server.domain.payment.service.PaymentWebhookService;
import com.mansereok.server.support.fixture.TestOrders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 결제 API 가 요청을 어떻게 받고 어떤 응답을 돌려주는지 웹 계층에서 고정한다.
 *
 * <ul>
 *   <li>잘못된 요청(경로 변수 형식, 깨진 본문, 메서드, 검증 실패)은 500 이 아니라 4xx 로 답한다. 요청 DTO 검증은 컨트롤러
 *   인자의 {@code @Valid} 가 있어야 돌므로, 본문을 받는 네 주소(주문 생성, 0원 받기, 결제 완료, 환불)마다 검증 실패를 확인한다.</li>
 *   <li>웹훅은 서명 검증을 통과한 요청만 서비스로 넘긴다. /api/payment/webhook 은 로그인 없이 열려 있어 서명 검증이 이 주소의
 *   유일한 방어다.</li>
 *   <li>성공 응답의 형식(주문 생성은 OrderCreateResponse JSON, 환불은 평문 안내 문구)은 프론트와의 계약이다.</li>
 * </ul>
 *
 * <p>스프링 MVC 설정과 운영의 예외 처리기(GlobalExceptionHandler, RequestErrorExceptionHandler)는 그대로 띄운다. 서비스는
 * {@link MockitoBean} 으로 바꾸고, 웹훅 서명 검증기는 테스트용 시크릿으로 만든 진짜를 쓴다. 로그인·CSRF 규칙은 이 테스트가
 * 보는 대상이 아니라 보안 필터를 끄고, JwtAuthenticationFilter 가 하던 인증 정보 넣기(요청자 이름)를 테스트가 대신한다.
 */
@WebMvcTest(
	controllers = PaymentController.class,
	// 보안 필터를 끄고 띄우므로 JWT 필터 빈도 만들지 않는다. 만들면 JwtUtil 까지 필요해진다.
	excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
		classes = JwtAuthenticationFilter.class),
	properties = {
		"app.jwt.secret=payment-controller-test-jwt-secret-0123456789",
		"portone.api.secret=payment-controller-test-api-secret",
		"portone.api.webhook.secret=" + PaymentControllerTest.WEBHOOK_SECRET,
		"spring.security.oauth2.client.registration.google.client-id=payment-controller-test",
		"spring.security.oauth2.client.registration.google.client-secret=payment-controller-test"
	})
@AutoConfigureMockMvc(addFilters = false)
@Import(PortOneWebhookVerifier.class)
class PaymentControllerTest {

	/** 포트원 콘솔이 발급하는 형식(whsec_ + base64). base64 를 풀면 32바이트 "payment-controller-test-webhook!" 이다. */
	static final String WEBHOOK_SECRET = "whsec_cGF5bWVudC1jb250cm9sbGVyLXRlc3Qtd2ViaG9vayE=";

	private static final String USERNAME = "buyer";
	private static final String WEBHOOK_ID = "msg_payment_controller_test";
	// 서명은 본문 형식과 관계없이 받은 원문 그대로 검증한다.
	// 본문은 PaymentWebhookService 가 읽는 형식(tx_id, payment_id, status)을 쓴다.
	private static final String WEBHOOK_BODY = "{\"tx_id\":\"tx_1\",\"payment_id\":\"pay_1\",\"status\":\"Paid\"}";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private PaymentOrderService paymentOrderService;
	@MockitoBean
	private PaymentWebhookService paymentWebhookService;
	@MockitoBean
	private PaymentConfirmService paymentConfirmService;
	@MockitoBean
	private PaymentQueryService paymentQueryService;
	@MockitoBean
	private PaymentRefundService paymentRefundService;

	@BeforeEach
	void signIn() {
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(USERNAME, null, List.of()));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("요청 형식이 잘못되면")
	class WhenRequestIsMalformed {

		@ParameterizedTest(name = "[{index}] GET {0} → {1}")
		@CsvSource(textBlock = """
			# 요청 경로,                  응답 메시지
			/api/payment/orders/abc,      요청 파라미터 'orderId' 의 값 'abc' 이 올바른 형식이 아닙니다.
			# 주소의 이름(paymentId)으로 알려준다. 받는 값은 결제 테이블의 PK 다.
			/api/orders/by-payment/abc,   요청 파라미터 'paymentId' 의 값 'abc' 이 올바른 형식이 아닙니다.
			""")
		@DisplayName("숫자 자리의 경로 변수가 숫자가 아니면 400 INVALID_PARAMETER 를 돌려준다")
		void pathVariableIsNotNumber(String path, String expectedMessage) throws Exception {
			// when & then
			mockMvc.perform(get(path))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message").value(expectedMessage));
		}

		@ParameterizedTest(name = "[{index}] 본문 \"{0}\"")
		@ValueSource(strings = {"{", ""})
		@DisplayName("결제 완료 요청의 본문이 깨졌거나 비었으면 400 INVALID_REQUEST_BODY 를 돌려준다")
		void completeBodyIsUnreadable(String body) throws Exception {
			// when & then
			mockMvc.perform(post("/api/payment/complete").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요."));
		}

		@ParameterizedTest(name = "[{index}] POST {0} 의 {2} → {3}")
		@CsvSource(delimiter = '|', textBlock = """
			# 요청 경로              | 요청 본문                                     | 필드          | 필드 메시지
			/api/payment/complete    | {"paymentId": " ", "merchantUid": "order_1"}  | paymentId     | 결제 ID 형식이 올바르지 않습니다.
			/api/payment/orders      | {"subCategoryId": null}                       | subCategoryId | 상품 ID는 필수입니다.
			/api/payment/redeem-free | {"subCategoryId": null}                       | subCategoryId | 상품 ID는 필수입니다.
			# 환불은 돈이 나가는 경로라 두 필드를 모두 본다.
			/api/payment/cancel      | {"paymentId": "pay_1", "reason": " "}         | reason        | 환불 사유는 필수입니다.
			/api/payment/cancel      | {"paymentId": " ", "reason": "단순 변심"}     | paymentId     | 결제 ID 형식이 올바르지 않습니다.
			""")
		@DisplayName("요청 본문이 DTO 검증에 걸리면 400 VALIDATION_ERROR 와 그 필드의 메시지를 돌려준다")
		void requestBodyFailsValidation(String path, String body, String field, String expectedMessage)
			throws Exception {
			// when & then
			mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.%s", field).value(expectedMessage));
		}

		@Test
		@DisplayName("환불 주소에 GET 을 보내면 405 와 POST 만 받는다는 Allow 헤더를 돌려준다")
		void getOnCancel() throws Exception {
			// when & then
			mockMvc.perform(get("/api/payment/cancel"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(HttpHeaders.ALLOW, "POST"))
				.andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
		}
	}

	@Nested
	@DisplayName("웹훅은")
	class Webhook {

		@Test
		@DisplayName("테스트 시크릿으로 올바르게 서명한 요청이면 200 이고 받은 본문 그대로 웹훅 처리를 한 번 부른다")
		void validSignature() throws Exception {
			// given
			String timestamp = nowEpochSeconds();

			// when
			mockMvc.perform(webhookRequest(WEBHOOK_BODY)
					.header("webhook-timestamp", timestamp)
					.header("webhook-signature", sign(WEBHOOK_ID, timestamp, WEBHOOK_BODY)))
				.andExpect(status().isOk());

			// then
			then(paymentWebhookService).should().processWebhook(WEBHOOK_BODY);
		}

		@Test
		@DisplayName("서명이 맞지 않으면 401 WEBHOOK_SIGNATURE_INVALID 를 돌려주고 웹훅 처리를 부르지 않는다")
		void invalidSignature() throws Exception {
			// when & then
			mockMvc.perform(webhookRequest(WEBHOOK_BODY)
					.header("webhook-timestamp", nowEpochSeconds())
					.header("webhook-signature", "v1,aW52YWxpZC1zaWduYXR1cmU="))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));

			// then: 서비스까지 가지 않는다
			then(paymentWebhookService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("다른 요청에 붙었던 서명을 본문만 바꿔 보내면 401 을 돌려주고 웹훅 처리를 부르지 않는다")
		void signatureOfAnotherBody() throws Exception {
			// given
			String timestamp = nowEpochSeconds();
			String forgedBody = WEBHOOK_BODY.replace("pay_1", "pay_2");

			// when
			mockMvc.perform(webhookRequest(forgedBody)
					.header("webhook-timestamp", timestamp)
					.header("webhook-signature", sign(WEBHOOK_ID, timestamp, WEBHOOK_BODY)))
				.andExpect(status().isUnauthorized());

			// then
			then(paymentWebhookService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("5분이 지난 타임스탬프로 올바르게 서명한 요청이면 401 을 돌려주고 웹훅 처리를 부르지 않는다")
		void staleTimestamp() throws Exception {
			// given: 예전에 가로챈 웹훅을 다시 보내는 경우. 포트원 SDK 는 지금과 5분 넘게 차이 나는 타임스탬프를 거부한다.
			// 경계(300초)에 붙이면 요청이 도는 동안 결과가 바뀔 수 있어 600초 전으로 여유를 둔다.
			String staleTimestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

			// when
			mockMvc.perform(webhookRequest(WEBHOOK_BODY)
					.header("webhook-timestamp", staleTimestamp)
					.header("webhook-signature", sign(WEBHOOK_ID, staleTimestamp, WEBHOOK_BODY)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));

			// then
			then(paymentWebhookService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("webhook-signature 헤더가 없으면 400 MISSING_HEADER 와 빠진 헤더 이름을 돌려주고 웹훅 처리를 부르지 않는다")
		void missingSignatureHeader() throws Exception {
			// when & then
			mockMvc.perform(webhookRequest(WEBHOOK_BODY)
					.header("webhook-timestamp", nowEpochSeconds()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"))
				.andExpect(jsonPath("$.message").value("webhook-signature 헤더가 필요합니다."));

			// then: 서비스까지 가지 않는다
			then(paymentWebhookService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("본문이 비어 있으면 400 INVALID_REQUEST_BODY 를 돌려주고 웹훅 처리를 부르지 않는다")
		void emptyBody() throws Exception {
			// given
			String timestamp = nowEpochSeconds();

			// when
			mockMvc.perform(webhookRequest("")
					.header("webhook-timestamp", timestamp)
					.header("webhook-signature", sign(WEBHOOK_ID, timestamp, "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"));

			// then
			then(paymentWebhookService).shouldHaveNoInteractions();
		}

		private MockHttpServletRequestBuilder webhookRequest(String body) {
			return post("/api/payment/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("webhook-id", WEBHOOK_ID);
		}
	}

	@Nested
	@DisplayName("성공 응답은")
	class SuccessResponse {

		@Test
		@DisplayName("주문 생성은 OrderCreateResponse 의 네 필드만 담은 JSON 이다")
		void createOrderReturnsOrderCreateResponse() throws Exception {
			// given
			OrderCreateResponse created = new OrderCreateResponse(7L, "order_7", 10000, "인생 총운");
			given(paymentOrderService.createOrder(USERNAME, orderCreateRequest(19L))).willReturn(created);

			// when
			MvcResult result = mockMvc.perform(post("/api/payment/orders").contentType(MediaType.APPLICATION_JSON)
					.content("{\"subCategoryId\": 19}"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andReturn();

			// then
			assertThat(readStrictly(result)).isEqualTo(created);
		}

		@Test
		@DisplayName("0원 상품 받기도 주문 생성과 같은 OrderCreateResponse JSON 이다")
		void redeemFreeProductReturnsOrderCreateResponse() throws Exception {
			// given
			OrderCreateResponse redeemed = new OrderCreateResponse(8L, "order_8", 0, "무료 상품");
			given(paymentOrderService.redeemFreeProduct(USERNAME, orderCreateRequest(20L))).willReturn(redeemed);

			// when
			MvcResult result = mockMvc.perform(post("/api/payment/redeem-free")
					.contentType(MediaType.APPLICATION_JSON).content("{\"subCategoryId\": 20}"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andReturn();

			// then
			assertThat(readStrictly(result)).isEqualTo(redeemed);
		}

		@Test
		@DisplayName("환불은 JSON 이 아니라 text/plain 안내 문구이고 요청자·결제 ID·사유를 그대로 넘겨 환불한다")
		void cancelReturnsPlainText() throws Exception {
			// when & then
			mockMvc.perform(post("/api/payment/cancel").contentType(MediaType.APPLICATION_JSON)
					.content("{\"paymentId\": \"pay_1\", \"reason\": \"단순 변심\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
				.andExpect(content().string("환불이 정상적으로 처리되었습니다."));

			// then: 포트원 취소로 이어지는 환불 명령을 받은 값 그대로 한 번 부른다
			then(paymentRefundService).should().cancel(USERNAME, "pay_1", "단순 변심");
		}

		@Test
		@DisplayName("결제 PK 로 주문을 조회하면 주소의 숫자를 결제 PK 로 넘겨 찾은 주문을 돌려준다")
		void orderByPaymentPkId() throws Exception {
			// given
			Order order = TestOrders.order().merchantUid("order_15").subCategoryId(19L).paid();
			given(paymentQueryService.getOwnedOrderByPaymentPkId(15L, USERNAME)).willReturn(order);

			// when & then
			mockMvc.perform(get("/api/orders/by-payment/15"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.merchantUid").value("order_15"));
		}
	}

	/**
	 * 응답 본문을 OrderCreateResponse 로 읽는다. 모르는 필드가 있으면 실패하게 해, 엔티티처럼 더 많은 필드를 내보내는 변경을 잡는다.
	 */
	private OrderCreateResponse readStrictly(MvcResult result) throws Exception {
		return objectMapper.readerFor(OrderCreateResponse.class)
			.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static OrderCreateRequest orderCreateRequest(Long subCategoryId) {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		return request;
	}

	/**
	 * 포트원 SDK 는 웹훅 타임스탬프를 시스템 시계와 비교해 오래된 요청을 거부한다. SDK 에 시계를 넣을 수 없어 지금 시각을 쓴다.
	 */
	private static String nowEpochSeconds() {
		return String.valueOf(Instant.now().getEpochSecond());
	}

	/**
	 * 포트원 서명 방식으로 서명한다. whsec_ 를 떼고 base64 로 푼 시크릿으로 "id.timestamp.body" 를 HMAC-SHA256 한다.
	 */
	private static String sign(String webhookId, String timestamp, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(
			Base64.getDecoder().decode(WEBHOOK_SECRET.substring("whsec_".length())), "HmacSHA256"));
		byte[] signature = mac.doFinal((webhookId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
		return "v1," + Base64.getEncoder().encodeToString(signature);
	}
}
