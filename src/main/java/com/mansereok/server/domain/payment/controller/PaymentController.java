package com.mansereok.server.domain.payment.controller;

import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.service.PaymentService;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.UserService;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
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
	private final UserService userService;

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

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		log.info("Username: {}", username);
		log.info("Authorities: {}", auth.getAuthorities());
		log.info("Is Authenticated: {}", auth.isAuthenticated());

		OrderCreateResponse response = paymentService.createOrder(username, request);
		return ResponseEntity.ok(response);
	}

	// 결제 완료 API (결제 후 검증)
	@PostMapping("/api/payment/complete")
	public ResponseEntity<?> completePayment(@RequestBody PaymentCompleteRequest request) {
		Order order = paymentService.completePayment(request);
		log.info("Order: {}", order);
		return ResponseEntity.ok(order);
	}

	@PostMapping("/api/payment/redeem-free")
	public ResponseEntity<?> redeemFreeProduct(
		@RequestBody OrderCreateRequest request,
		@AuthenticationPrincipal String username
	) {
		log.info("0원 결제 요청: username={}", username);
		OrderCreateResponse response = paymentService.redeemFreeProduct(username, request);
		return ResponseEntity.ok(response);
	}

	// 포트원이 결제완료 사실을 백엔드에 알려주는 알림 시스템
	// 웹훅은 누구나 요청을 보낼 수 있기 때문에 신뢰하지 않고 서명 검증 + API 재조회
	@PostMapping("/api/payment/webhook")
	public ResponseEntity<Void> handleWebhook(
		@RequestBody String body,
		@RequestHeader("webhook-id") String webhookId,
		@RequestHeader("webhook-timestamp") String webhookTimestamp,
		@RequestHeader("webhook-signature") String webhookSignature
	) throws WebhookVerificationException {
		log.info("webhook-id: " + webhookId);
		log.info("webhook-timestamp: " + webhookTimestamp);
		log.info("webhook-signature: " + webhookSignature);
		log.info("webhook body: " + body);

		WebhookVerifier verifier = new WebhookVerifier(webhookSecret);
		verifier.verify(body, webhookId, webhookSignature, webhookTimestamp);

		log.info("웹훅 서명 검증 성공: webhookId={}", webhookId);

		paymentService.processWebhook(body);

		return ResponseEntity.ok().build();
	}

	@GetMapping("/api/payment/orders/{orderId}")
	public ResponseEntity<?> getOrder(
		@PathVariable Long orderId,
		@AuthenticationPrincipal String username
	) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(
				() -> new EntityNotFoundException("주문을 찾을 수 없습니다.")); // 👈 404 처리를 위해 예외 Throw
		return ResponseEntity.ok(order);
	}

	@GetMapping("/api/payments/me")
	public ResponseEntity<List<PaymentResponseDto>> getPayments(
		@AuthenticationPrincipal String username
	) {
		List<PaymentResponseDto> responses = paymentService.getPayments(username);
		return ResponseEntity.ok(responses);
	}

	@GetMapping("/api/orders/by-payment/{paymentId}")
	public ResponseEntity<?> getOrderByPaymentId(
		@PathVariable Long paymentId,
		@AuthenticationPrincipal String username
	) {
		log.info("Payment ID로 Order 조회 요청: username={}, paymentId={}", username, paymentId);
		Order order = orderRepository.findByPaymentPkId(paymentId)
			.orElseThrow(() -> new EntityNotFoundException(
				"결제 ID에 해당하는 주문을 찾을 수 없습니다."));

		User currentUser = userService.findByUsername(username);

		if (!order.getUserId().equals(currentUser.getId())) {
			log.warn("권한 없는 주문 조회 시도: 요청자={}, 주문 소유자={}",
				currentUser.getId(), order.getUserId());
			throw new AccessDeniedException("본인의 주문만 조회할 수 있습니다.");
		}
		return ResponseEntity.ok(order);
	}
}
