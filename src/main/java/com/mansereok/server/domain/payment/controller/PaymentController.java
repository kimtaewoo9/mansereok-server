package com.mansereok.server.domain.payment.controller;

import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.dto.response.OrderResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.dto.request.PaymentCancelRequest;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.service.PaymentQueryService;
import com.mansereok.server.domain.payment.service.PaymentService;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

	private final PaymentService paymentService;
	private final PaymentQueryService paymentQueryService;

	@Value("${portone.webhook.secret}")
	private String webhookSecret;

	/**
	 * 주문 생성 API (결제 전)
	 */
	@PostMapping("/api/payment/orders")
	public ResponseEntity<?> createOrder(
		@Valid @RequestBody OrderCreateRequest request,
		@AuthenticationPrincipal String username
	) {
		OrderCreateResponse response = paymentService.createOrder(username, request);
		return ResponseEntity.ok(response);
	}

	// 결제 완료 API (결제 후 검증)
	@PostMapping("/api/payment/complete")
	public ResponseEntity<OrderResponse> completePayment(
		@Valid @RequestBody PaymentCompleteRequest request
	) {
		Order order = paymentService.completePayment(request);
		return ResponseEntity.ok(OrderResponse.from(order));
	}

	@PostMapping("/api/payment/redeem-free")
	public ResponseEntity<?> redeemFreeProduct(
		@Valid @RequestBody OrderCreateRequest request,
		@AuthenticationPrincipal String username
	) {
		log.info("0원 결제 요청: username={}", username);
		OrderCreateResponse response = paymentService.redeemFreeProduct(username, request);
		return ResponseEntity.ok(response);
	}

	// 포트원이 결제완료 사실을 백엔드에 알려주는 웹훅
	// 웹훅은 누구나 요청을 보낼 수 있기 때문에 신뢰하지 않고 서명 검증 + API 재조회
	@PostMapping("/api/payment/webhook")
	public ResponseEntity<Void> handleWebhook(
		@RequestBody String body,
		@RequestHeader("webhook-id") String webhookId,
		@RequestHeader("webhook-timestamp") String webhookTimestamp,
		@RequestHeader("webhook-signature") String webhookSignature
	) throws WebhookVerificationException {
		long startTime = System.currentTimeMillis();

		try {
			log.info("webhook-id: " + webhookId);
			log.info("webhook-timestamp: " + webhookTimestamp);
			log.info("webhook-signature: " + webhookSignature);
			log.info("webhook body: " + body);

			WebhookVerifier verifier = new WebhookVerifier(webhookSecret);
			verifier.verify(body, webhookId, webhookSignature, webhookTimestamp);

			log.info("웹훅 서명 검증 성공: webhookId={}", webhookId);

			paymentService.processWebhook(body);

			return ResponseEntity.ok().build();

		} finally {
			// 2. 종료 시간 기록 및 로그 출력
			long endTime = System.currentTimeMillis();
			long duration = endTime - startTime;
			log.info("=== 웹훅 API 총 응답 시간: {}ms (ID: {}) ===", duration, webhookId); // 3. 결과 로그
		}
	}

	@GetMapping("/api/payment/orders/{orderId}")
	public ResponseEntity<OrderResponse> getOrder(
		@PathVariable Long orderId,
		@AuthenticationPrincipal String username
	) {
		Order order = paymentQueryService.getOwnedOrder(orderId, username);
		return ResponseEntity.ok(OrderResponse.from(order));
	}

	@GetMapping("/api/payments/me")
	public ResponseEntity<List<PaymentResponseDto>> getPayments(
		@AuthenticationPrincipal String username
	) {
		List<PaymentResponseDto> responses = paymentQueryService.getPayments(username);
		return ResponseEntity.ok(responses);
	}

	@GetMapping("/api/orders/by-payment/{paymentId}")
	public ResponseEntity<OrderResponse> getOrderByPaymentId(
		@PathVariable Long paymentId,
		@AuthenticationPrincipal String username
	) {
		log.info("Payment ID로 Order 조회 요청: username={}, paymentId={}", username, paymentId);
		Order order = paymentQueryService.getOwnedOrderByPaymentPkId(paymentId, username);
		return ResponseEntity.ok(OrderResponse.from(order));
	}

	@PostMapping("/api/payment/cancel")
	public ResponseEntity<?> cancelPayment(
		@Valid @RequestBody PaymentCancelRequest request,
		@AuthenticationPrincipal String username
	) {
		paymentService.cancelPayment(username, request.getPaymentId(), request.getReason());
		return ResponseEntity.ok("환불이 정상적으로 처리되었습니다.");
	}
}
