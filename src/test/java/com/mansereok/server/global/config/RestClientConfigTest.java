package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 소셜 로그인이 쓰는 공용 RestClient 가 답 없는 제공자를 끝없이 기다리지 않는지 실제 소켓으로 확인한다.
 *
 * <p>설정 클래스가 만든 RestClient 를 그대로 쓴다. 요청을 보내는 구현도 운영과 같이 클래스패스에서 찾은 것(JDK HttpClient)이다.
 */
class RestClientConfigTest {

	private final RestClient restClient = new RestClientConfig().restClient(RestClient.builder());

	private ServerSocket silentServer;

	@BeforeEach
	void openSilentServer() throws IOException {
		// 연결은 운영체제가 받아 두지만(backlog) 서버는 아무것도 읽거나 쓰지 않는다. 연결만 받고 답하지 않는 제공자와 같다.
		silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
	}

	@AfterEach
	void closeSilentServer() throws IOException {
		silentServer.close();
	}

	@Test
	@DisplayName("연결만 받고 답하지 않는 서버에는 읽기 제한(5초)이 지나면 ResourceAccessException 으로 끊는다")
	void stopsWaitingForSilentServer() {
		// given
		String url = "http://127.0.0.1:" + silentServer.getLocalPort() + "/token";
		long startedAt = System.nanoTime();

		// when & then: 제한이 없으면 끝없이 기다리므로 8초 안에 끝나는지 본다
		assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
			assertThatThrownBy(() -> restClient.get().uri(url).retrieve().toBodilessEntity())
				.isInstanceOf(ResourceAccessException.class));
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
			.as("읽기 제한 5초보다 먼저 끊지 않는다")
			.isGreaterThanOrEqualTo(Duration.ofMillis(4500));
	}

	@Test
	@DisplayName("닫힌 포트에는 연결 제한을 기다리지 않고 바로 ResourceAccessException 을 던진다")
	void failsFastOnClosedPort() throws IOException {
		// given
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		String url = "http://127.0.0.1:" + closedPort + "/token";

		// when & then
		assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
			assertThatThrownBy(() -> restClient.get().uri(url).retrieve().toBodilessEntity())
				.isInstanceOf(ResourceAccessException.class));
	}
}
