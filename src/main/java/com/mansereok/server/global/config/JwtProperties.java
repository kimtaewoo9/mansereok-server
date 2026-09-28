package com.mansereok.server.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
	String secret,
	Long accessTokenExpiration,
	Long refreshTokenExpiration,
	String issuer
) {

	public JwtProperties {
		if (secret == null || secret.length() < 32) {
			throw new IllegalArgumentException("JWT secret must be at least 32 characters");
		}
		if (accessTokenExpiration == null || accessTokenExpiration <= 0) {
			throw new IllegalArgumentException("Access token expiration must be positive");
		}
		if (refreshTokenExpiration == null || refreshTokenExpiration <= 0) {
			throw new IllegalArgumentException("Refresh token expiration must be positive");
		}
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalArgumentException("JWT issuer cannot be blank");
		}
	}

	/**
	 * 설정값을 로그나 오류 메시지에 찍어도 비밀키가 새지 않도록, 비밀키는 길이만 보여 준다.
	 */
	@Override
	public String toString() {
		return "JwtProperties[secret=(" + secret.length() + "자), accessTokenExpiration=" + accessTokenExpiration
			+ ", refreshTokenExpiration=" + refreshTokenExpiration + ", issuer=" + issuer + "]";
	}
}
