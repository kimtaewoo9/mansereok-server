package com.mansereok.server.domain.payment.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentPage;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 포트원 V2 REST API 를 호출하는 {@link PortOneClient} 구현체.
 * <p>
 * 공용 RestClient 빈과는 별도로, 연결/읽기 타임아웃이 설정된 전용 RestClient 를 만들어 사용한다.
 * <p>
 * 예외 분류: 네트워크 오류·타임아웃({@link ResourceAccessException})과 5xx({@link HttpServerErrorException})는
 * 재시도 가능한 일시 장애로 보고 {@link PortOneUnavailableException}(503)을, 4xx 와 응답 파싱 실패는
 * {@link PaymentException}(400)을 던진다.
 */
@Slf4j
@Component
public class PortOneRestClient implements PortOneClient {

	/** 다건 조회 한 페이지 크기. 포트원 문서에 상한이 없어 넉넉한 값을 쓴다. */
	static final int PAGE_SIZE = 100;
	/** 페이지 순회 상한. 응답의 totalCount 가 이상해도 무한 루프에 빠지지 않게 하는 안전장치다. */
	static final int MAX_PAGES = 200;
	/** 대사 대상 상태. 돈이 실제로 오간 거래만 본다. */
	private static final List<String> RECONCILED_STATUSES =
		List.of("PAID", "CANCELLED", "PARTIAL_CANCELLED");
	/** 상태가 바뀐 시각 기준으로 창을 자른다. 창 이전에 만들어져 창 안에서 취소된 거래도 잡아야 하기 때문이다. */
	private static final String TIMESTAMP_TYPE = "STATUS_CHANGED_AT";

	private final RestClient restClient;
	private final PortOneProperties properties;
	private final ObjectMapper objectMapper;

	@Autowired
	public PortOneRestClient(RestClient.Builder builder, PortOneProperties properties,
		ObjectMapper objectMapper) {
		// RestClient.Builder 빈은 prototype 스코프라 이 인스턴스는 다른 곳과 공유되지 않는다.
		// 타임아웃만 설정한 전용 RestClient 를 만든다.
		this(builder.requestFactory(createRequestFactory(properties)).build(),
			properties, objectMapper);
	}

	/**
	 * 테스트용 생성자. 이미 구성된 RestClient(예: MockRestServiceServer 를 바인딩한 빌더로 만든 것)를 그대로 사용한다.
	 */
	PortOneRestClient(RestClient restClient, PortOneProperties properties,
		ObjectMapper objectMapper) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	private static ClientHttpRequestFactory createRequestFactory(PortOneProperties properties) {
		// 클래스패스에서 감지되는 클라이언트(기본 JDK HttpClient)를 그대로 쓰고 타임아웃만 덧붙인다.
		return ClientHttpRequestFactoryBuilder.detect().build(
			ClientHttpRequestFactorySettings.defaults()
				.withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
				.withReadTimeout(Duration.ofMillis(properties.readTimeoutMs())));
	}

	@Override
	public PortOnePaymentResponse getPayment(String paymentId) {
		String rawJsonResponse;
		try {
			// API 호출하여 원시 JSON 문자열 받기
			rawJsonResponse = requestPayment(paymentId);
		} catch (ResourceAccessException | HttpServerErrorException e) {
			log.error("PortOne API 일시 장애(네트워크/타임아웃/5xx): paymentId={}", paymentId, e);
			throw new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.", e);
		} catch (RestClientException e) {
			log.error("PortOne API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 정보를 조회하는 중 오류가 발생했습니다.");
		}

		PortOnePaymentResponse response = parsePayment(rawJsonResponse, paymentId);
		log.info("PortOne 결제 조회 완료: paymentId={}, status={}", paymentId, response.getStatus());
		return response;
	}

	@Override
	public Optional<PortOnePaymentResponse> findPayment(String paymentId) {
		String rawJsonResponse;
		try {
			rawJsonResponse = requestPayment(paymentId);
		} catch (HttpClientErrorException.NotFound e) {
			log.info("PortOne 에 없는 결제입니다: paymentId={}", paymentId);
			return Optional.empty();
		} catch (ResourceAccessException | HttpServerErrorException e) {
			log.error("PortOne API 일시 장애(네트워크/타임아웃/5xx): paymentId={}", paymentId, e);
			throw new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.", e);
		} catch (RestClientException e) {
			log.error("PortOne API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 정보를 조회하는 중 오류가 발생했습니다.");
		}

		return Optional.of(parsePayment(rawJsonResponse, paymentId));
	}

	@Override
	public List<PortOnePaymentResponse> listPaymentsChangedBetween(Instant from, Instant until) {
		List<PortOnePaymentResponse> payments = new ArrayList<>();

		for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
			PortOnePaymentPage page = requestPaymentPage(from, until, pageNumber);
			if (page.getItems() != null) {
				payments.addAll(page.getItems());
			}
			if ((pageNumber + 1) * PAGE_SIZE >= page.getPage().getTotalCount()) {
				log.info("PortOne 결제 목록 조회 완료: from={}, until={}, 건수={}", from, until,
					payments.size());
				return payments;
			}
		}

		throw new PaymentException("PortOne 결제 목록이 " + MAX_PAGES + "페이지를 넘어 조회를 중단했습니다.");
	}

	@Override
	public void cancelPayment(String paymentId, String reason) {
		try {
			restClient.post()
				.uri(properties.baseUrl() + "/payments/" + paymentId + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
				.contentType(MediaType.APPLICATION_JSON)
				.body(new CancelRequest(reason))
				.retrieve()
				.toBodilessEntity();
		} catch (ResourceAccessException | HttpServerErrorException e) {
			log.error("포트원 결제 취소 API 일시 장애(네트워크/타임아웃/5xx): paymentId={}", paymentId, e);
			throw new PortOneUnavailableException("결제 취소 연동 중 일시적인 오류가 발생했습니다.", e);
		} catch (RestClientException e) {
			log.error("포트원 결제 취소 API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 취소 연동 중 오류가 발생했습니다: " + e.getMessage());
		}
	}

	private String requestPayment(String paymentId) {
		return restClient.get()
			.uri(properties.baseUrl() + "/payments/" + paymentId)
			.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(String.class);
	}

	private PortOnePaymentResponse parsePayment(String rawJsonResponse, String paymentId) {
		// 원시 응답에는 구매자 정보가 포함되므로 DEBUG 레벨로만 남긴다.
		log.debug("PortOne API 원시 응답 (paymentId: {}): {}", paymentId, rawJsonResponse);

		if (rawJsonResponse == null || rawJsonResponse.isBlank()) {
			log.error("PortOne API로부터 비어있는 응답을 받았습니다. paymentId={}", paymentId);
			throw new PaymentException("PortOne API로부터 비어있는 응답을 받았습니다.");
		}

		PortOnePaymentResponse response;
		try {
			response = objectMapper.readValue(rawJsonResponse, PortOnePaymentResponse.class);
		} catch (JsonProcessingException e) {
			log.error("PortOne API 응답 JSON 파싱 중 오류 발생. paymentId={}", paymentId, e);
			throw new PaymentException("결제 정보 응답 처리 중 오류 발생 (JSON 파싱 실패)");
		}

		if (response == null) {
			log.error("PortOne API 응답 JSON 파싱 실패. paymentId={}", paymentId);
			throw new PaymentException("PortOne API 응답 파싱에 실패했습니다.");
		}
		return response;
	}

	private PortOnePaymentPage requestPaymentPage(Instant from, Instant until, int pageNumber) {
		String requestBody = writeListRequest(from, until, pageNumber);
		String rawJsonResponse;
		try {
			// requestBody 는 JSON 을 그대로 담은 쿼리 파라미터라, URI 변수로 넘겨 인코딩되게 한다.
			rawJsonResponse = restClient.get()
				.uri(properties.baseUrl() + "/payments?requestBody={requestBody}", requestBody)
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);
		} catch (ResourceAccessException | HttpServerErrorException e) {
			log.error("PortOne 결제 목록 API 일시 장애(네트워크/타임아웃/5xx): page={}", pageNumber, e);
			throw new PortOneUnavailableException("결제 목록을 조회하는 중 일시적인 오류가 발생했습니다.", e);
		} catch (RestClientException e) {
			log.error("PortOne 결제 목록 API 호출 실패: page={}", pageNumber, e);
			throw new PaymentException("결제 목록을 조회하는 중 오류가 발생했습니다.");
		}

		return parsePaymentPage(rawJsonResponse, pageNumber);
	}

	private String writeListRequest(Instant from, Instant until, int pageNumber) {
		PaymentListRequest request = new PaymentListRequest(
			new PaymentListRequest.PageRequest(pageNumber, PAGE_SIZE),
			new PaymentListRequest.Filter(TIMESTAMP_TYPE, from.toString(), until.toString(),
				RECONCILED_STATUSES));
		try {
			return objectMapper.writeValueAsString(request);
		} catch (JsonProcessingException e) {
			log.error("PortOne 결제 목록 요청 JSON 을 만들지 못했습니다. page={}", pageNumber, e);
			throw new PaymentException("결제 목록 조회 요청을 만드는 중 오류가 발생했습니다.");
		}
	}

	private PortOnePaymentPage parsePaymentPage(String rawJsonResponse, int pageNumber) {
		if (rawJsonResponse == null || rawJsonResponse.isBlank()) {
			log.error("PortOne 결제 목록 응답이 비어있습니다. page={}", pageNumber);
			throw new PaymentException("PortOne API로부터 비어있는 응답을 받았습니다.");
		}

		PortOnePaymentPage page;
		try {
			page = objectMapper.readValue(rawJsonResponse, PortOnePaymentPage.class);
		} catch (JsonProcessingException e) {
			log.error("PortOne 결제 목록 응답 JSON 파싱 중 오류 발생. page={}", pageNumber, e);
			throw new PaymentException("결제 목록 응답 처리 중 오류 발생 (JSON 파싱 실패)");
		}

		if (page == null || page.getPage() == null) {
			log.error("PortOne 결제 목록 응답에 페이지 정보가 없습니다. page={}", pageNumber);
			throw new PaymentException("PortOne 결제 목록 응답에 페이지 정보가 없습니다.");
		}
		return page;
	}

	private String authorizationHeader() {
		return "PortOne " + properties.secret();
	}

	/**
	 * 결제 취소 요청 본문. reason 이 null 이어도 직렬화된다. (Map.of 는 null 값에 NPE 를 던진다)
	 */
	record CancelRequest(String reason) {

	}

	/**
	 * 결제 다건 조회 요청 본문. 포트원 V2 는 이 JSON 을 GET 쿼리 파라미터 requestBody 에 담아 받는다.
	 */
	record PaymentListRequest(PageRequest page, Filter filter) {

		record PageRequest(int number, int size) {

		}

		record Filter(String timestampType, String from, String until, List<String> status) {

		}
	}
}
