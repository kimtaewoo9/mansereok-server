package com.mansereok.server.domain.payment.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.global.exception.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookCustomDataTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("정상 customData JSON 에서 merchantUid 를 꺼내고 다른 필드는 무시한다")
	void from_validJson_extractsMerchantUid() {
		WebhookCustomData data = WebhookCustomData.from(
			"{\"merchantUid\":\"order_123_abcd\",\"subCategoryId\":1}", objectMapper);

		assertThat(data.merchantUid()).isEqualTo("order_123_abcd");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("customData 가 null 이거나 빈 값이면 'customData 를 찾을 수 없어' PaymentException 이 난다")
	void from_blank_throwsMissingCustomData(String customData) {
		assertThatThrownBy(() -> WebhookCustomData.from(customData, objectMapper))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 API 응답에서 customData를 찾을 수 없어 주문 번호를 알 수 없습니다.");
	}

	@Test
	@DisplayName("customData 가 JSON 이 아니면 'customData 파싱 중 오류' PaymentException 이 난다")
	void from_notJson_throwsParseFailure() {
		assertThatThrownBy(() -> WebhookCustomData.from("not-json", objectMapper))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 API 응답의 customData 파싱 중 오류 발생");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{}",
		"{\"merchant_uid\":\"order_1\"}",
		"{\"merchantUid\":\"\"}",
		"{\"merchantUid\":\"   \"}",
		"{\"merchantUid\":null}",
		"\"just a string\""
	})
	@DisplayName("merchantUid 필드가 없거나 빈 값이면 '유효한 주문 번호를 추출할 수 없습니다' PaymentException 이 난다")
	void from_missingMerchantUid_throws(String customData) {
		assertThatThrownBy(() -> WebhookCustomData.from(customData, objectMapper))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 API 응답의 customData에서 유효한 주문 번호(merchantUid)를 추출할 수 없습니다.");
	}
}
