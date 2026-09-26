package com.mansereok.server.domain.coupon.controller;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.dto.CouponResponse;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.payment.service.PaymentUserLookup;
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
	private final PaymentUserLookup paymentUserLookup;

	// 사용자가 없으면 결제 API 와 같은 400 PAYMENT_ERROR 로 답한다(PaymentUserLookup).
	private Long getUserId(String username) {
		return paymentUserLookup.getByUsername(username).getId();
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

	// 3. 내 쿠폰함 조회 (결제 시 사용 가능 목록). 엔티티 대신 CouponResponse 로 내보낸다(JSON 키는 예전 엔티티 응답과 같다).
	@GetMapping("/api/coupons/my")
	public ResponseEntity<List<CouponResponse>> getMyCoupons(
		@AuthenticationPrincipal String username
	) {
		Long userId = getUserId(username); // 실제 userId 조회
		List<CouponResponse> myCoupons = couponService.getMyCoupons(userId).stream()
			.map(CouponResponse::from)
			.toList();
		return ResponseEntity.ok(myCoupons);
	}
}
