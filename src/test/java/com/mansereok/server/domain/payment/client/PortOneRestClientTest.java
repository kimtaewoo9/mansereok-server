package com.mansereok.server.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PortOneRestClientTest {

	private static final String SECRET = "test-portone-secret";
	private static final String BASE_URL = "https://api.portone.io";
	private static final PortOneProperties.Webhook WEBHOOK =
		new PortOneProperties.Webhook("test-webhook-secret");
	private static final String PAYMENT_ID = "pay_test_001";
	private static final String PAYMENT_URL = BASE_URL + "/payments/" + PAYMENT_ID;
	private static final String CANCEL_URL = PAYMENT_URL + "/cancel";

	private MockRestServiceServer server;
	private PortOneRestClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();

		PortOneProperties properties = new PortOneProperties(SECRET, BASE_URL, null, null, WEBHOOK);
		// Spring Boot 가 자동 구성하는 ObjectMapper 와 같이 FAIL_ON_UNKNOWN_PROPERTIES 가 꺼진 매퍼를 쓴다.
		// (PortOnePaymentResponse.Amount 에는 ignoreUnknown 설정이 없어 plain ObjectMapper 로는 실제 응답 파싱이 실패한다)
		client = new PortOneRestClient(builder.build(), properties,
			Jackson2ObjectMapperBuilder.json().build());
	}

	@Test
	@DisplayName("운영용 생성자는 타임아웃이 설정된 전용 RestClient 를 예외 없이 만든다")
	void productionConstructor_buildsClientWithTimeouts() {
		PortOneProperties properties = new PortOneProperties(SECRET, BASE_URL, 1500, 2500, WEBHOOK);

		assertThatCode(() -> new PortOneRestClient(RestClient.builder(), properties,
			Jackson2ObjectMapperBuilder.json().build()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("결제 조회는 GET /payments/{id} 에 인증·Accept 헤더를 보내고 status, amount.total, customData 를 파싱한다")
	void getPayment_sendsExpectedRequestAndParsesResponse() {
		// given
		String body = """
			{
			  "id": "pay_test_001",
			  "status": "PAID",
			  "amount": {"total": 10000, "paid": 10000},
			  "customData": "{\\"merchantUid\\":\\"order_1\\"}",
			  "unknownField": "ignored"
			}
			""";
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne " + SECRET))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		// when
		PortOnePaymentResponse response = client.getPayment(PAYMENT_ID);

		// then
		assertThat(response.getId()).isEqualTo(PAYMENT_ID);
		assertThat(response.getStatus()).isEqualTo("PAID");
		assertThat(response.getAmount().getTotal()).isEqualTo(10000L);
		assertThat(response.getCustomData()).isEqualTo("{\"merchantUid\":\"order_1\"}");
		server.verify();
	}

	@Test
	@DisplayName("응답 본문이 비어있으면 '비어있는 응답' 메시지의 PaymentException 을 던진다 (다시 감싸지 않는다)")
	void getPayment_emptyBody_throwsEmptyResponseMessage() {
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess());

		assertThatThrownBy(() -> client.getPayment(PAYMENT_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage("PortOne API로부터 비어있는 응답을 받았습니다.");
		server.verify();
	}

	@Test
	@DisplayName("응답이 깨진 JSON 이면 'JSON 파싱 실패' 메시지의 PaymentException 을 던진다")
	void getPayment_malformedJson_throwsParseFailureMessage() {
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"status\": ", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.getPayment(PAYMENT_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보 응답 처리 중 오류 발생 (JSON 파싱 실패)");
		server.verify();
	}

	@Test
	@DisplayName("응답이 500 이면 재시도 가능한 PortOneUnavailableException 을 던진다")
	void getPayment_serverError_throwsPortOneUnavailable() {
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withServerError());

		assertThatThrownBy(() -> client.getPayment(PAYMENT_ID))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasMessage("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.");
		server.verify();
	}

	@Test
	@DisplayName("네트워크 오류나 타임아웃(ResourceAccessException)이면 PortOneUnavailableException 을 던진다")
	void getPayment_timeout_throwsPortOneUnavailable() {
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.getPayment(PAYMENT_ID))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
		server.verify();
	}

	@Test
	@DisplayName("응답이 4xx 이면 기존처럼 '조회 중 오류' 메시지의 PaymentException 을 던진다")
	void getPayment_clientError_throwsPaymentException() {
		server.expect(requestTo(PAYMENT_URL))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withBadRequest());

		assertThatThrownBy(() -> client.getPayment(PAYMENT_ID))
			.isInstanceOf(PaymentException.class)
			.isNotInstanceOf(PortOneUnavailableException.class)
			.hasMessage("결제 정보를 조회하는 중 오류가 발생했습니다.");
		server.verify();
	}

	@Test
	@DisplayName("결제 취소는 POST /payments/{id}/cancel 에 인증 헤더와 JSON 본문의 reason 을 보낸다")
	void cancelPayment_sendsReasonAsJsonBody() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne " + SECRET))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.reason").value("단순 변심"))
			.andRespond(withSuccess());

		client.cancelPayment(PAYMENT_ID, "단순 변심");

		server.verify();
	}

	@Test
	@DisplayName("reason 이 null 이어도 NPE 없이 취소 요청이 나간다")
	void cancelPayment_nullReason_stillSendsRequest() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne " + SECRET))
			.andExpect(content().json("{\"reason\":null}"))
			.andRespond(withSuccess());

		assertThatCode(() -> client.cancelPayment(PAYMENT_ID, null))
			.doesNotThrowAnyException();
		server.verify();
	}

	@Test
	@DisplayName("취소 응답이 5xx 이면 PortOneUnavailableException 을 던진다")
	void cancelPayment_serverError_throwsPortOneUnavailable() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withServerError());

		assertThatThrownBy(() -> client.cancelPayment(PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasMessage("결제 취소 연동 중 일시적인 오류가 발생했습니다.");
		server.verify();
	}

	@Test
	@DisplayName("취소 응답이 4xx 이면 기존 메시지 형식의 PaymentException 을 던진다")
	void cancelPayment_clientError_throwsPaymentException() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withBadRequest());

		assertThatThrownBy(() -> client.cancelPayment(PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PaymentException.class)
			.hasMessageStartingWith("결제 취소 연동 중 오류가 발생했습니다: ");
		server.verify();
	}
}
