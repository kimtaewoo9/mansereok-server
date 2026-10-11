package com.mansereok.server.domain.payment.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortoneWebhookDtoTest {

	// 모르는 필드에서 실패하는 기본 설정의 ObjectMapper 를 일부러 쓴다. DTO 가 스스로 모르는 필드를 무시해야 통과한다.
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("쓰지 않는 tx_id·merchant_uid 가 든 웹훅 본문도 ObjectMapper 설정과 상관없이 읽고, 쓰는 필드만 채운다")
	void unusedFields_areIgnoredRegardlessOfMapperSettings() throws Exception {
		// given
		String body = """
			{"tx_id": "tx_1", "payment_id": "pay_test_001", "status": "Paid", "merchant_uid": "order_1"}
			""";

		// when
		PortoneWebhookDto webhook = objectMapper.readValue(body, PortoneWebhookDto.class);

		// then
		assertThat(webhook.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(webhook.getStatus()).isEqualTo("Paid");
		assertThat(webhook.getType()).isNull();
	}
}
