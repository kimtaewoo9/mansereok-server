package com.mansereok.server.controller;

import com.mansereok.server.entity.User;
import com.mansereok.server.service.S3PresignService;
import com.mansereok.server.service.UserService;
import com.mansereok.server.service.request.UploadCompleteRequest;
import com.mansereok.server.service.response.PresignedUrlResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class S3Controller {

	private final S3PresignService s3PresignService;
	private final UserService userService;

	// 클라이언트가 업로드할 파일 정보(이름, 타입)를 서버에 알려줍니다.
	@GetMapping("/api/s3/presigned-url")
	public ResponseEntity<PresignedUrlResponse> getPresignedUrl(
		@RequestParam String fileName,
		@RequestParam String contentType,
		@AuthenticationPrincipal String username
	) {
		User user = userService.findByUsername(username);

		// 파일명 sanitize (경로 탐색 공격 방지)
		String safeFileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

		// UUID 추가로 파일명 충돌 방지
		String uniqueFileName = UUID.randomUUID() + "_" + safeFileName;
		String objectKey = String.format("user-uploads/%s/%s", user.getId(), uniqueFileName);

		String presignedUrl = s3PresignService.generatePresignedUrlForPut(
			objectKey, contentType, 300
		);

		PresignedUrlResponse response = new PresignedUrlResponse(
			presignedUrl,
			objectKey,  // 클라이언트가 업로드 후 DB 저장 시 필요
			300
		);

		return ResponseEntity.ok(response);
	}

	@PostMapping("/api/s3/upload-complete")
	public ResponseEntity<String> uploadComplete(
		@RequestBody UploadCompleteRequest request,
		@AuthenticationPrincipal String username
	) {
		User user = userService.findByUsername(username);

		log.info("File upload completed - User: {}, ObjectKey: {}",
			user.getId(), request.getObjectKey());

		// TODO: 필요하면 DB에 파일 정보 저장

		return ResponseEntity.ok("업로드 완료");
	}
}
