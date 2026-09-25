package com.mansereok.server.global.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브라우저에서 이 API 를 부를 수 있는 프론트엔드 출처 목록(app.cors.allowed-origins). 환경마다 yml 에 따로 적는다.
 *
 * <p>쿠키를 싣는 요청을 허용하므로 "*" 나 "https://*.vercel.app" 같은 와일드카드는 받지 않는다. 누구나 하위 도메인을 만들 수
 * 있는 주소가 섞이면 그 사이트의 페이지가 사용자의 쿠키로 API 를 부르고 응답을 읽을 수 있다. 출처는 브라우저가 보내는 Origin
 * 헤더와 글자 그대로 비교하므로 "https://www.namedsaju.com" 처럼 끝에 / 없이 적는다.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			throw new IllegalArgumentException("app.cors.allowed-origins 에 프론트엔드 출처를 하나 이상 적어야 합니다.");
		}
		for (String origin : allowedOrigins) {
			if (origin == null || origin.isBlank() || origin.contains("*") || origin.endsWith("/")) {
				throw new IllegalArgumentException(
					"허용 출처는 와일드카드나 끝의 / 없이 정확한 주소로 적어야 합니다: " + origin);
			}
		}
		allowedOrigins = List.copyOf(allowedOrigins);
	}
}
