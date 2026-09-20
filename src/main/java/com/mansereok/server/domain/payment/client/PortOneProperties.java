package com.mansereok.server.domain.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * portone.api.* 설정 바인딩.
 * <p>
 * secret 과 webhook.secret 은 필수이고 나머지는 기본값을 가진다. 웹훅 시크릿이 yml 에 portone.api.webhook.secret 으로
 * 정의돼 있으므로 중첩 record {@link Webhook} 으로 같은 위치에서 바인딩한다. (예전 PaymentController 의
 * {@code @Value("${portone.webhook.secret}")} 는 yml 키와 어긋나 환경변수 relaxed binding 에만 기대고 있었다)
 */
@ConfigurationProperties(prefix = "portone.api")
public record PortOneProperties(
	String secret,
	String baseUrl,
	Integer connectTimeoutMs,
	Integer readTimeoutMs,
	Webhook webhook
) {

	public static final String DEFAULT_BASE_URL = "https://api.portone.io";
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
	public static final int DEFAULT_READ_TIMEOUT_MS = 10000;

	public PortOneProperties {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("portone.api.secret 설정이 필요합니다.");
		}
		if (webhook == null || webhook.secret() == null || webhook.secret().isBlank()) {
			throw new IllegalArgumentException("portone.api.webhook.secret 설정이 필요합니다.");
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = DEFAULT_BASE_URL;
		}
		if (connectTimeoutMs == null) {
			connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
		}
		if (readTimeoutMs == null) {
			readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
		}
	}

	/**
	 * portone.api.webhook.* 바인딩. secret 은 포트원 콘솔이 발급한 웹훅 서명 시크릿이다.
	 */
	public record Webhook(String secret) {

	}
}
