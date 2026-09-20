package com.mansereok.server.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.payment.client.PortOneProperties.Webhook;
import io.portone.sdk.server.errors.WebhookVerificationException;
import java.util.Base64;
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
}
