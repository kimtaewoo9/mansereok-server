package com.mansereok.server.controller;

import com.mansereok.server.entity.Order;
import com.mansereok.server.repository.OrderRepository;
import com.mansereok.server.service.PaymentService;
import com.mansereok.server.service.request.OrderCreateRequest;
import com.mansereok.server.service.request.PaymentCompleteRequest;
import com.mansereok.server.service.response.OrderCreateResponse;
import com.mansereok.server.service.response.PaymentResponseDto;
import io.portone.sdk.server.webhook.WebhookVerifier;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
	private final OrderRepository orderRepository;

	@Value("${portone.webhook.secret}")
	private String webhookSecret;

	/**
	 * 주문 생성 API (결제 전)
	 */
	@PostMapping("/api/payment/orders")
	public ResponseEntity<?> createOrder(
		@RequestBody OrderCreateRequest request,
		@AuthenticationPrincipal String username
	) {
		try {
			log.info("주문 생성 요청자: " + username);
			OrderCreateResponse response = paymentService.createOrder(username, request);
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

	@PostMapping("/api/payment/orders/{orderId}")
	public ResponseEntity<?> getOrder(
		@PathVariable Long orderId,
		@AuthenticationPrincipal String username
	) {
		Order order = orderRepository.findById(orderId).orElseThrow();
		return ResponseEntity.ok(order);
	}

	// 포트원이 결제완료 사실을 백엔드에 알려주는 알림 시스템
	// 웹훅은 누구나 요청을 보낼 수 있기 때문에 신뢰하지 않고 서명 검증 + API 재조회
	@PostMapping("/api/payment/webhook")
	public ResponseEntity<Void> handleWebhook(
		@RequestBody String body,
		@RequestHeader("webhook-id") String webhookId,
		@RequestHeader("webhook-timestamp") String webhookTimestamp,
		@RequestHeader("webhook-signature") String webhookSignature
	) {
		try {
			log.info("webhook-id: " + webhookId);
			log.info("webhook-timestamp: " + webhookTimestamp);
			log.info("webhook-signature: " + webhookSignature);
			log.info("webhook body: " + body);

			// 1. 웹훅 서명 검증 (위변조 방지)
			WebhookVerifier verifier = new WebhookVerifier(webhookSecret);
			verifier.verify(body, webhookId, webhookSignature, webhookTimestamp);

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
	public ResponseEntity<List<PaymentResponseDto>> getPayments(
		@AuthenticationPrincipal String username
	) {
		List<PaymentResponseDto> responses = paymentService.getPayments(username);
		return ResponseEntity.ok(responses);
	}

	@GetMapping("/api/orders/by-payment/{paymentId}") // 경로도 orders 쪽으로 맞추는 것이 더 명확
	public ResponseEntity<?> getOrderByPaymentId(
		@PathVariable String paymentId, // 타입 String으로 유지
		@AuthenticationPrincipal String username // 인증된 사용자 정보 추가
	) {
		try {
			log.info("Payment ID로 Order 조회 요청: username={}, paymentId={}", username, paymentId);
			Order order = orderRepository.findByPaymentId(paymentId).orElseThrow();
			return ResponseEntity.ok(order);
		} catch (EntityNotFoundException e) {
			log.error("Order 조회 실패 (찾을 수 없음): {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (Exception e) {
			log.error("Payment ID로 Order 조회 중 서버 오류 발생", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body("주문 조회 중 오류가 발생했습니다.");
		}
	}


}
