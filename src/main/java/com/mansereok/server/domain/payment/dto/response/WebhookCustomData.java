package com.mansereok.server.domain.payment.dto.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.global.exception.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 포트원 결제 조회 응답의 customData 에 프론트가 실어 보낸 값.
 *
 * <p>customData 는 JSON 문자열이며 주문 조회 키인 merchantUid 를 담는다. 형식이 어긋나면 재전송으로
 * 해결되지 않는 최종 실패이므로 {@link PaymentException}(400)을 던진다.
 *
 * @param merchantUid 주문 번호. 비어 있지 않음이 보장된다.
 */
public record WebhookCustomData(String merchantUid) {

	private static final Logger log = LoggerFactory.getLogger(WebhookCustomData.class);

	private static final String MERCHANT_UID_FIELD = "merchantUid";

	/**
	 * customData 문자열을 파싱하고 merchantUid 를 검증한다.
	 *
	 * @throws PaymentException customData 가 비어 있거나, JSON 이 아니거나, merchantUid 가 없거나 빈 값인 경우
	 */
	public static WebhookCustomData from(String customData, ObjectMapper objectMapper) {
		if (customData == null || customData.isBlank()) {
			log.error("PortOne API 응답에 customData가 비어있습니다!");
			throw new PaymentException("결제 API 응답에서 customData를 찾을 수 없어 주문 번호를 알 수 없습니다.");
		}

		JsonNode json;
		try {
			json = objectMapper.readTree(customData);
		} catch (JsonProcessingException e) {
			log.error("customData 문자열 JSON 파싱 실패! customData: {}", customData, e);
			throw new PaymentException("결제 API 응답의 customData 파싱 중 오류 발생");
		}

		JsonNode merchantUidNode = json.get(MERCHANT_UID_FIELD);
		String merchantUid = merchantUidNode == null || merchantUidNode.isNull()
			? null : merchantUidNode.asText();
		if (merchantUid == null || merchantUid.isBlank()) {
			log.error("customData JSON 안에 'merchantUid' 필드가 없거나 비어있습니다! customData: {}. "
				+ "프론트엔드 customData 형식을 확인하세요. 예: { \"merchantUid\": \"order_...\", ... }",
				customData);
			throw new PaymentException(
				"결제 API 응답의 customData에서 유효한 주문 번호(merchantUid)를 추출할 수 없습니다.");
		}

		return new WebhookCustomData(merchantUid);
	}
}
