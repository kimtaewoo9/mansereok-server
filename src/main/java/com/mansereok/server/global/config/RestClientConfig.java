package com.mansereok.server.global.config;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 소셜 로그인 서비스 네 곳(구글·카카오·네이버·X)이 함께 쓰는 RestClient.
 *
 * <p>연결은 3초, 응답은 5초 안에 오지 않으면 끊는다. 스프링 부트 기본값에는 시간 제한이 없어, 제공자가 연결만 받고 답하지 않으면
 * 로그인 요청 스레드가 끝없이 기다렸다. 그런 요청이 쌓이면 톰캣 스레드가 모두 묶여 결제 같은 다른 요청도 받지 못한다.
 *
 * <p>끊겼을 때 던지는 예외는 어디서 멈췄느냐에 따라 다르다. 연결하지 못했거나 응답 헤더가 5초 안에 오지 않으면
 * ResourceAccessException 이다. 헤더는 왔는데 본문을 받다가 5초가 지나면 RestClientException 이고 원인은 IOException 이다.
 * 각 제공자 서비스(OauthProviderCalls)가 두 경우를 모두 503(OauthProviderUnavailableException)으로 바꾼다.
 *
 * <p>요청을 보내는 구현은 클래스패스에서 찾은 것(지금은 JDK HttpClient)을 그대로 쓰고 시간 제한만 더한다. 결제·사주 해석 쪽은 자기
 * RestClient 를 따로 만들어 쓰므로 이 설정과 상관없다.
 */
@Configuration
public class RestClientConfig {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	public RestClient restClient(RestClient.Builder builder) {
		return builder
			.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(requestFactorySettings()))
			.build();
	}

	/**
	 * 요청 구현에 넘기는 설정. 연결 3초·읽기 5초 제한만 기본값에서 바꾼다.
	 */
	static ClientHttpRequestFactorySettings requestFactorySettings() {
		return ClientHttpRequestFactorySettings.defaults()
			.withConnectTimeout(CONNECT_TIMEOUT)
			.withReadTimeout(READ_TIMEOUT);
	}
}
