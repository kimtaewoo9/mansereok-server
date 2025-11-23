package com.mansereok.server.domain.discount.controller;

import com.mansereok.server.domain.discount.dto.request.DiscountCheckRequest;
import com.mansereok.server.domain.discount.dto.response.DiscountCheckResponse;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DiscountController {

	private final DiscountCodeService discountCodeService;

	@PostMapping("/api/payment/discount")
	public ResponseEntity<DiscountCheckResponse> checkDiscount(
		@RequestBody DiscountCheckRequest request
	) {
		DiscountCheckResponse response = discountCodeService.checkDiscount(request);
		return ResponseEntity.ok(response);
	}
}
