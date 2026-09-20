package com.mansereok.server.domain.payment.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.dto.response.PortoneWebhookDto;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentService {

	private final DiscordNotificationService discordNotificationService;

	private final OrderRepository orderRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final PaymentRepository paymentRepository;
	private final DiscountCodeService discountCodeService;

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	private final ObjectMapper objectMapper;
	private final UserRepository userRepository;

	private final ResultService resultService;

	private final CouponService couponService;

	private final PortOneClient portOneClient;

	private final PaidOrderFinalizer paidOrderFinalizer;

	// 1단계: 주문 생성 (결제 전)
	public OrderCreateResponse createOrder(String username, OrderCreateRequest request) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));

		Integer originalAmount = subCategory.getPrice(); // 1. 원본 금액 .

		try {
			DiscountValidationResult validationResult;
			Long usedCouponId = null; // 나중에 사용 처리를 위해 저장

			// A. 쿠폰을 선택한 경우 (우선순위 높음)
			if (request.getCouponId() != null) {
				// 새 CouponService 호출
				validationResult = couponService.validateAndCalculateCoupon(
					request.getCouponId(),
					user.getId(),
					originalAmount
				);
				usedCouponId = request.getCouponId();
			}
			// B. 할인 코드를 직접 입력한 경우 (기존 로직)
			else if (request.getDiscountCode() != null && !request.getDiscountCode().isBlank()) {
				validationResult = discountCodeService.validateAndCalculateDiscountForPayment(
					request.getDiscountCode(),
					originalAmount,
					request.getSubCategoryId()
				);
			}
			// C. 아무것도 안 쓴 경우
			else {
				validationResult = new DiscountValidationResult(originalAmount, null, null);
			}

			Integer finalAmount = validationResult.getFinalAmount();
			String appliedCode = validationResult.getAppliedCode(); // 쿠폰명 or 할인코드

			// 할인 코드를 쓴 경우에만 값이 있고, 쿠폰을 쓴 경우엔 null임
			DiscountCode discountCodeEntity = validationResult.getDiscountCodeEntity();

			String merchantUid =
				"order_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString()
					.substring(0, 8);

			// 3. 주문서 생성
			Order savedOrder = orderRepository.save(
				Order.create(
					merchantUid,
					user.getId(),
					subCategory.getId(),
					originalAmount,
					finalAmount,
					appliedCode,
					usedCouponId, // ✅ 여기에 위에서 저장해둔 usedCouponId 변수를 넘깁니다!
					OrderStatus.PENDING,
					user.getName(),
					user.getEmail()
				)
			);

			// 4. 사용 횟수 증가 (주문 생성 트랜잭션 내에서 즉시 처리)
			// [중요] 쿠폰 사용 처리
			if (usedCouponId != null) {
				couponService.useCoupon(usedCouponId);
				log.info("쿠폰 사용 처리 완료: couponId={}", usedCouponId);
			}
			// 기존 할인 코드 사용 처리
			else if (discountCodeEntity != null) {
				discountCodeService.incrementUsage(discountCodeEntity);
				log.info("할인 코드 사용 횟수 증가 완료: {}", appliedCode);
			}

			log.info("주문 생성 완료 (트랜잭션 커밋): orderId={}, merchantUid={}, amount={}",
				savedOrder.getId(), merchantUid, finalAmount);

			// 5. 프론트에 최종 결제액과 주문번호 전달
			return new OrderCreateResponse(
				savedOrder.getId(),
				merchantUid,
				finalAmount, // 프론트가 결제할 최종 금액
				subCategory.getTitle()
			);

		} catch (PaymentException e) {
			// 할인 코드 검증 실패 (만료, 횟수 초과 등)
			log.warn("할인/쿠폰 처리 실패: {}", e.getMessage());
			throw e; // 400 Bad Request로 프론트에 전달
		} catch (Exception e) {
			// 기타 DB 오류 등
			log.error("주문 생성 중 심각한 오류 발생: {}", e.getMessage(), e);
			throw new PaymentException("주문 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
		}
	}

	// 2단계. 결제 상태 조회 ..
	@Transactional  // readOnly 제거!
	public Order completePayment(PaymentCompleteRequest request) {
		log.info("결제 완료 요청 및 검증: paymentId={}, merchantUid={}",
			request.getPaymentId(), request.getMerchantUid());

		// 비관적 락으로 주문 조회
		Order order = orderRepository.findByMerchantUidWithLock(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		// 멱등성 보장: 이미 처리된 주문이면 바로 반환
		if (order.getStatus() == OrderStatus.PAID) {
			log.info("이미 처리된 주문입니다. orderId={}", order.getId());
			return order;
		}

		if (paymentRepository.findByImpUid(request.getPaymentId()).isPresent()) {
			log.warn("이미 존재하는 결제입니다: paymentId={}", request.getPaymentId());
			throw new PaymentException("이미 처리된 결제입니다.");
		}

		// 포트원 API 조회를 통한 2차 검증 ..
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(
			request.getPaymentId());

		// 금액 검증
		if (!Objects.equals(paymentResponse.getAmount().getTotal(),
			order.getAmount().longValue())) {
			throw new PaymentException("결제 금액이 일치하지 않습니다.");
		}

		// 포트원 상태가 PAID라면 즉시 DB 업데이트
		PaymentStatus paymentStatus = PaymentStatus.fromPortOneStatus(paymentResponse.getStatus());

		if (paymentStatus == PaymentStatus.PAID) {
			log.info("검증 완료. 주문 상태를 PAID로 변경합니다.");

			// 1. 주문 PAID 확정, Payment 저장, 연관관계 연결, 초기 Result 생성
			Payment savedPayment = paidOrderFinalizer.finalizePaid(
				order,
				request.getPaymentId(),
				paymentResponse.getAmount().getTotal(),
				LocalDateTime.now()
			);

			// 2. Discord 알림 (선택사항)
			notifyPaymentCompleted(order, savedPayment);

			log.info("completePayment에서 결제 처리 완료: orderId={}, paymentId={}",
				order.getId(), request.getPaymentId());

			return order;  // 이제 PAID 상태로 반환
		}

		// PAID가 아닌 경우
		log.warn("결제가 아직 완료되지 않았습니다: status={}", paymentStatus);
		return order;
	}

	@Transactional
	public OrderCreateResponse redeemFreeProduct(String username, OrderCreateRequest request) {
		log.info("0원 결제(무료 제공) 요청: username={}, subCategoryId={}", username,
			request.getSubCategoryId());

		// 1. 사용자 조회
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		// 2. 상품 조회
		SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));
		Integer originalAmount = subCategory.getPrice();

		// 3. 할인 코드 재검증
		DiscountValidationResult validationResult = discountCodeService.validateAndCalculateDiscountForPayment(
			request.getDiscountCode(),
			originalAmount,
			request.getSubCategoryId()
		);

		// 4. 0원 할인 검증
		if (validationResult.getFinalAmount() != 0) {
			log.warn("0원 결제 시도 실패: 최종 금액이 0원이 아닙니다. ({}원)", validationResult.getFinalAmount());
			throw new PaymentException("유효한 100% 할인 코드가 아닙니다.");
		}

		// 5. 0원짜리 Order, Payment, Result 동시 생성 (하나의 트랜잭션)

		// 5-1. Order 생성 (상태: PAID)
		String merchantUid =
			"free_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString()
				.substring(0, 8);
		Order order = Order.create(
			merchantUid,
			user.getId(),
			subCategory.getId(),
			originalAmount,
			0, // finalAmount = 0
			request.getDiscountCode(),
			null,
			OrderStatus.PENDING, // PAID 전이는 finalizePaid 의 markPaid 가 담당한다
			user.getName(),
			user.getEmail()
		);

		// 5-2. 주문 PAID 확정, 0원 Payment 저장, 연관관계 연결, 초기 Result 생성
		String paymentId = "free_" + merchantUid; // 포트원 paymentId 가 없으니 merchantUid 로 대신한다.
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, paymentId, 0L,
			LocalDateTime.now());

		// 5-3. 할인 코드 사용 횟수 증가
		discountCodeService.incrementUsage(validationResult.getDiscountCodeEntity());

		log.info("0원 결제(무료 제공) 처리 완료: paymentId(PK)={}, orderId={}", savedPayment.getId(),
			order.getId());

		// 6. 응답 반환
		return new OrderCreateResponse(
			order.getId(),
			order.getMerchantUid(),
			order.getAmount(),
			subCategory.getTitle()
		);
	}

	@Transactional
	public Payment createFreeOrder(String username, Long subCategoryId) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));

		String merchantUid =
			"free_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString()
				.substring(0, 8);

		// 1. Order 생성 (finalizePaid 에서 PAID 로 전이)
		Order order = Order.create(
			merchantUid,
			user.getId(),
			subCategory.getId(),
			0,  // 원가 0원
			0,  // 결제 금액 0원
			"EVENT_FREE", // 무료 이벤트 표기,
			null,
			OrderStatus.PENDING, // PAID 전이는 finalizePaid 의 markPaid 가 담당한다
			user.getName(),
			user.getEmail()
		);

		// 2. 주문 PAID 확정, 0원 Payment 저장, 연관관계 연결, 초기 Result 생성
		String paymentId = "pay_free_" + merchantUid;
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, paymentId, 0L,
			LocalDateTime.now());

		log.info("무료 사주 주문 생성 완료: orderId={}, paymentId={}", order.getId(),
			savedPayment.getId());
		return savedPayment;
	}

	public void processWebhook(String body) {
		String merchantUidFromCustomData = null;
		try {
			log.info("=== 웹훅 원본 페이로드 ===");
			log.info("body: {}", body);

			PortoneWebhookDto webhook = objectMapper.readValue(body, PortoneWebhookDto.class);

			log.info("웹훅 수신: status={}, paymentId={}",
				webhook.getStatus(), webhook.getPaymentId());

			String paymentId = webhook.getPaymentId();
			String status = webhook.getStatus();
			String merchantUid = webhook.getMerchantUid();

			log.info("webhook.getStatus(): {}", webhook.getStatus());
			log.info("webhook.getPaymentId(): {}", webhook.getPaymentId());
			log.info("webhook.getMerchantUid(): {}", webhook.getMerchantUid());

			// Ready 상태는 결제 완료가 아님 (가상계좌 발급, 결제 시작 등)
			if (!"Paid".equals(status)) {
				log.info("결제 완료 이벤트가 아님: status={}", status);
				return;
			}

			// ✅ 포트원 API 호출 전에 로그
			log.info("포트원 API 호출 시작: paymentId={}", paymentId);

			// 포트원에 결제 됐는지 재확인함
			PortOnePaymentResponse paymentResponse = portOneClient.getPayment(paymentId);

			log.info("포트원 API 호출 완료");
			log.info("PortOnePaymentResponse: " + paymentResponse);

			String customDataString = paymentResponse.getCustomData();
			if (customDataString == null || customDataString.isBlank()) {
				log.error("PortOne API 응답(paymentId:{})에 customData가 비어있습니다!", paymentId);
				throw new PaymentException("결제 API 응답에서 customData를 찾을 수 없어 주문 번호를 알 수 없습니다.");
			}

			log.info("customData 원본: '{}'", customDataString);

			if (customDataString.isBlank()) {
				log.error("customData가 비어있음!");
				throw new PaymentException("customData 없음");
			}

			try {
				// customData 문자열을 JSON 객체로 파싱
				JsonNode customDataJson = objectMapper.readTree(customDataString);

				log.info("JSON 파싱 완료: {}", customDataJson);

				// ✅ merchantUid 추출 전에 로그
				log.info("merchantUid 추출 시작");
				if (customDataJson.has("merchantUid")) {
					merchantUidFromCustomData = customDataJson.get("merchantUid").asText();
					log.info("customData에서 merchantUid 추출 성공: {}", merchantUidFromCustomData);
				}

				// 추출한 merchantUid 검증
				if (merchantUidFromCustomData == null || merchantUidFromCustomData.isBlank()) {
					log.error("customData JSON 안에 'merchantUid' 필드가 없거나 비어있습니다! customData: {}",
						customDataString);
					log.error(
						"프론트엔드 customData 형식을 확인하세요. 예: { \"merchantUid\": \"order_...\", ... }");
					throw new PaymentException(
						"결제 API 응답의 customData에서 유효한 주문 번호(merchantUid)를 추출할 수 없습니다.");
				}

			} catch (JsonProcessingException e) {
				log.error("customData 문자열 JSON 파싱 실패! customData: {}", customDataString, e);
				log.error("프론트엔드에서 customData를 올바른 JSON 문자열 형태로 전달했는지 확인하세요.");
				throw new PaymentException("결제 API 응답의 customData 파싱 중 오류 발생");
			}

			// ✅ 비관적 락으로 주문 조회
			log.info("추출한 merchantUid '{}'로 주문을 조회합니다...", merchantUidFromCustomData);
			Order order = orderRepository.findByMerchantUidWithLock(merchantUidFromCustomData)
				.orElseThrow(EntityNotFoundException::new);
			log.info("주문 조회 성공: orderId={}, currentStatus={}", order.getId(), order.getStatus());

			// ✅ 멱등성 체크
			if (order.getStatus() == OrderStatus.PAID) {
				log.info("이미 처리된 주문: merchantUid={}", merchantUid);
				return;
			}

			if (paymentRepository.findByImpUid(paymentId).isPresent()) {
				log.warn("이미 존재하는 결제입니다: paymentId={}", paymentId);
				return;
			}

			// 2. 금액 검증
			if (!Objects.equals(paymentResponse.getAmount().getTotal(),
				order.getAmount().longValue())) {
				log.error("웹훅 금액 불일치: expected={}, actual={}",
					order.getAmount(), paymentResponse.getAmount().getTotal());
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 금액이 일치하지 않습니다.");
			}

			PaymentStatus paymentStatus = PaymentStatus.fromPortOneStatus(
				paymentResponse.getStatus());

			if (paymentStatus == PaymentStatus.PAID) {
				// 주문 PAID 확정, Payment 저장, 연관관계 연결, 초기 Result 생성
				Payment savedPayment = paidOrderFinalizer.finalizePaid(
					order,
					paymentId,
					paymentResponse.getAmount().getTotal(),
					LocalDateTime.now()
				);
				log.info("웹훅으로 결제 완료 처리: orderId={}, paymentId={}",
					order.getId(), paymentId);

				// 할인 코드 써서 결제했다면, 로그 남기기.
				if (order.getAppliedDiscountCode() != null &&
					!order.getAppliedDiscountCode().isEmpty()) {

					int discountAmount = order.getOriginalAmount() - order.getAmount();
					double discountRate =
						(discountAmount / (double) order.getOriginalAmount()) * 100;

					log.info("주문 ID: {}", order.getId());
					log.info("사용한 할인 코드: {}", order.getAppliedDiscountCode());
					log.info("할인액: {}원 ({}% 할인)", discountAmount,
						String.format("%.1f", discountRate));
				}

				notifyPaymentCompleted(order, savedPayment);

				processOrder(order);
			} else {
				log.error("웹훅 결제 실패: paymentId={}, status={}", paymentId, paymentStatus);
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 실패 상태입니다.");
			}
		} catch (Exception e) {
			log.error("웹훅 처리 중 에러", e);
			throw new PaymentException("웹훅 처리 실패: " + e.getMessage());
		}
	}

	@Transactional(readOnly = true)
	public Payment getPayment(Long paymentId) {
		return paymentRepository.findById(paymentId).orElseThrow(EntityNotFoundException::new);
	}

	@Transactional(readOnly = true)
	public List<PaymentResponseDto> getPayments(String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

		List<Payment> payments = paymentRepository.findAllByUserIdOrderByCreatedAtDesc(
			user.getId());

		// 1. 조회된 결제들의 ID 목록 추출
		List<Long> paymentIds = payments.stream().map(Payment::getId).toList();

		// 2. ResultRepository에 findByPaymentIdIn(List<Long> ids) 메서드를 만들어 한 번에 조회
		List<Result> results = resultRepository.findByPaymentIdIn(paymentIds);

		// 3. 매핑 편의를 위해 Map으로 변환 (paymentId -> ResultStatus)
		Map<Long, ResultStatus> statusMap = results.stream()
			.collect(Collectors.toMap(Result::getPaymentId, Result::getStatus));

		// 4. 조립
		return payments.stream().map(payment -> {
			ResultStatus status = statusMap.get(payment.getId());
			return PaymentResponseDto.create(payment, status);
		}).collect(Collectors.toList());
	}

	// ... 기존 메서드들 ...

	/**
	 * 사용자 직접 환불 처리 (ResultStatus가 INPUT_REQUIRED 일 때만 가능)
	 */
	@Transactional
	public void cancelPayment(String username, String paymentId, String reason) {
		// 1. 사용자 조회
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		// 2. 결제 정보 조회 (impUid로 조회)
		Payment payment = paymentRepository.findByImpUid(paymentId)
			.orElseThrow(() -> new PaymentException("결제 정보를 찾을 수 없습니다."));

		// 3. 권한 확인 (본인의 결제인지)
		if (!payment.getUserId().equals(user.getId())) {
			throw new PaymentException("본인의 결제 건만 취소할 수 있습니다.");
		}

		// ✅ 추가: 무료 결제(0원) 환불 시도 원천 차단
		if (payment.getAmount() == 0 || payment.getImpUid().startsWith("free_")) {
			throw new PaymentException("무료 이벤트 결제는 환불/취소 대상이 아닙니다.");
		}

		// 4. 이미 취소된 건인지 확인
		if (payment.getStatus() == PaymentStatus.CANCELLED) {
			throw new PaymentException("이미 취소된 결제입니다.");
		}

		// 5. Result 상태 검증 (핵심: 사주 정보를 입력하기 전인가?)
		Result result = resultRepository.findByPaymentId(payment.getId())
			.orElseThrow(() -> new PaymentException("해당 결제에 대한 결과 정보를 찾을 수 없습니다."));

		if (result.getStatus() != ResultStatus.INPUT_REQUIRED) {
			throw new PaymentException("이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.");
		}

		// 6. 포트원 API로 결제 취소 요청
		portOneClient.cancelPayment(payment.getImpUid(), reason);

		// 7. DB 상태 업데이트
		// 7-1. Payment 상태 변경
		// Payment 엔티티에 setStatus가 없다면 추가하거나 updateStatus 메서드 필요
		payment.updateStatus(PaymentStatus.CANCELLED);

		// 7-2. Order 상태 변경
		Order order = orderRepository.findById(payment.getOrderId())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));
		order.setStatus(OrderStatus.CANCELLED);

		// 7-3. Result 삭제 (정보 입력 전이므로 삭제)
		resultRepository.delete(result);

		if (order.getCouponId() != null) {
			// 쿠폰을 사용했던 주문이라면 쿠폰 복구
			couponService.restoreCoupon(order.getCouponId());
			log.info("환불로 인한 쿠폰 복구 완료: couponId={}", order.getCouponId());
		} else if (order.getAppliedDiscountCode() != null && !order.getAppliedDiscountCode()
			.isBlank()) {
			// 할인 코드를 사용했던 주문이라면 사용 횟수 복구
			discountCodeService.restoreDiscountUsage(order.getAppliedDiscountCode());
			log.info("환불로 인한 할인 코드 횟수 복구 완료: code={}", order.getAppliedDiscountCode());
		}

		log.info("사용자 환불 완료: username={}, paymentId={}, reason={}", username, paymentId, reason);
	}

	/**
	 * 결제 완료 Discord 알림. 알림 실패가 결제 처리에 영향을 주지 않도록 예외를 삼킨다.
	 */
	private void notifyPaymentCompleted(Order order, Payment payment) {
		try {
			User user = userRepository.findById(order.getUserId()).orElse(null);
			SubCategory subCategory = subCategoryRepository.findById(order.getSubCategoryId())
				.orElse(null);

			if (user != null && subCategory != null) {
				discordNotificationService.sendPaymentCompletedNotification(
					user.getName(),
					user.getEmail(),
					payment.getAmount(),
					subCategory.getTitle(),
					order.getPaidAt(),
					order.getAppliedDiscountCode(),
					order.getOriginalAmount()
				);
			} else {
				log.warn(
					"Discord 결제 알림 및 사주 결과 생성 완료 이메일 전송 실패: 사용자(ID:{}) 또는 상품(ID:{}) 정보를 찾을 수 없습니다.",
					order.getUserId(), order.getSubCategoryId());
			}
		} catch (Exception e) {
			// 알림 실패가 결제 처리에 영향을 주지 않도록 try-catch로 감쌉니다.
			log.error("Discord 결제 알림 전송 중 오류 (무시됨)", e);
		}
	}

	private void processOrder(Order order) {
		// TODO: 실제 비즈니스 로직 구현
		// - 이메일 발송
		// - 해석 정보 전달 등등 ..

		log.info("주문 처리 완료: orderId	={}, subCategoryId={}",
			order.getId(), order.getSubCategoryId());
	}
}
