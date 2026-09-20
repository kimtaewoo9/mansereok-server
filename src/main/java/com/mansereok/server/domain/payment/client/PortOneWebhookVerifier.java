package com.mansereok.server.domain.payment.client;

import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import org.springframework.stereotype.Component;

/**
 * 포트원 웹훅 서명 검증기. 설정의 웹훅 시크릿으로 {@link WebhookVerifier} 를 기동 시 한 번만 만들어 재사용한다.
 * <p>
 * 예전에는 컨트롤러가 요청마다 {@code new WebhookVerifier(secret)} 로 시크릿을 다시 디코딩했다.
 */
@Component
public class PortOneWebhookVerifier {

	private final WebhookVerifier delegate;

	public PortOneWebhookVerifier(PortOneProperties properties) {
		this.delegate = new WebhookVerifier(properties.webhook().secret());
	}

	/**
	 * 웹훅 본문과 헤더로 서명을 검증한다.
	 *
	 * @throws WebhookVerificationException 서명 불일치, 타임스탬프 허용 범위 초과 등 검증 실패
	 */
	public void verify(String body, String webhookId, String webhookSignature,
		String webhookTimestamp) throws WebhookVerificationException {
		delegate.verify(body, webhookId, webhookSignature, webhookTimestamp);
	}
}
