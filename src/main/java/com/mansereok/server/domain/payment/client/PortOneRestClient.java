package com.mansereok.server.domain.payment.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.global.exception.PaymentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 포트원 V2 REST API 를 호출하는 {@link PortOneClient} 구현체.
 * <p>
 * 공용 RestClient 빈과는 별도로, 연결/읽기 타임아웃이 설정된 전용 RestClient 를 만들어 사용한다.
 */
@Slf4j
@Component
public class PortOneRestClient implements PortOneClient {

	private final RestClient restClient;
	private final PortOneProperties properties;
	private final ObjectMapper objectMapper;

	@Autowired
	public PortOneRestClient(RestClient.Builder builder, PortOneProperties properties,
		ObjectMapper objectMapper) {
		// 주입받은 빌더를 변형하지 않도록 복제한 뒤, 타임아웃이 설정된 전용 RestClient 를 만든다.
		this(builder.clone().requestFactory(createRequestFactory(properties)).build(),
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
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeoutMs());
		factory.setReadTimeout(properties.readTimeoutMs());
		return factory;
	}

	@Override
	public PortOnePaymentResponse getPayment(String paymentId) {
		String rawJsonResponse;
		try {
			// API 호출하여 원시 JSON 문자열 받기
			rawJsonResponse = restClient.get()
				.uri(properties.baseUrl() + "/payments/" + paymentId)
				.header(HttpHeaders.AUTHORIZATION, authorizationHeader())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);
		} catch (Exception e) {
			log.error("PortOne API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 정보를 조회하는 중 오류가 발생했습니다.");
		}

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

		log.info("PortOne 결제 조회 완료: paymentId={}, status={}", paymentId, response.getStatus());
		return response;
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
		} catch (Exception e) {
			log.error("포트원 결제 취소 API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 취소 연동 중 오류가 발생했습니다: " + e.getMessage());
		}
	}

	private String authorizationHeader() {
		return "PortOne " + properties.secret();
	}

	/**
	 * 결제 취소 요청 본문. reason 이 null 이어도 직렬화된다. (Map.of 는 null 값에 NPE 를 던진다)
	 */
	record CancelRequest(String reason) {

	}
}
