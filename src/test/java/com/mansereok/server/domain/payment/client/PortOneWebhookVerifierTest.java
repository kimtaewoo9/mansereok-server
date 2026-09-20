package com.mansereok.server.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.payment.client.PortOneProperties.Webhook;
import io.portone.sdk.server.errors.WebhookVerificationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortOneWebhookVerifierTest {

	/** 포트원 콘솔이 발급하는 형식(whsec_ 접두어 + base64) 의 임의 시크릿 */
	private static final String SECRET =
		"whsec_" + Base64.getEncoder().encodeToString(new byte[32]);

	@Test
	@DisplayName("설정의 webhook.secret 으로 만든 검증기가 잘못된 서명을 거부한다")
	void verify_rejectsInvalidSignature() {
		PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(
			new PortOneProperties("api-secret", null, null, null, new Webhook(SECRET)));

		assertThatThrownBy(() -> verifier.verify("{}", "msg_1",
			"v1,invalid", String.valueOf(System.currentTimeMillis() / 1000)))
			.isInstanceOf(WebhookVerificationException.class);
	}

	@Test
	@DisplayName("설정의 webhook.secret 으로 서명한 요청은 예외 없이 통과한다")
	void verify_acceptsValidSignature() throws Exception {
		// 포트원 SDK 가 역직렬화할 수 있는 형식(type/timestamp/data) 의 본문
		String body = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2025-01-01T00:00:00Z\","
			+ "\"data\":{\"paymentId\":\"pay_1\",\"storeId\":\"store_1\",\"transactionId\":\"tx_1\"}}";
		String id = "msg_1";
		String ts = String.valueOf(System.currentTimeMillis() / 1000);

		// 포트원 서명 방식: base64 디코딩한 시크릿(whsec_ 제거) 으로 "id.timestamp.body" 를 HMAC-SHA256
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(
			Base64.getDecoder().decode(SECRET.substring("whsec_".length())), "HmacSHA256"));
		String signature = "v1," + Base64.getEncoder().encodeToString(
			mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));

		PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(
			new PortOneProperties("api-secret", null, null, null, new Webhook(SECRET)));

		assertThatCode(() -> verifier.verify(body, id, signature, ts)).doesNotThrowAnyException();
	}
}
