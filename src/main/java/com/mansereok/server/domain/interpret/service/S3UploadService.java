package com.mansereok.server.domain.interpret.service;

import java.net.URL;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3UploadService {

	private final S3Client s3Client;

	@Value("${aws.s3.bucket-name}")
	private String bucketName;

	/**
	 * 파일을 S3 에 올리고 공개 주소를 돌려준다.
	 *
	 * <p>잠시 뒤 같은 요청이 성공할 수 있는 실패만 다시 시도한다. 연결이 끊기거나 응답이 늦은 {@link SdkClientException} 과 S3 가
	 * 5xx 로 답한 경우다. 권한이 없거나(403) 요청이 잘못된(4xx) 실패는 다시 해도 같으므로 곧바로 던진다.
	 *
	 * <p>본문을 한 번만 읽을 수 있는 스트림이 아니라 byte[] 로 받는다. 다시 시도할 때 이 메서드가 같은 인자로 다시 불리는데, 스트림이면
	 * 앞 시도가 이미 끝까지 읽어 버려 빈 본문이 나간다. byte[] 는 시도마다 처음부터 새 본문을 만든다.
	 *
	 * @param content     올릴 파일 내용
	 * @param objectKey   S3 객체 키
	 * @param contentType Content-Type 헤더 값
	 * @return 올린 객체의 공개 주소
	 */
	@Retryable(
		retryFor = {SdkClientException.class, S3ServerErrorException.class},
		maxAttempts = 3,
		backoff = @Backoff(delayExpression = "${s3.upload.retry-delay-ms:1000}")
	)
	public String uploadFileAndGetPublicUrl(byte[] content, String objectKey, String contentType) {
		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.contentType(contentType)
			.build();

		try {
			s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
		} catch (S3Exception e) {
			if (e.statusCode() >= 500) {
				throw new S3ServerErrorException(e);
			}
			throw e;
		}

		GetUrlRequest getUrlRequest = GetUrlRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.build();

		URL fileUrl = s3Client.utilities().getUrl(getUrlRequest);

		log.info("S3 Public Upload Success. Key: {}", objectKey);
		return fileUrl.toString();
	}

	/** S3 가 5xx 로 답한 실패. 잠시 뒤 같은 요청이 성공할 수 있어 다시 시도한다. 원래 S3 예외는 cause 로 남긴다. */
	static final class S3ServerErrorException extends RuntimeException {

		S3ServerErrorException(S3Exception cause) {
			super("S3 서버 오류(" + cause.statusCode() + ")로 업로드 실패: " + cause.getMessage(), cause);
		}
	}
}
