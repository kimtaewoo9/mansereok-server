package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 전 주문 생성과 0원 발급(100% 할인 코드·무료 이벤트)을 담당한다. 쿠폰/할인 코드 해석과 사용 확정도 여기서 한다.
 *
 * <p>세 공개 메서드는 모두 클래스 수준 {@code @Transactional} 에 참여한다. 주문 저장과 쿠폰·할인 코드 사용 확정이 한
 * 트랜잭션이라, 저장 실패 시 할인 코드 락과 사용 횟수가 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentOrderService {

	private final UserRepository userRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final OrderRepository orderRepository;
	private final DiscountCodeService discountCodeService;
	private final CouponService couponService;
	private final PaidOrderFinalizer paidOrderFinalizer;
	private final FreeProductPolicy freeProductPolicy;
	private final MerchantUidGenerator merchantUidGenerator;

	/**
	 * 쿠폰/할인코드 분기의 결과. 사용 확정(consumeDiscount)에 필요한 정보를 함께 담는다.
	 *
	 * @param finalAmount        할인 적용 후 금액
	 * @param appliedCode        주문에 기록할 코드(쿠폰명 또는 할인 코드), 없으면 null
	 * @param couponId           쿠폰을 쓴 경우 그 id, 아니면 null
	 * @param discountCodeEntity 할인 코드를 쓴 경우 그 엔티티, 아니면 null
	 */
	private record DiscountResolution(
		int finalAmount,
		String appliedCode,
		Long couponId,
		DiscountCode discountCodeEntity
	) {

	}

	// 1단계: 주문 생성 (결제 전)
	public OrderCreateResponse createOrder(String username, OrderCreateRequest request) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));

		Integer originalAmount = subCategory.getPrice(); // 1. 원본 금액 .

		// 2. 쿠폰/할인 코드 검증. 실패(PaymentException)는 GlobalExceptionHandler 가 400 으로,
		//    DB 장애 같은 예상 못 한 예외는 500 으로 응답하므로 여기서 다시 감싸지 않는다.
		DiscountResolution discount = resolveDiscount(user, subCategory, request);
		int finalAmount = discount.finalAmount();

		// 0원 주문은 결제창을 띄울 수 없고 무료 발급 절차(redeemFreeProduct)가 따로 있으므로
		// 여기서는 만들지 않는다. 할인 코드 락은 트랜잭션 롤백으로 함께 풀린다.
		if (finalAmount <= 0) {
			throw new PaymentException("0원 주문은 무료 결제 API(/api/payment/redeem-free)를 이용해주세요.");
		}

		String merchantUid = merchantUidGenerator.forOrder();

		// 3. 주문서 생성
		Order savedOrder = orderRepository.save(
			Order.create(
				merchantUid,
				user.getId(),
				subCategory.getId(),
				originalAmount,
				finalAmount,
				discount.appliedCode(),
				discount.couponId(),
				OrderStatus.PENDING,
				user.getName(),
				user.getEmail()
			)
		);

		// 4. 사용 횟수 증가 (주문 생성 트랜잭션 내에서 즉시 처리)
		consumeDiscount(discount);

		log.info("주문 생성 완료 (트랜잭션 커밋): orderId={}, merchantUid={}, amount={}",
			savedOrder.getId(), merchantUid, finalAmount);

		// 5. 프론트에 최종 결제액과 주문번호 전달
		return new OrderCreateResponse(
			savedOrder.getId(),
			merchantUid,
			finalAmount, // 프론트가 결제할 최종 금액
			subCategory.getTitle()
		);
	}

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

		// 3. 쿠폰/할인 코드 재검증 (createOrder 와 같은 규칙)
		DiscountResolution discount = resolveDiscount(user, subCategory, request);

		// 4. 0원 할인 검증
		if (discount.finalAmount() != 0) {
			log.warn("0원 결제 시도 실패: 최종 금액이 0원이 아닙니다. ({}원)", discount.finalAmount());
			throw new PaymentException("유효한 100% 할인 코드가 아닙니다.");
		}

		// 5. 0원짜리 Order, Payment, Result 동시 생성 (하나의 트랜잭션)

		// 5-1. Order 생성 (상태: PAID)
		String merchantUid = merchantUidGenerator.forFree();
		Order order = Order.create(
			merchantUid,
			user.getId(),
			subCategory.getId(),
			originalAmount,
			0, // finalAmount = 0
			discount.appliedCode(),
			discount.couponId(),
			OrderStatus.PENDING, // PAID 전이는 finalizePaid 의 markPaid 가 담당한다
			user.getName(),
			user.getEmail()
		);

		// 5-2. 주문 PAID 확정, 0원 Payment 저장, 연관관계 연결, 초기 Result 생성
		String paymentId = MerchantUidGenerator.FREE_PREFIX + merchantUid; // 포트원 paymentId 가 없으니 merchantUid 로 대신한다.
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, paymentId, 0L,
			LocalDateTime.now());

		// 5-3. 쿠폰 또는 할인 코드 사용 확정
		consumeDiscount(discount);

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

	public Payment createFreeOrder(String username, Long subCategoryId) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));

		// 무료 판정을 통과한 상품만 0원 PAID 로 발급한다 (유료 상품의 무료 발급 차단)
		if (!freeProductPolicy.isFree(subCategory)) {
			log.warn("유료 상품 무료 발급 시도 차단: username={}, subCategoryId={}", username,
				subCategoryId);
			throw new PaymentException("무료로 제공되는 상품이 아닙니다.");
		}

		String merchantUid = merchantUidGenerator.forFree();

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
		String paymentId = MerchantUidGenerator.FREE_PREFIX + merchantUid; // redeemFreeProduct 와 같은 규칙
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, paymentId, 0L,
			LocalDateTime.now());

		log.info("무료 사주 주문 생성 완료: orderId={}, paymentId={}", order.getId(),
			savedPayment.getId());
		return savedPayment;
	}

	/**
	 * 쿠폰/할인 코드 분기. 쿠폰이 우선이고, 없으면 할인 코드, 둘 다 없으면 원가 그대로다.
	 * 검증만 하고 사용 확정은 {@link #consumeDiscount(DiscountResolution)} 가 한다.
	 */
	private DiscountResolution resolveDiscount(User user, SubCategory subCategory,
		OrderCreateRequest request) {
		int originalAmount = subCategory.getPrice();

		// A. 쿠폰을 선택한 경우 (우선순위 높음)
		if (request.getCouponId() != null) {
			DiscountValidationResult result = couponService.validateAndCalculateCoupon(
				request.getCouponId(),
				user.getId(),
				originalAmount
			);
			return new DiscountResolution(result.getFinalAmount(), result.getAppliedCode(),
				request.getCouponId(), null);
		}

		// B. 할인 코드를 직접 입력한 경우
		if (request.getDiscountCode() != null && !request.getDiscountCode().isBlank()) {
			DiscountValidationResult result = discountCodeService.validateAndCalculateDiscountForPayment(
				request.getDiscountCode(),
				originalAmount,
				request.getSubCategoryId()
			);
			return new DiscountResolution(result.getFinalAmount(), result.getAppliedCode(), null,
				result.getDiscountCodeEntity());
		}

		// C. 아무것도 안 쓴 경우
		return new DiscountResolution(originalAmount, null, null, null);
	}

	/**
	 * 검증을 통과한 쿠폰/할인 코드의 사용을 확정한다. 주문 생성과 같은 트랜잭션 안에서 호출한다.
	 */
	private void consumeDiscount(DiscountResolution discount) {
		if (discount.couponId() != null) {
			couponService.useCoupon(discount.couponId());
			log.info("쿠폰 사용 처리 완료: couponId={}", discount.couponId());
		} else if (discount.discountCodeEntity() != null) {
			discountCodeService.incrementUsage(discount.discountCodeEntity());
			log.info("할인 코드 사용 횟수 증가 완료: {}", discount.appliedCode());
		}
	}
}
