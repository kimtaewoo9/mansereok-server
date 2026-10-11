package com.mansereok.server.domain.payment.service;


import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.dto.response.PortoneWebhookDto;
import com.mansereok.server.domain.payment.dto.response.WebhookCustomData;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
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

	private final OrderDiscountRestorer orderDiscountRestorer;

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

	/**
	 * 2단계. 결제 완료 검증. 클라이언트가 보낸 paymentId 와 merchantUid 를 그대로 믿지 않는다.
	 *
	 * <p>순서: 요청자 조회 → 주문 잠금 → 소유자 대조(403) → PAID 멱등 반환 → 결제 중복 선검사 → 포트원 조회
	 * → customData 의 merchantUid 대조(400) → 금액 검증 → 확정.
	 *
	 * @throws AccessDeniedException 요청자가 주문 소유자가 아닐 때 (403)
	 * @throws PaymentException      주문 없음 · 결제 중복 · 주문 번호 불일치 · 금액 불일치 (400)
	 */
	@Transactional  // readOnly 제거!
	public Order completePayment(String username, PaymentCompleteRequest request) {
		log.info("결제 완료 요청 및 검증: username={}, paymentId={}, merchantUid={}",
			username, request.getPaymentId(), request.getMerchantUid());

		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		// 비관적 락으로 주문 조회
		Order order = orderRepository.findByMerchantUidWithLock(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		// 소유자 대조. 멱등 반환보다 먼저 해서 타인의 PAID 주문 정보도 새지 않게 한다.
		assertOrderOwnedBy(order, user);

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

		// 결제와 주문의 결합 검증: 포트원에 기록된 주문 번호가 잠근 주문과 같아야 한다
		assertCustomDataMatchesOrder(order, request.getPaymentId(), paymentResponse);

		// 금액 검증
		if (!Objects.equals(paymentResponse.getAmount().getTotal(),
			order.getAmount().longValue())) {
			throw new PaymentException("결제 금액이 일치하지 않습니다.");
		}

		// 포트원 상태가 PAID라면 즉시 DB 업데이트. 모르는 상태는 "아직 완료되지 않음" 으로 보고 주문을 그대로 돌려준다.
		Optional<PaymentStatus> paymentStatus = PaymentStatus.fromPortOneStatus(
			paymentResponse.getStatus());
		if (paymentStatus.isEmpty()) {
			log.warn("알 수 없는 포트원 결제 상태라 미완료로 취급합니다: orderId={}, paymentId={}, rawStatus={}",
				order.getId(), request.getPaymentId(), paymentResponse.getStatus());
			return order;
		}

		if (paymentStatus.get() == PaymentStatus.PAID) {
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
		log.warn("결제가 아직 완료되지 않았습니다: status={}", paymentStatus.get());
		return order;
	}

	/**
	 * 주문 소유자와 요청자를 대조한다. 탈퇴 처리로 userId 가 null 인 주문은 누구의 것도 아니므로 거부한다.
	 */
	private void assertOrderOwnedBy(Order order, User user) {
		if (order.getUserId() == null || !Objects.equals(order.getUserId(), user.getId())) {
			log.warn("권한 없는 결제 완료 시도: 요청자={}, 주문 소유자={}, orderId={}",
				user.getId(), order.getUserId(), order.getId());
			throw new AccessDeniedException("본인의 주문만 결제 완료 처리할 수 있습니다.");
		}
	}

	/**
	 * 포트원 응답 customData 의 merchantUid 를 잠근 주문의 merchantUid(요청값이 아니라 DB 값)와 대조한다.
	 * 결제 한 건이 다른 주문에 붙는 것을 막는다.
	 *
	 * <p>customData 가 비어 있으면 하위 호환을 위해 warn 로그만 남기고 통과한다(customData 를 싣지 않는
	 * 예전 클라이언트·수동 결제). 형식이 어긋난 customData 는 {@link WebhookCustomData#from} 의
	 * PaymentException 이 그대로 전파된다.
	 *
	 * @throws PaymentException customData 의 merchantUid 가 주문의 merchantUid 와 다른 경우
	 */
	private void assertCustomDataMatchesOrder(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		String customData = paymentResponse.getCustomData();
		if (customData == null || customData.isBlank()) {
			log.warn("포트원 응답에 customData 가 없어 주문 번호 대조를 건너뜁니다: orderId={}, paymentId={}",
				order.getId(), paymentId);
			return;
		}

		String paidMerchantUid = WebhookCustomData.from(customData, objectMapper).merchantUid();
		if (!Objects.equals(paidMerchantUid, order.getMerchantUid())) {
			log.warn("결제 정보의 주문 번호 불일치: orderId={}, orderMerchantUid={}, customDataMerchantUid={}, paymentId={}",
				order.getId(), order.getMerchantUid(), paidMerchantUid, paymentId);
			throw new PaymentException("결제 정보의 주문 번호가 일치하지 않습니다.");
		}
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

	@Transactional
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
	 * 해석 요청에 실린 paymentId(PK) 가 요청자 본인의 결제 완료 건인지 확인한다.
	 *
	 * <p>존재하지 않음 · 미결제 · 타인 소유를 모두 같은 메시지로 거부해 paymentId 열거로 상태를
	 * 알아낼 수 없게 한다.
	 */
	@Transactional(readOnly = true)
	public void verifyPaidOwnership(Long paymentPkId, String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		Payment payment = paymentPkId == null ? null
			: paymentRepository.findById(paymentPkId).orElse(null);

		if (payment == null
			|| payment.getStatus() != PaymentStatus.PAID
			|| !Objects.equals(payment.getUserId(), user.getId())) {
			log.warn("유효하지 않은 결제로 해석 요청: username={}, paymentPkId={}, exists={}, status={}",
				username, paymentPkId, payment != null,
				payment == null ? null : payment.getStatus());
			throw new PaymentException("유효한 결제 정보가 아닙니다.");
		}
	}

	/**
	 * 포트원 웹훅 처리. 서명 검증은 컨트롤러가 마친 뒤 호출한다.
	 *
	 * <p>순서: 페이로드 파싱 → Paid 이벤트 필터 → 포트원 재조회 → 주문 잠금과 멱등 검사 → 금액 검증 → 확정.
	 *
	 * <p>금액 불일치와 결제 실패 상태는 재전송으로 해결되지 않는 최종 실패라서 예외 없이 주문을 FAILED 로 기록하고
	 * 정상 반환한다. 예외를 던지면 같은 트랜잭션의 FAILED 저장이 롤백되고 포트원이 재시도를 반복하므로, 정상 반환으로
	 * FAILED 를 커밋하고 포트원에는 200 을 돌려준다. 포트원 일시 장애(PortOneUnavailableException, 503)와
	 * DB 장애(500)는 그대로 전파해 포트원이 재시도하게 둔다.
	 *
	 * <p>트랜잭션은 클래스 수준 {@code @Transactional} 에 참여한다. 주문 잠금부터 FAILED 저장·할인 복구까지
	 * 한 트랜잭션이다.
	 */
	public void processWebhook(String body) {
		PortoneWebhookDto webhook = parseWebhookBody(body);
		String paymentId = webhook.getPaymentId();
		log.info("웹훅 수신: status={}, paymentId={}", webhook.getStatus(), paymentId);

		// Ready 상태는 결제 완료가 아님 (가상계좌 발급, 결제 시작 등)
		if (!"Paid".equals(webhook.getStatus())) {
			log.info("결제 완료 이벤트가 아님: status={}, paymentId={}", webhook.getStatus(), paymentId);
			return;
		}

		// 웹훅 본문은 신뢰하지 않고 포트원 API 로 재조회한다
		PortOnePaymentResponse paymentResponse = portOneClient.getPayment(paymentId);
		String merchantUid = WebhookCustomData.from(paymentResponse.getCustomData(), objectMapper)
			.merchantUid();

		Optional<Order> unprocessed = lockUnprocessedOrder(merchantUid, paymentId);
		if (unprocessed.isEmpty()) {
			return;
		}
		Order order = unprocessed.get();

		if (!verifyWebhookAmount(order, paymentResponse)) {
			return;
		}

		confirmByPortOneStatus(order, paymentId, paymentResponse);
	}

	private PortoneWebhookDto parseWebhookBody(String body) {
		try {
			return objectMapper.readValue(body, PortoneWebhookDto.class);
		} catch (JsonProcessingException e) {
			log.error("웹훅 페이로드 파싱 실패", e);
			throw new PaymentException("웹훅 페이로드 파싱 실패");
		}
	}

	/**
	 * 주문을 비관적 락으로 조회하고 멱등 검사를 한다. 이미 처리된 주문(PAID 이거나 같은 paymentId 의 Payment 가
	 * 있음)이면 빈 Optional 을 돌려준다.
	 *
	 * @throws PaymentException merchantUid 에 해당하는 주문이 없는 경우
	 */
	private Optional<Order> lockUnprocessedOrder(String merchantUid, String paymentId) {
		Order order = orderRepository.findByMerchantUidWithLock(merchantUid)
			.orElseThrow(() -> {
				log.error("웹훅 주문 조회 실패: merchantUid={}, paymentId={}", merchantUid, paymentId);
				return new PaymentException("주문을 찾을 수 없습니다.");
			});

		if (order.getStatus() == OrderStatus.PAID) {
			log.info("이미 처리된 주문: orderId={}, merchantUid={}", order.getId(), merchantUid);
			return Optional.empty();
		}

		if (paymentRepository.findByImpUid(paymentId).isPresent()) {
			log.warn("이미 존재하는 결제입니다: paymentId={}", paymentId);
			return Optional.empty();
		}

		return Optional.of(order);
	}

	/**
	 * 포트원 결제 금액과 주문 금액을 비교한다. 불일치는 최종 실패이므로 주문을 FAILED 로 기록하고 false 를 돌려준다.
	 */
	private boolean verifyWebhookAmount(Order order, PortOnePaymentResponse paymentResponse) {
		Long paidTotal = paymentResponse.getAmount().getTotal();
		if (Objects.equals(paidTotal, order.getAmount().longValue())) {
			return true;
		}
		log.error("웹훅 금액 불일치: orderId={}, expected={}, actual={}",
			order.getId(), order.getAmount(), paidTotal);
		markOrderFailed(order);
		return false;
	}

	/**
	 * 포트원 재조회 상태로 주문을 확정한다.
	 *
	 * <ul>
	 *   <li>PAID → 결제 확정</li>
	 *   <li>READY, VIRTUAL_ACCOUNT_ISSUED → 진행 중이므로 "아직 완료되지 않음" 으로 보고 주문을 건드리지 않는다.
	 *       Paid 웹훅과 조회 API 반영 사이의 지연 같은 일시 상태를 종단 상태 FAILED 로 굳히지 않기 위해서다.</li>
	 *   <li>FAILED, CANCELLED → 최종 실패로 FAILED 기록</li>
	 *   <li>모르는 상태 → "아직 완료되지 않음" 으로 보고 주문을 건드리지 않는다</li>
	 * </ul>
	 */
	private void confirmByPortOneStatus(Order order, String paymentId,
		PortOnePaymentResponse paymentResponse) {
		Optional<PaymentStatus> paymentStatus = PaymentStatus.fromPortOneStatus(
			paymentResponse.getStatus());
		if (paymentStatus.isEmpty()) {
			log.warn("알 수 없는 포트원 결제 상태라 미완료로 취급합니다: orderId={}, paymentId={}, rawStatus={}",
				order.getId(), paymentId, paymentResponse.getStatus());
			return;
		}

		PaymentStatus resolved = paymentStatus.get();
		if (resolved == PaymentStatus.READY || resolved == PaymentStatus.VIRTUAL_ACCOUNT_ISSUED) {
			log.warn("결제가 아직 완료되지 않았습니다: orderId={}, paymentId={}, status={}",
				order.getId(), paymentId, resolved);
			return;
		}

		if (resolved != PaymentStatus.PAID) { // FAILED, CANCELLED
			log.error("웹훅 결제 실패 상태: orderId={}, paymentId={}, status={}",
				order.getId(), paymentId, paymentResponse.getStatus());
			markOrderFailed(order);
			return;
		}

		// 주문 PAID 확정, Payment 저장, 연관관계 연결, 초기 Result 생성
		Payment savedPayment = paidOrderFinalizer.finalizePaid(
			order,
			paymentId,
			paymentResponse.getAmount().getTotal(),
			LocalDateTime.now()
		);
		log.info("웹훅으로 결제 완료 처리: orderId={}, paymentId={}, discountCode={}, amount={}/{}",
			order.getId(), paymentId, order.getAppliedDiscountCode(), order.getAmount(),
			order.getOriginalAmount());

		notifyPaymentCompleted(order, savedPayment);
	}

	/**
	 * 주문을 FAILED 로 기록하고 쓴 쿠폰·할인코드를 복구한다. FAILED 로 갈 수 없는 상태(EXPIRED 등)면 상태는
	 * 그대로 두고 복구도 하지 않는다(만료 경로가 이미 복구했다).
	 *
	 * <p>예외를 던지지 않으므로 호출자의 트랜잭션이 커밋되며 FAILED 가 실제로 저장된다. FAILED 는 종단 상태라
	 * 만료 스케줄러(PENDING 만 조회)가 다시 다루지 않으므로, 환불·만료와 같은 규칙으로 여기서 바로 복구한다.
	 */
	private void markOrderFailed(Order order) {
		if (!order.getStatus().canTransitionTo(OrderStatus.FAILED)) {
			log.warn("FAILED 로 전이할 수 없는 주문 상태라 그대로 둡니다: orderId={}, status={}",
				order.getId(), order.getStatus());
			return;
		}
		order.markFailed();
		orderRepository.save(order);
		orderDiscountRestorer.restore(order); // 환불·만료와 같은 규칙, 같은 트랜잭션에 참여
		log.info("주문을 FAILED 로 기록: orderId={}, merchantUid={}", order.getId(),
			order.getMerchantUid());
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
		if (payment.getAmount() == 0 || payment.getImpUid().startsWith(MerchantUidGenerator.FREE_PREFIX)) {
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

		// 6. 상태 전이 사전 검사. 포트원 환불은 되돌릴 수 없으므로 markCancelled 가 던질 상황이면
		//    외부 호출 전에 먼저 거른다.
		if (payment.getStatus() != PaymentStatus.PAID) {
			throw new PaymentException("결제 완료 상태가 아니라 취소할 수 없습니다.");
		}
		Order order = orderRepository.findById(payment.getOrderId())
			.orElseThrow(() -> new PaymentException("주문 정보를 찾을 수 없습니다."));
		if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
			throw new PaymentException("취소할 수 없는 주문 상태입니다.");
		}

		// 7. 포트원 API로 결제 취소 요청
		portOneClient.cancelPayment(payment.getImpUid(), reason);

		// 8. DB 상태 업데이트
		// 8-1. Payment 상태 변경 (PAID 에서만 허용)
		payment.markCancelled();

		// 8-2. Order 상태 변경
		order.markCancelled();

		// 8-3. Result 삭제 (정보 입력 전이므로 삭제)
		resultRepository.delete(result);

		// 8-4. 쿠폰 또는 할인 코드 복구 (규칙은 OrderDiscountRestorer 가 소유)
		orderDiscountRestorer.restore(order);

		log.info("사용자 환불 완료: username={}, paymentId={}, reason={}", username, paymentId, reason);
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
}
