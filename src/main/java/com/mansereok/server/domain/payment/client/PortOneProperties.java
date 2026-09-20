package com.mansereok.server.domain.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * portone.api.* 설정 바인딩.
 * <p>
 * secret 은 필수이고 나머지는 기본값을 가진다. 같은 prefix 아래에 이 record 가 모르는 하위 키(예: webhook)가
 * 있어도 Spring Boot 기본 동작(ignoreUnknownFields = true)에 따라 무시된다.
 */
@ConfigurationProperties(prefix = "portone.api")
public record PortOneProperties(
	String secret,
	String baseUrl,
	Integer connectTimeoutMs,
	Integer readTimeoutMs
) {

	public static final String DEFAULT_BASE_URL = "https://api.portone.io";
	public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
	public static final int DEFAULT_READ_TIMEOUT_MS = 10000;

	public PortOneProperties {
		if (secret == null) {
			throw new IllegalArgumentException("portone.api.secret 설정이 필요합니다.");
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
}
