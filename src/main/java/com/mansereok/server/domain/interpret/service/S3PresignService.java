package com.mansereok.server.domain.interpret.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3PresignService {

	private final S3Presigner s3Presigner;

	@Value("${aws.s3.bucket-name}")
	private String bucketName;

	public String generatePresignedUrlForPut(String objectKey, String contentType,
		int expirationSeconds) {

		// 1. PUT 요청 객체 생성
		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
			.bucket(bucketName)
			.key(objectKey)
			.contentType(contentType)
			.build();

		// 2. Pre-Signed URL 요청 생성 (만료 시간 설정)
		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
			.signatureDuration(Duration.ofSeconds(expirationSeconds))
			.putObjectRequest(putObjectRequest)
			.build();

		// 3. URL 생성 및 반환
		try {
			String presignedUrl = s3Presigner.presignPutObject(presignRequest).url()
				.toExternalForm();
			log.info("Pre-Signed PUT URL generated. Key: {}", objectKey);
			return presignedUrl;
		} catch (Exception e) {
			log.error("Pre-Signed URL generation failed. Key: {}", objectKey, e);
			throw new RuntimeException("Pre-Signed URL 생성에 실패했습니다.", e);
		}
	}
}
