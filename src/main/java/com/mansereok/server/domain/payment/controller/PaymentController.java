package com.mansereok.server.domain.payment.controller;

import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.dto.response.OrderResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.client.PortOneWebhookVerifier;
import com.mansereok.server.domain.payment.dto.request.PaymentCancelRequest;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.service.PaymentConfirmService;
import com.mansereok.server.domain.payment.service.PaymentQueryService;
import com.mansereok.server.domain.payment.service.PaymentRefundService;
import com.mansereok.server.domain.payment.service.PaymentService;
import io.portone.sdk.server.errors.WebhookVerificationException;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
	private final PaymentConfirmService paymentConfirmService;
	private final PaymentQueryService paymentQueryService;
	private final PaymentRefundService paymentRefundService;
	private final PortOneWebhookVerifier webhookVerifier;

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

	// 결제 완료 API (결제 후 검증). 요청자가 주문 소유자인지는 서비스가 확인한다(아니면 403).
	@PostMapping("/api/payment/complete")
	public ResponseEntity<OrderResponse> completePayment(
		@Valid @RequestBody PaymentCompleteRequest request,
		@AuthenticationPrincipal String username
	) {
		Order order = paymentConfirmService.complete(username, request);
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
	// 서명 검증 실패(WebhookVerificationException)는 GlobalExceptionHandler 가 401 로 매핑한다.
	@PostMapping("/api/payment/webhook")
	public ResponseEntity<Void> handleWebhook(
		@RequestBody String body,
		@RequestHeader("webhook-id") String webhookId,
		@RequestHeader("webhook-timestamp") String webhookTimestamp,
		@RequestHeader("webhook-signature") String webhookSignature
	) throws WebhookVerificationException {
		long startTime = System.currentTimeMillis();

		try {
			// 본문·서명·타임스탬프는 로그에 남기지 않는다 (본문에 구매자 정보가 실린다). 검증 실패는 핸들러가 warn 으로 남긴다.
			webhookVerifier.verify(body, webhookId, webhookSignature, webhookTimestamp);

			paymentService.processWebhook(body);

			return ResponseEntity.ok().build();

		} finally {
			long duration = System.currentTimeMillis() - startTime;
			log.info("웹훅 처리 종료: webhookId={}, {}ms", webhookId, duration);
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
		paymentRefundService.cancel(username, request.getPaymentId(), request.getReason());
		return ResponseEntity.ok("환불이 정상적으로 처리되었습니다.");
	}
}
