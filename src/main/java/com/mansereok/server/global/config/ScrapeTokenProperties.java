package com.mansereok.server.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prometheus 가 지표(/actuator/prometheus)를 가져갈 때 Authorization: Bearer 헤더에 싣는 수집 토큰(app.management.scrape-token).
 * 서버는 환경변수 MANAGEMENT_SCRAPE_TOKEN 으로 받고, Prometheus 는 같은 값을 적은 파일(prometheus.yml 의 credentials_file)을 읽는다.
 *
 * <p>값이 비어 있으면 어떤 요청도 지표를 받지 못한다. 토큰을 넣지 않은 로컬 서버나 테스트에서 지표가 저절로 열리지 않게 하려는 것이다.
 *
 * <p>앞뒤 공백과 줄바꿈은 떼고 쓴다. 비밀 저장소나 파일에 적은 값 끝에 줄바꿈이 붙어도, 파일 내용의 앞뒤 공백을 떼고 보내는
 * Prometheus 의 값과 같아지게 한다.
 *
 * <p>toString 은 토큰을 드러내지 않는다. 설정 객체가 로그나 오류 메시지에 찍혀도 토큰이 새지 않게 한다.
 */
@ConfigurationProperties(prefix = "app.management")
public record ScrapeTokenProperties(String scrapeToken) {

	public ScrapeTokenProperties {
		scrapeToken = scrapeToken == null ? "" : scrapeToken.strip();
	}

	/**
	 * 수집 토큰이 설정돼 있는지 알려 준다. false 면 지표 요청을 모두 막는다.
	 */
	public boolean hasScrapeToken() {
		return !scrapeToken.isEmpty();
	}

	@Override
	public String toString() {
		return "ScrapeTokenProperties[scrapeToken=" + (hasScrapeToken() ? "(설정됨)" : "(비어 있음)") + "]";
	}
}
