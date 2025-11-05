package com.mansereok.server.domain.interpret.service;

import java.io.InputStream;
import java.net.URL;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3UploadService {

	private final S3Client s3Client;

	@Value("${aws.s3.bucket-name}")
	private String bucketName;

	public String uploadFileAndGetPublicUrl(
		InputStream inputStream,
		long fileSize,
		String objectKey,
		String contentType
	) {
		try {
			PutObjectRequest putObjectRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(objectKey)
				.contentType(contentType)
				.build();

			RequestBody requestBody = RequestBody.fromInputStream(inputStream, fileSize);

			s3Client.putObject(putObjectRequest, requestBody);

			GetUrlRequest getUrlRequest = GetUrlRequest.builder()
				.bucket(bucketName)
				.key(objectKey)
				.build();

			URL fileUrl = s3Client.utilities().getUrl(getUrlRequest);

			log.info("S3 Public Upload Success. Key: {}, URL: {}", objectKey, fileUrl.toString());
			return fileUrl.toString();

		} catch (Exception e) {
			log.error("S3 Public Upload Failed. Key: {}", objectKey, e);
			throw new RuntimeException("S3 업로드 및 Public URL 생성에 실패했습니다.", e);
		}
	}
}
