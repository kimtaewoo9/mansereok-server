package com.mansereok.server.domain.user.controller;

import com.mansereok.server.domain.interpret.dto.response.CompatibilityPageResponse;
import com.mansereok.server.domain.interpret.dto.response.InterpretationPageResponse;
import com.mansereok.server.domain.interpret.dto.response.InterpretationResultResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.domain.interpret.dto.response.SajuHistoryResponseDto;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.dto.response.ProfileResponseDto;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
	public ResponseEntity<List<InterpretationPageResponse>> getInterpretationResults(
		@AuthenticationPrincipal String username
	) {
		List<InterpretationPageResponse> results =
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
	public ResponseEntity<List<CompatibilityPageResponse>> getCompatibilityResults(
		@AuthenticationPrincipal String username
	) {
		List<CompatibilityPageResponse> result =
			userService.getCompatibilityResults(
				username); // Service method now returns the new DTO list
		return ResponseEntity.ok(result);
	}

	@GetMapping("/api/v1/users/me/compatibility/{resultId}")
	public ResponseEntity<ManseCompatibilityAnalysisResponse> getCompatibilityResult(
		@PathVariable Long resultId,
		@AuthenticationPrincipal String username
	) {
		ManseCompatibilityAnalysisResponse result =
			userService.getCompatibilityResultDetail(resultId, username); // 👈 올바른 서비스 메서드 호출
		return ResponseEntity.ok(result);
	}

	@GetMapping("/api/v1/users/me/saju-history")
	public ResponseEntity<List<SajuHistoryResponseDto>> getCombinedSajuHistory(
		@AuthenticationPrincipal String username
	) {
		List<SajuHistoryResponseDto> results = userService.getCombinedSajuHistory(username);
		return ResponseEntity.ok(results);
	}

	@DeleteMapping("/api/v1/users/me")
	public ResponseEntity<Void> deleteUser(
		@AuthenticationPrincipal String username,
		HttpServletResponse response
	) {
		// 1. 회원 탈퇴 서비스 로직 실행
		userService.deleteUser(username);

		// 2. 클라이언트 쿠키 삭제 (로그아웃 처리)
		Cookie refreshCookie = new Cookie("REFRESH_TOKEN", null);
		refreshCookie.setMaxAge(0);
		refreshCookie.setPath("/");
		refreshCookie.setHttpOnly(true);
		refreshCookie.setSecure(true);
		response.addCookie(refreshCookie);

		Cookie jsessionCookie = new Cookie("JSESSIONID", null);
		jsessionCookie.setMaxAge(0);
		jsessionCookie.setPath("/");
		response.addCookie(jsessionCookie);

		return ResponseEntity.noContent().build();
	}
}
