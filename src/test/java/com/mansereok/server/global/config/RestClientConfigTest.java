package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 소셜 로그인이 쓰는 공용 RestClient 가 답 없는 제공자를 끝없이 기다리지 않는지 확인한다.
 *
 * <p>연결·읽기 제한 값은 설정을 직접 읽어 확인한다. 연결 제한은 로컬에서 재현하기 어렵기 때문이다(닫힌 포트는 운영체제가 곧바로
 * 거절해 제한과 상관없이 빨리 끝난다). 읽기 제한은 실제 소켓으로 멈춘 제공자를 흉내 내, 설정 클래스가 만든 RestClient 가 어떤 예외로
 * 끊는지까지 본다. 요청을 보내는 구현도 운영과 같이 클래스패스에서 찾은 것(JDK HttpClient)이다.
 */
class RestClientConfigTest {

	private final RestClient restClient = new RestClientConfig().restClient(RestClient.builder());

	private final CountDownLatch testFinished = new CountDownLatch(1);

	private ServerSocket server;

	@BeforeEach
	void openServer() throws IOException {
		// 연결은 운영체제가 받아 둔다(backlog). 서버가 무엇을 보낼지는 테스트마다 정한다.
		server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
	}

	@AfterEach
	void closeServer() throws IOException {
		testFinished.countDown();
		server.close();
	}

	@Test
	@DisplayName("요청 구현에 연결 제한 3초와 읽기 제한 5초를 넘긴다")
	void passesConnectAndReadTimeouts() {
		// when
		ClientHttpRequestFactorySettings settings = RestClientConfig.requestFactorySettings();

		// then
		assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("연결만 받고 응답 헤더를 보내지 않는 서버에는 읽기 제한(5초)이 지나면 ResourceAccessException 으로 끊는다")
	void stopsWaitingForSilentServer() {
		// given: 서버는 연결을 accept 하지도, 아무것도 쓰지도 않는다
		String url = "http://127.0.0.1:" + server.getLocalPort() + "/token";
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
	@DisplayName("응답 헤더를 보낸 뒤 본문 도중 멈추는 서버에는 읽기 제한(5초)이 지나면 원인이 IOException 인 RestClientException 으로 끊는다")
	void stopsWaitingForStalledBody() {
		// given
		sendHeadersAndStall();
		String url = "http://127.0.0.1:" + server.getLocalPort() + "/token";
		long startedAt = System.nanoTime();

		// when & then: ResourceAccessException 이 아니므로 제공자 서비스가 따로 503 으로 바꿔야 한다
		assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
			assertThatThrownBy(() -> restClient.get().uri(url).retrieve().body(Map.class))
				.isExactlyInstanceOf(RestClientException.class)
				.hasCauseExactlyInstanceOf(IOException.class));
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
			.as("읽기 제한 5초보다 먼저 끊지 않는다")
			.isGreaterThanOrEqualTo(Duration.ofMillis(4500));
	}

	// 연결을 받아 200 응답 헤더와 JSON 본문 앞부분만 보내고, 테스트가 끝날 때까지 더 보내지 않는다.
	private void sendHeadersAndStall() {
		Thread.ofPlatform().daemon().start(() -> {
			try (Socket socket = server.accept()) {
				OutputStream out = socket.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\n"
					+ "Content-Type: application/json\r\n"
					+ "Content-Length: 100\r\n"
					+ "\r\n"
					+ "{\"access_token\": \"").getBytes(StandardCharsets.UTF_8));
				out.flush();
				testFinished.await();
			} catch (IOException | InterruptedException e) {
				// 테스트가 끝나 소켓을 닫으면 여기로 온다. 확인할 것은 클라이언트 쪽이라 무시한다.
			}
		});
	}
}
