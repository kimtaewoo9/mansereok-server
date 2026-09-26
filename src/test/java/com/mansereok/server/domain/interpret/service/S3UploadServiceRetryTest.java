package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 업로드의 재시도가 실제 Spring Retry 프록시를 거쳐 동작하는지 본다. 재시도는 같은 인자로 메서드를 다시 부르므로, 다시 보내는
 * 본문이 원본과 같은지가 핵심이다. S3 는 바깥 시스템이라 S3Client 만 목으로 두고, 재시도 대기는 1ms 로 줄인다.
 */
@SpringJUnitConfig(S3UploadServiceRetryTest.RetryConfig.class)
@TestPropertySource(properties = {
	"aws.s3.bucket-name=test-bucket",
	"s3.upload.retry-delay-ms=1"
})
@DisplayName("S3 업로드 재시도")
class S3UploadServiceRetryTest {

	private static final byte[] CONTENT = "og-image-bytes-0123456789".repeat(2_000).getBytes();
	private static final String OBJECT_KEY = "og-images/saju-7.png";
	private static final String CONTENT_TYPE = "image/png";

	@Configuration
	@EnableRetry
	@Import(S3UploadService.class)
	static class RetryConfig {

	}

	@MockitoBean
	private S3Client s3Client;

	@Autowired
	private S3UploadService s3UploadService;

	@Nested
	@DisplayName("S3 가 본문을 다 받은 뒤 500 으로 답하면")
	class WhenServerErrorAfterBodyWasRead {

		@Test
		@DisplayName("다시 시도할 때 원본과 같은 본문을 처음부터 다시 보내고 공개 주소를 돌려준다")
		void resendsWholeBody() {
			// given
			List<byte[]> sentBodies = new ArrayList<>();
			given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.willAnswer(readBodyThenThrow(sentBodies, s3Exception(500)))
				.willAnswer(readBodyThenSucceed(sentBodies));
			given(s3Client.utilities()).willReturn(S3Utilities.builder().region(Region.AP_NORTHEAST_2).build());

			// when
			String publicUrl = s3UploadService.uploadFileAndGetPublicUrl(CONTENT, OBJECT_KEY, CONTENT_TYPE);

			// then
			assertThat(sentBodies).as("보낸 본문 수").hasSize(2);
			assertThat(sentBodies.get(1)).as("두 번째 시도의 본문").isEqualTo(CONTENT);
			assertThat(publicUrl).isEqualTo("https://test-bucket.s3.ap-northeast-2.amazonaws.com/og-images/saju-7.png");
		}

		@Test
		@DisplayName("세 번 모두 500 이면 세 번 보낸 뒤 원래 S3 예외를 원인으로 담아 던진다")
		void givesUpAfterThreeServerErrors() {
			// given
			S3Exception serverError = s3Exception(503);
			given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willThrow(serverError);

			// when & then
			assertThatThrownBy(() -> s3UploadService.uploadFileAndGetPublicUrl(CONTENT, OBJECT_KEY, CONTENT_TYPE))
				.isInstanceOf(S3UploadService.S3ServerErrorException.class)
				.hasCause(serverError);
			then(s3Client).should(times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
		}
	}

	@Test
	@DisplayName("S3 가 403 으로 답하면 다시 해도 같으므로 한 번만 보내고 그 예외를 그대로 던진다")
	void doesNotRetryClientError() {
		// given
		S3Exception forbidden = s3Exception(403);
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willThrow(forbidden);

		// when & then
		assertThatThrownBy(() -> s3UploadService.uploadFileAndGetPublicUrl(CONTENT, OBJECT_KEY, CONTENT_TYPE))
			.isSameAs(forbidden);
		then(s3Client).should(times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	@DisplayName("연결 오류(SdkClientException)가 계속되면 세 번 보낸 뒤 그 예외를 던진다")
	void retriesConnectionErrorThreeTimes() {
		// given
		SdkClientException connectionError = SdkClientException.create("연결이 끊겼습니다");
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).willThrow(connectionError);

		// when & then
		assertThatThrownBy(() -> s3UploadService.uploadFileAndGetPublicUrl(CONTENT, OBJECT_KEY, CONTENT_TYPE))
			.isSameAs(connectionError);
		then(s3Client).should(times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	/** 실제 S3 처럼 본문을 끝까지 읽은 뒤 failure 를 던진다. */
	private static Answer<PutObjectResponse> readBodyThenThrow(List<byte[]> sentBodies, RuntimeException failure) {
		return invocation -> {
			sentBodies.add(readAll(invocation.getArgument(1)));
			throw failure;
		};
	}

	/** 본문을 끝까지 읽고 성공으로 답한다. */
	private static Answer<PutObjectResponse> readBodyThenSucceed(List<byte[]> sentBodies) {
		return invocation -> {
			sentBodies.add(readAll(invocation.getArgument(1)));
			return PutObjectResponse.builder().build();
		};
	}

	private static byte[] readAll(RequestBody body) throws IOException {
		try (InputStream in = body.contentStreamProvider().newStream()) {
			return in.readAllBytes();
		}
	}

	private static S3Exception s3Exception(int statusCode) {
		return (S3Exception) S3Exception.builder().statusCode(statusCode).message("S3 응답 " + statusCode).build();
	}
}
