package com.mansereok.server.domain.review.controller;

import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewEligibilityResponse;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.service.ReviewService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ReviewController {

	private final ReviewService reviewService; //

	@PostMapping("/api/v1/reviews")
	public ResponseEntity<ReviewResponse> createReview(
		@Valid @RequestBody ReviewCreateRequest request,
		@AuthenticationPrincipal String username
	) {
		ReviewResponse response = reviewService.createReview(username, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/api/v1/reviews/eligibility")
	public ResponseEntity<ReviewEligibilityResponse> checkReviewEligibility(
		@RequestParam Long orderId,
		@RequestParam Long subCategoryId,
		@AuthenticationPrincipal String username
	) {
		ReviewEligibilityResponse response = reviewService.checkReviewEligibility(
			username,
			orderId,
			subCategoryId
		);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/api/v1/reviews/{reviewId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Map<String, String>> deleteReview(
		@PathVariable Long reviewId,
		@AuthenticationPrincipal String username
	) {
		reviewService.deleteReview(reviewId, username);
		return ResponseEntity.ok(Map.of("message", "리뷰가 성공적으로 삭제되었습니다."));
	}

	@GetMapping("/api/v1/reviews")
	public ResponseEntity<Page<ReviewResponse>> readAll(
		@RequestParam(required = false) Long subCategoryId,
		@RequestParam(defaultValue = "1") int page,
		@RequestParam(defaultValue = "10") int size
	) {
		Page<ReviewResponse> result;

		if (subCategoryId != null) {
			// 1. 특정 상품 리뷰 조회
			result = reviewService.getReviewsBySubCategory(subCategoryId, page, size);
			log.info("상품 {} 리뷰 조회: {}페이지 ({}개)", subCategoryId, page, result.getContent().size());
		} else {
			// 2. 전체 리뷰 조회 (여기도 페이징 적용!)
			result = reviewService.getAllReviewsSortedByLatest(page, size);
			log.info("전체 리뷰 조회: {}페이지 ({}개)", page, result.getContent().size());
		}

		return ResponseEntity.ok(result);
	}
}
