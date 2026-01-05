package com.mansereok.server.domain.coupon.controller;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponController {


	private final CouponService couponService;
	private final UserRepository userRepository;

	// 유저 조회 편의 메서드
	private Long getUserId(String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));
		return user.getId();
	}

	// 1. 쿠폰 이벤트 목록 조회 (발급 여부 포함)
	@GetMapping("/api/coupons/events")
	public ResponseEntity<List<CouponEventDto>> getCouponEvents(
		@AuthenticationPrincipal String username
	) {
		Long userId = getUserId(username); // 실제 userId 조회
		List<CouponEventDto> events = couponService.getCouponEvents(userId);
		return ResponseEntity.ok(events);
	}

	// 2. 쿠폰 다운로드 (발급)
	@PostMapping("/api/coupons/{templateId}/download")
	public ResponseEntity<Void> downloadCoupon(
		@PathVariable Long templateId,
		@AuthenticationPrincipal String username
	) {
		Long userId = getUserId(username); // 실제 userId 조회
		couponService.downloadCoupon(userId, templateId);
		return ResponseEntity.ok().build();
	}

	// 3. 내 쿠폰함 조회 (결제 시 사용 가능 목록)
	@GetMapping("/api/coupons/my")
	public ResponseEntity<List<Coupon>> getMyCoupons(
		@AuthenticationPrincipal String username
	) {
		Long userId = getUserId(username); // 실제 userId 조회
		List<Coupon> myCoupons = couponService.getMyCoupons(userId);
		return ResponseEntity.ok(myCoupons);
	}
}
