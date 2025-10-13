package com.mansereok.server.controller;

import com.mansereok.server.entity.Order;
import com.mansereok.server.service.PaymentService;
import com.mansereok.server.service.request.OrderCreateRequest;
import com.mansereok.server.service.request.PaymentCompleteRequest;
import com.mansereok.server.service.response.OrderCreateResponse;
import com.mansereok.server.service.response.PaymentResponseDto;
import io.portone.sdk.server.webhook.WebhookVerifier;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

	private final PaymentService paymentService;

	@Value("${portone.webhook.secret}")
	private String webhookSecret;

	/**
	 * 주문 생성 API (결제 전)
	 */
	@PostMapping("/api/payment/orders")
	public ResponseEntity<?> createOrder(@RequestBody OrderCreateRequest request) {
		try {
			OrderCreateResponse response = paymentService.createOrder(request);
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("주문 생성 실패", e);
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	// 결제 완료 API (결제 후 검증) // 이렇게 구현하면 프론트엔드에서 폴링으로 계속 결제 정보 확인해야함 .
	@PostMapping("/api/payment/complete")
	public ResponseEntity<?> completePayment(@RequestBody PaymentCompleteRequest request) {
		try {
			Order order = paymentService.completePayment(request);
			return ResponseEntity.ok(order);
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}

	@PostMapping("/api/payment/webhook")
	public ResponseEntity<Void> handleWebhook(
		@RequestBody String body,
		@RequestHeader("webhook-id") String webhookId,
		@RequestHeader("webhook-timestamp") String webhookTimestamp,
		@RequestHeader("webhook-signature") String webhookSignature
	) {
		try {
			// 1. 웹훅 서명 검증 (위변조 방지)
			WebhookVerifier verifier = new WebhookVerifier(webhookSecret);
			verifier.verify(body, webhookId, webhookTimestamp, webhookSignature);

			log.info("웹훅 서명 검증 성공: webhookId={}", webhookId);

			// 2. 검증 통과 후 처리
			paymentService.processWebhook(body);

			return ResponseEntity.ok().build();

		} catch (Exception e) {
			log.error("웹훅 처리 실패: {}", e.getMessage(), e);
			return ResponseEntity.status(500).build();
		}
	}

	@GetMapping("/api/payments/me")
	public ResponseEntity<List<PaymentResponseDto>> getPaymentHistory(
		@AuthenticationPrincipal String username
	) {
		List<PaymentResponseDto> responses = paymentService.getPayments(username);
		return ResponseEntity.ok(responses);
	}
}
