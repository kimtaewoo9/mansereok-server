package com.mansereok.server.controller;

import com.mansereok.server.entity.User;
import com.mansereok.server.service.UserService;
import com.mansereok.server.service.request.ProfileUpdateRequestDto;
import com.mansereok.server.service.response.InterpretationResultResponse;
import com.mansereok.server.service.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ProfileResponseDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProfileController {

	private final UserService userService;

	@GetMapping("/api/v1/users/me/profiles")
	public ResponseEntity<ProfileResponseDto> getProfile(
		@AuthenticationPrincipal String username
	) {
		User user = userService.findByUsername(username);

		return ResponseEntity.ok(
			new ProfileResponseDto(user)
		);
	}

	@PatchMapping("/api/v1/users/me/profiles")
	public ResponseEntity<ProfileResponseDto> updateProfile(
		@AuthenticationPrincipal String username,
		@RequestBody ProfileUpdateRequestDto requestDto
	) {
		// username을 서비스로 직접 전달
		User updatedUser = userService.updateUserProfile(username, requestDto);
		return ResponseEntity.ok(new ProfileResponseDto(updatedUser));
	}

	@GetMapping("/api/v1/users/me/saju")
	public ResponseEntity<List<InterpretationResultResponse>> getInterpretationResults(
		@AuthenticationPrincipal String username
	) {
		List<InterpretationResultResponse> results =
			userService.getInterpretationResults(username);
		return ResponseEntity.ok(results);
	}

	@GetMapping("/api/v1/users/me/saju/{resultId}")
	public ResponseEntity<InterpretationResultResponse> getInterpretationResult(
		@PathVariable Long resultId,
		@AuthenticationPrincipal String username
	) {
		InterpretationResultResponse result =
			userService.getInterpretationResult(resultId, username);
		return ResponseEntity.ok(result);
	}

	@GetMapping("/api/v1/users/me/compatibility")
	public ResponseEntity<List<ManseCompatibilityAnalysisResponse>> getCompatibilityResults(
		@AuthenticationPrincipal String username
	) {
		List<ManseCompatibilityAnalysisResponse> result =
			userService.getMyCompatibilityResults(username);
		return ResponseEntity.ok(result);
	}
}
