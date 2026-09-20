package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	private static final String USERNAME = "testUser";
	private static final String BUYER_NAME = "김태우";
	private static final String BUYER_EMAIL = "taewoo@example.com";
	private static final Long USER_ID = 1L;
	private static final Long SUB_CATEGORY_ID = 1L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final int PRICE = 10000;

	private PaymentService paymentService;

	@Mock
	private DiscordNotificationService discordNotificationService;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private DiscountCodeService discountCodeService;
	@Mock
	private ResultRepository resultRepository;
	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;
	@Mock
	private ObjectMapper objectMapper;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ResultService resultService;
	@Mock
	private CouponService couponService;
	@Mock
	private PortOneClient portOneClient;
	@Mock
	private OrderDiscountRestorer orderDiscountRestorer;
	@Mock
	private FreeProductPolicy freeProductPolicy;

	@BeforeEach
	void setUp() {
		// completePayment 등이 "주문이 PAID 가 되고 Payment 와 Result 가 만들어진다"를 계속 검증하도록
		// PaidOrderFinalizer 는 mock 하지 않고 mock 리포지토리로 만든 실제 인스턴스를 넘긴다.
		PaidOrderFinalizer paidOrderFinalizer = new PaidOrderFinalizer(orderRepository,
			paymentRepository, resultService);
		paymentService = new PaymentService(
			discordNotificationService,
			orderRepository,
			subCategoryRepository,
			paymentRepository,
			discountCodeService,
			resultRepository,
			compatibilityResultRepository,
			objectMapper,
			userRepository,
			resultService,
			couponService,
			portOneClient,
			paidOrderFinalizer,
			orderDiscountRestorer,
			freeProductPolicy,
			new MerchantUidGenerator() // 접두사·형식 단언을 위해 실제 인스턴스를 쓴다
		);
	}

	// ===== 테스트 픽스처 =====

	private User createUser() {
		User user = User.create(USERNAME, BUYER_NAME, "password", BUYER_EMAIL,
			LocalDate.now(), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private SubCategory mockSubCategory() {
		// protected 생성자 대응. stub 이 들어있으므로 다른 given(...) 의 인자 안에서 호출하면 안 된다.
		// 공용 픽스처라 호출 경로가 바뀌어도 strict stubs 에 걸리지 않도록 lenient 로 둔다.
		SubCategory subCategory = mock(SubCategory.class);
		lenient().when(subCategory.getPrice()).thenReturn(PRICE);
		lenient().when(subCategory.getId()).thenReturn(SUB_CATEGORY_ID);
		lenient().when(subCategory.getTitle()).thenReturn("인생 총운");
		return subCategory;
	}

	private Order createOrder(OrderStatus status, String appliedDiscountCode, Long couponId) {
		Order order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, PRICE, PRICE,
			appliedDiscountCode, couponId, status, BUYER_NAME, BUYER_EMAIL);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
	}

	private Payment createPaidPayment() {
		Payment payment = Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE,
			PaymentStatus.PAID, ORDER_ID, USER_ID, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		return payment;
	}

	private PortOnePaymentResponse portOneResponse(String status, long total) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(PAYMENT_ID);
		response.setStatus(status);
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		response.setAmount(amount);
		return response;
	}

	private void givenOrderSaveReturnsArgument() {
		given(orderRepository.save(any(Order.class))).willAnswer(
			invocation -> invocation.getArgument(0));
	}

	/** 새 주문(id 없음)이 저장되면 ORDER_ID 를 부여해 DB 의 IDENTITY 채번을 흉내 낸다. */
	private void givenOrderSaveAssignsId() {
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order order = invocation.getArgument(0);
			if (order.getId() == null) {
				ReflectionTestUtils.setField(order, "id", ORDER_ID);
			}
			return order;
		});
	}

	private void givenPaymentSaveAssignsId() {
		given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
			return payment;
		});
	}

	// ===== createOrder =====

	@Test
	@DisplayName("주문 생성 시 구매자 이름과 이메일이 Order 엔티티에 올바르게 저장되어야 한다")
	void createOrder_ShouldSaveBuyerInfo() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		givenOrderSaveReturnsArgument();

		// 쿠폰도 할인 코드도 없는 요청
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);

		// when
		paymentService.createOrder(USERNAME, request);

		// then
		// ArgumentCaptor를 사용하여 실제로 save 메서드에 전달된 Order 객체를 포획
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());

		Order savedOrder = orderCaptor.getValue();

		// 검증: Order 엔티티에 유저 정보(이름, 이메일)가 잘 박제되었는지 확인
		assertThat(savedOrder.getBuyerName()).isEqualTo(BUYER_NAME);
		assertThat(savedOrder.getBuyerEmail()).isEqualTo(BUYER_EMAIL);
		assertThat(savedOrder.getAmount()).isEqualTo(PRICE);
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);

		// 할인 수단이 없으면 할인 코드/쿠폰 서비스는 호출되지 않는다
		verifyNoInteractions(discountCodeService, couponService);
	}

	@Test
	@DisplayName("할인 코드를 보낸 주문은 할인 코드 검증을 거쳐 finalAmount 로 저장되고 사용 횟수가 증가한다")
	void createOrder_withDiscountCode_validatesAndSavesFinalAmount() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		givenOrderSaveReturnsArgument();

		DiscountCode discountCode = mock(DiscountCode.class);
		given(discountCodeService.validateAndCalculateDiscountForPayment("SALE10", PRICE,
			SUB_CATEGORY_ID))
			.willReturn(new DiscountValidationResult(9000, "SALE10", discountCode));

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setDiscountCode("SALE10");

		// when
		OrderCreateResponse response = paymentService.createOrder(USERNAME, request);

		// then
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();

		assertThat(savedOrder.getOriginalAmount()).isEqualTo(PRICE);
		assertThat(savedOrder.getAmount()).isEqualTo(9000);
		assertThat(savedOrder.getAppliedDiscountCode()).isEqualTo("SALE10");
		assertThat(savedOrder.getCouponId()).isNull();
		assertThat(response.getAmount()).isEqualTo(9000);

		verify(discountCodeService).incrementUsage(discountCode);
		verifyNoInteractions(couponService);
	}

	@Test
	@DisplayName("쿠폰 id 를 보낸 주문은 쿠폰 검증과 useCoupon 을 호출하고 할인 코드 서비스는 호출하지 않는다")
	void createOrder_withCouponId_usesCouponServiceOnly() {
		// given
		Long couponId = 5L;
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		givenOrderSaveReturnsArgument();

		given(couponService.validateAndCalculateCoupon(couponId, USER_ID, PRICE))
			.willReturn(new DiscountValidationResult(8000, "신규가입 쿠폰", null));

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setCouponId(couponId);

		// when
		OrderCreateResponse response = paymentService.createOrder(USERNAME, request);

		// then
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();

		assertThat(savedOrder.getAmount()).isEqualTo(8000);
		assertThat(savedOrder.getCouponId()).isEqualTo(couponId);
		assertThat(savedOrder.getAppliedDiscountCode()).isEqualTo("신규가입 쿠폰");
		assertThat(response.getAmount()).isEqualTo(8000);

		verify(couponService).useCoupon(couponId);
		verifyNoInteractions(discountCodeService);
	}

	@Test
	@DisplayName("100% 할인으로 최종 금액이 0원이면 주문을 만들지 않고 무료 결제 API 안내 메시지의 PaymentException 이 난다")
	void createOrder_zeroFinalAmount_throwsAndSavesNothing() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		DiscountCode discountCode = mock(DiscountCode.class);
		given(discountCodeService.validateAndCalculateDiscountForPayment("FREE100", PRICE,
			SUB_CATEGORY_ID))
			.willReturn(new DiscountValidationResult(0, "FREE100", discountCode));

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setDiscountCode("FREE100");

		// when & then
		assertThatThrownBy(() -> paymentService.createOrder(USERNAME, request))
			.isInstanceOf(PaymentException.class)
			.hasMessage("0원 주문은 무료 결제 API(/api/payment/redeem-free)를 이용해주세요.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(discountCodeService, never()).incrementUsage(any());
		verifyNoInteractions(couponService, paymentRepository, resultService);
	}

	// ===== completePayment =====

	@Test
	@DisplayName("포트원이 PAID 와 일치하는 금액을 돌려주면 주문이 PAID 가 되고 Payment 저장과 초기 Result 생성이 이뤄진다")
	void completePayment_paidAndAmountMatches_marksOrderPaid() {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);

		// when
		Order result = paymentService.completePayment(request);

		// then
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(result.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(result.getPaidAt()).isNotNull();
		assertThat(result.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);
		verify(orderRepository, atLeastOnce()).save(order);

		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(paymentCaptor.capture());
		Payment savedPayment = paymentCaptor.getValue();
		assertThat(savedPayment.getImpUid()).isEqualTo(PAYMENT_ID);
		assertThat(savedPayment.getMerchantUid()).isEqualTo(MERCHANT_UID);
		assertThat(savedPayment.getAmount()).isEqualTo((long) PRICE);
		assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(savedPayment.getUserId()).isEqualTo(USER_ID);

		verify(resultService).createInitialResult(savedPayment, order);
	}

	@Test
	@DisplayName("결제 완료 시 사용자와 상품 정보로 Discord 알림이 주문의 금액, 시각, 할인 정보와 함께 전송된다")
	void completePayment_paid_sendsDiscordNotificationWithOrderDetails() {
		// given
		Order order = createOrder(OrderStatus.PENDING, "WELCOME10", null);
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();
		SubCategory subCategory = mockSubCategory(); // stub 이 든 헬퍼는 given(...) 인자 밖에서 먼저 만든다
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(createUser()));
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));

		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);

		// when
		Order result = paymentService.completePayment(request);

		// then
		verify(discordNotificationService).sendPaymentCompletedNotification(
			BUYER_NAME, BUYER_EMAIL, (long) PRICE, "인생 총운", result.getPaidAt(),
			"WELCOME10", PRICE);
	}

	@Test
	@DisplayName("포트원 결제 금액이 주문 금액과 다르면 PaymentException 이 나고 Payment 는 저장되지 않는다")
	void completePayment_amountMismatch_throwsAndDoesNotSavePayment() {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponse("PAID", PRICE - 1000));

		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);

		// when & then
		assertThatThrownBy(() -> paymentService.completePayment(request))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 금액이 일치하지 않습니다.");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(orderRepository, never()).save(any(Order.class));
		verifyNoInteractions(resultService);
	}

	@Test
	@DisplayName("이미 PAID 인 주문은 포트원을 조회하지 않고 그대로 반환한다")
	void completePayment_alreadyPaid_returnsOrderWithoutCallingPortOne() {
		// given
		Order order = createOrder(OrderStatus.PAID, null, null);
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));

		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);

		// when
		Order result = paymentService.completePayment(request);

		// then
		assertThat(result).isSameAs(order);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		verifyNoInteractions(portOneClient, paymentRepository, resultService);
	}

	@Test
	@DisplayName("CANCELLED 주문에 결제 완료 요청이 오면 OrderStateException 이 나고 Payment 는 저장되지 않는다")
	void completePayment_cancelledOrder_throwsOrderStateException() {
		// given: 멱등 검사(PAID 조기 반환)는 통과하고 포트원 조회까지 간 뒤 markPaid 가드에서 걸린다
		Order order = createOrder(OrderStatus.CANCELLED, null, null);
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse("PAID", PRICE));

		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);

		// when & then
		assertThatThrownBy(() -> paymentService.completePayment(request))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED 에서 PAID 로");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isNull();
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, discordNotificationService);
	}

	// ===== redeemFreeProduct =====

	@Test
	@DisplayName("100% 할인 코드로 무료 상품을 받으면 PAID 주문과 0원 Payment 가 저장되고 Result 가 생성되며 Discord 알림은 보내지 않는다")
	void redeemFreeProduct_savesPaidOrderAndZeroPaymentAndCreatesResult() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		DiscountCode discountCode = mock(DiscountCode.class);
		given(discountCodeService.validateAndCalculateDiscountForPayment("FREE100", PRICE,
			SUB_CATEGORY_ID))
			.willReturn(new DiscountValidationResult(0, "FREE100", discountCode));
		givenOrderSaveAssignsId();
		givenPaymentSaveAssignsId();

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setDiscountCode("FREE100");

		// when
		OrderCreateResponse response = paymentService.redeemFreeProduct(USERNAME, request);

		// then
		// (a) PAID 주문과 0원 Payment 저장
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, atLeastOnce()).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedOrder.getOriginalAmount()).isEqualTo(PRICE);
		assertThat(savedOrder.getAmount()).isZero();
		assertThat(savedOrder.getAppliedDiscountCode()).isEqualTo("FREE100");
		assertThat(savedOrder.getMerchantUid()).startsWith("free_");
		assertThat(savedOrder.getPaidAt()).isNotNull();
		assertThat(savedOrder.getPaymentId()).isEqualTo("free_" + savedOrder.getMerchantUid());
		assertThat(savedOrder.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);
		assertThat(savedOrder.getBuyerName()).isEqualTo(BUYER_NAME);
		assertThat(savedOrder.getBuyerEmail()).isEqualTo(BUYER_EMAIL);

		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(paymentCaptor.capture());
		Payment savedPayment = paymentCaptor.getValue();
		assertThat(savedPayment.getImpUid()).isEqualTo("free_" + savedOrder.getMerchantUid());
		assertThat(savedPayment.getMerchantUid()).isEqualTo(savedOrder.getMerchantUid());
		assertThat(savedPayment.getAmount()).isZero();
		assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(savedPayment.getUserId()).isEqualTo(USER_ID);
		assertThat(savedPayment.getSubCategoryId()).isEqualTo(SUB_CATEGORY_ID);

		// (b) Result 생성
		verify(resultService).createInitialResult(savedPayment, savedOrder);

		// 할인 코드 사용 횟수 증가
		verify(discountCodeService).incrementUsage(discountCode);

		// (c) Discord 알림 없음, 포트원 호출 없음
		verifyNoInteractions(discordNotificationService, portOneClient);

		assertThat(response.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(response.getMerchantUid()).isEqualTo(savedOrder.getMerchantUid());
		assertThat(response.getAmount()).isZero();
		assertThat(response.getProductName()).isEqualTo("인생 총운");
	}

	@Test
	@DisplayName("할인 후 금액이 0원이 아니면 PaymentException 이 나고 주문·Payment·Result 는 만들어지지 않는다")
	void redeemFreeProduct_nonZeroAmount_throwsAndSavesNothing() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mock(SubCategory.class);
		given(subCategory.getPrice()).willReturn(PRICE);
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		given(discountCodeService.validateAndCalculateDiscountForPayment("SALE10", PRICE,
			SUB_CATEGORY_ID))
			.willReturn(new DiscountValidationResult(9000, "SALE10", mock(DiscountCode.class)));

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setDiscountCode("SALE10");

		// when & then
		assertThatThrownBy(() -> paymentService.redeemFreeProduct(USERNAME, request))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 100% 할인 코드가 아닙니다.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(discountCodeService, never()).incrementUsage(any());
		verifyNoInteractions(resultService, discordNotificationService);
	}

	@Test
	@DisplayName("couponId 로 무료 상품을 받으면 쿠폰 검증과 useCoupon 을 거치고 주문에 couponId 가 기록되며 할인 코드 서비스는 호출하지 않는다")
	void redeemFreeProduct_withCouponId_validatesAndUsesCoupon() {
		// given
		Long couponId = 7L;
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		given(couponService.validateAndCalculateCoupon(couponId, USER_ID, PRICE))
			.willReturn(new DiscountValidationResult(0, "무료 쿠폰", null));
		givenOrderSaveAssignsId();
		givenPaymentSaveAssignsId();

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);
		request.setCouponId(couponId);

		// when
		OrderCreateResponse response = paymentService.redeemFreeProduct(USERNAME, request);

		// then
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, atLeastOnce()).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedOrder.getAmount()).isZero();
		assertThat(savedOrder.getCouponId()).isEqualTo(couponId);
		assertThat(savedOrder.getAppliedDiscountCode()).isEqualTo("무료 쿠폰");
		assertThat(savedOrder.getMerchantUid()).startsWith("free_");

		verify(couponService).useCoupon(couponId);
		verifyNoInteractions(discountCodeService);
		verify(resultService).createInitialResult(any(Payment.class), any(Order.class));
		assertThat(response.getAmount()).isZero();
	}

	// ===== createFreeOrder =====

	@Test
	@DisplayName("무료 판정을 통과하지 못한 유료 상품은 PaymentException 이 나고 주문·Payment·Result 는 만들어지지 않는다")
	void createFreeOrder_paidProduct_throwsAndSavesNothing() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mock(SubCategory.class);
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		given(freeProductPolicy.isFree(subCategory)).willReturn(false);

		// when & then
		assertThatThrownBy(() -> paymentService.createFreeOrder(USERNAME, SUB_CATEGORY_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage("무료로 제공되는 상품이 아닙니다.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, discordNotificationService, discountCodeService,
			couponService);
	}

	@Test
	@DisplayName("무료 이벤트 주문은 원가 0원의 PAID 주문과 free_ 접두사의 0원 Payment 가 저장되고 Result 가 생성되며 Discord 알림은 보내지 않는다")
	void createFreeOrder_savesPaidOrderAndZeroPaymentAndCreatesResult() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		// createFreeOrder 는 상품의 id 만 쓰므로 strict stubs 를 위해 getId 만 stub 한다
		SubCategory subCategory = mock(SubCategory.class);
		given(subCategory.getId()).willReturn(SUB_CATEGORY_ID);
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		given(freeProductPolicy.isFree(subCategory)).willReturn(true);
		givenOrderSaveAssignsId();
		givenPaymentSaveAssignsId();

		// when
		Payment returned = paymentService.createFreeOrder(USERNAME, SUB_CATEGORY_ID);

		// then
		// (a) PAID 주문과 0원 Payment 저장
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, atLeastOnce()).save(orderCaptor.capture());
		Order savedOrder = orderCaptor.getValue();
		assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedOrder.getOriginalAmount()).isZero();
		assertThat(savedOrder.getAmount()).isZero();
		assertThat(savedOrder.getAppliedDiscountCode()).isEqualTo("EVENT_FREE");
		assertThat(savedOrder.getCouponId()).isNull();
		assertThat(savedOrder.getMerchantUid()).startsWith("free_");
		assertThat(savedOrder.getPaidAt()).isNotNull();
		assertThat(savedOrder.getPaymentId()).isEqualTo("free_" + savedOrder.getMerchantUid());
		assertThat(savedOrder.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);

		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(paymentCaptor.capture());
		Payment savedPayment = paymentCaptor.getValue();
		assertThat(returned).isSameAs(savedPayment);
		assertThat(savedPayment.getImpUid()).isEqualTo("free_" + savedOrder.getMerchantUid());
		assertThat(savedPayment.getMerchantUid()).isEqualTo(savedOrder.getMerchantUid());
		assertThat(savedPayment.getAmount()).isZero();
		assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(savedPayment.getUserId()).isEqualTo(USER_ID);
		assertThat(savedPayment.getSubCategoryId()).isEqualTo(SUB_CATEGORY_ID);

		// (b) Result 생성
		verify(resultService).createInitialResult(savedPayment, savedOrder);

		// (c) Discord 알림 없음, 할인/쿠폰/포트원 호출 없음
		verifyNoInteractions(discordNotificationService, discountCodeService, couponService,
			portOneClient);
	}

	// ===== verifyPaidOwnership =====

	@Test
	@DisplayName("본인의 PAID 결제면 verifyPaidOwnership 은 예외 없이 통과한다")
	void verifyPaidOwnership_ownPaidPayment_passes() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(
			Optional.of(createPaidPayment()));

		// when & then
		paymentService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME);
	}

	@Test
	@DisplayName("결제가 없으면 '유효한 결제 정보가 아닙니다.' PaymentException 이 난다")
	void verifyPaidOwnership_paymentNotFound_throws() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 결제 정보가 아닙니다.");
	}

	@Test
	@DisplayName("결제 상태가 PAID 가 아니면 같은 메시지의 PaymentException 이 난다")
	void verifyPaidOwnership_notPaid_throws() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE,
			PaymentStatus.CANCELLED, ORDER_ID, USER_ID, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 결제 정보가 아닙니다.");
	}

	@Test
	@DisplayName("다른 사용자의 결제면 존재 여부를 드러내지 않는 같은 메시지의 PaymentException 이 난다")
	void verifyPaidOwnership_otherUsersPayment_throws() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Long otherUserId = 999L;
		Payment payment = Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE,
			PaymentStatus.PAID, ORDER_ID, otherUserId, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 결제 정보가 아닙니다.");
	}

	// ===== cancelPayment =====

	@Test
	@DisplayName("환불 시 포트원 취소를 호출한 뒤 Payment 와 Order 를 CANCELLED 로 바꾸고 Result 를 삭제한다")
	void cancelPayment_success_updatesStatusesAndDeletesResult() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = createPaidPayment();
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.of(payment));
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));
		Order order = createOrder(OrderStatus.PAID, null, null);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

		// when
		paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심");

		// then
		verify(portOneClient).cancelPayment(PAYMENT_ID, "단순 변심");
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		verify(resultRepository).delete(result);

		// 복구 규칙은 OrderDiscountRestorer 가 소유하므로 PaymentService 는 restore(order) 만 위임한다
		verify(orderDiscountRestorer).restore(order);
		verifyNoInteractions(couponService, discountCodeService);
	}

	@Test
	@DisplayName("쿠폰을 쓴 주문을 환불하면 포트원 취소 뒤 OrderDiscountRestorer 로 복구를 위임한다")
	void cancelPayment_couponOrder_restoresThroughRestorer() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = createPaidPayment();
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.of(payment));
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));
		Order order = createOrder(OrderStatus.PAID, null, 100L);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

		// when
		paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심");

		// then
		InOrder inOrder = inOrder(portOneClient, orderDiscountRestorer);
		inOrder.verify(portOneClient).cancelPayment(PAYMENT_ID, "단순 변심");
		inOrder.verify(orderDiscountRestorer).restore(order);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		verifyNoInteractions(couponService, discountCodeService);
	}

	@Test
	@DisplayName("포트원 취소가 PaymentException 을 던지면 Payment/Order/Result 상태가 바뀌지 않는다")
	void cancelPayment_portOneFails_leavesStateUnchanged() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = createPaidPayment();
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.of(payment));
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));
		Order order = createOrder(OrderStatus.PAID, null, null);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

		willThrow(new PaymentException("결제 취소 연동 중 오류가 발생했습니다: 400 Bad Request"))
			.given(portOneClient).cancelPayment(PAYMENT_ID, "단순 변심");

		// when & then
		assertThatThrownBy(() -> paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PaymentException.class)
			.hasMessageStartingWith("결제 취소 연동 중 오류가 발생했습니다: ");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		verify(resultRepository, never()).delete(any(Result.class));
		verifyNoInteractions(couponService, discountCodeService, orderDiscountRestorer);
	}

	@Test
	@DisplayName("Payment 가 PAID 가 아니면 포트원 취소를 호출하기 전에 PaymentException 이 난다")
	void cancelPayment_paymentNotPaid_throwsBeforeCallingPortOne() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE,
			PaymentStatus.READY, ORDER_ID, USER_ID, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.of(payment));
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));

		// when & then
		assertThatThrownBy(() -> paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 완료 상태가 아니라 취소할 수 없습니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
		verify(portOneClient, never()).cancelPayment(any(), any());
		verify(orderRepository, never()).findById(any());
		verify(resultRepository, never()).delete(any(Result.class));
	}

	@Test
	@DisplayName("주문이 CANCELLED 로 갈 수 없는 상태면 포트원 취소를 호출하기 전에 PaymentException 이 난다")
	void cancelPayment_orderNotCancellable_throwsBeforeCallingPortOne() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		Payment payment = createPaidPayment();
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.of(payment));
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

		// when & then
		assertThatThrownBy(() -> paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PaymentException.class)
			.hasMessage("취소할 수 없는 주문 상태입니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(portOneClient, never()).cancelPayment(any(), any());
		verify(resultRepository, never()).delete(any(Result.class));
	}
}
