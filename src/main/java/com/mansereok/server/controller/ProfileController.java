package com.mansereok.server.controller;

import com.mansereok.server.entity.User;
import com.mansereok.server.service.UserService;
import com.mansereok.server.service.request.ProfileUpdateRequestDto;
import com.mansereok.server.service.response.InterpretationResultResponse;
import com.mansereok.server.service.response.ProfileResponseDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class ProfileController {

	private final UserService userService;

	@GetMapping("/api/v1/users/me/profiles")
	public ResponseEntity<ProfileResponseDto> getProflie(
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
	public ResponseEntity<List<InterpretationResultResponse>> getInterpretationResult(
		@AuthenticationPrincipal String username
	) {
		List<InterpretationResultResponse> results =
			userService.getInterpretationResultsForUser(username);
		return ResponseEntity.ok(results);
	}
}
