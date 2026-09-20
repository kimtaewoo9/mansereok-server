package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.event.PaymentCompletedEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 주문 생성·0원 발급(100% 할인 코드, 무료 이벤트) 검증.
 *
 * <p>PaidOrderFinalizer 는 mock 하지 않고 mock 리포지토리로 만든 실제 인스턴스를 넘겨 "주문이 PAID 가 되고
 * Payment 와 Result 가 만들어진다"를 계속 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrderServiceTest {

	private static final String USERNAME = "testUser";
	private static final String BUYER_NAME = "김태우";
	private static final String BUYER_EMAIL = "taewoo@example.com";
	private static final Long USER_ID = 1L;
	private static final Long SUB_CATEGORY_ID = 1L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final int PRICE = 10000;

	private PaymentOrderService paymentOrderService;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private DiscountCodeService discountCodeService;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ResultService resultService;
	@Mock
	private CouponService couponService;
	@Mock
	private FreeProductPolicy freeProductPolicy;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@BeforeEach
	void setUp() {
		PaidOrderFinalizer paidOrderFinalizer = new PaidOrderFinalizer(orderRepository,
			paymentRepository, resultService, eventPublisher);
		paymentOrderService = new PaymentOrderService(
			userRepository,
			subCategoryRepository,
			orderRepository,
			discountCodeService,
			couponService,
			paidOrderFinalizer,
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
		paymentOrderService.createOrder(USERNAME, request);

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
		OrderCreateResponse response = paymentOrderService.createOrder(USERNAME, request);

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
		OrderCreateResponse response = paymentOrderService.createOrder(USERNAME, request);

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
		assertThatThrownBy(() -> paymentOrderService.createOrder(USERNAME, request))
			.isInstanceOf(PaymentException.class)
			.hasMessage("0원 주문은 무료 결제 API(/api/payment/redeem-free)를 이용해주세요.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(discountCodeService, never()).incrementUsage(any());
		verifyNoInteractions(couponService, paymentRepository, resultService);
	}

	@Test
	@DisplayName("주문 저장 중 DB 장애(DataAccessException)는 PaymentException 으로 감싸지 않고 그대로 전파된다")
	void createOrder_dbFailure_propagatesOriginalException() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		SubCategory subCategory = mockSubCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		given(orderRepository.save(any(Order.class)))
			.willThrow(new DataAccessResourceFailureException("connection pool exhausted"));

		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(SUB_CATEGORY_ID);

		// when & then
		assertThatThrownBy(() -> paymentOrderService.createOrder(USERNAME, request))
			.isInstanceOf(DataAccessResourceFailureException.class)
			.isNotInstanceOf(PaymentException.class);
	}

	// ===== redeemFreeProduct =====

	@Test
	@DisplayName("100% 할인 코드로 무료 상품을 받으면 PAID 주문과 0원 Payment 가 저장되고 Result 가 생성되며 완료 이벤트는 amount 0 으로 발행된다")
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
		OrderCreateResponse response = paymentOrderService.redeemFreeProduct(USERNAME, request);

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

		// (c) 완료 이벤트는 amount 0 으로 발행되고 알림 리스너가 0원이면 보내지 않는다
		verify(eventPublisher).publishEvent(new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, 0L));

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
		assertThatThrownBy(() -> paymentOrderService.redeemFreeProduct(USERNAME, request))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 100% 할인 코드가 아닙니다.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(discountCodeService, never()).incrementUsage(any());
		verifyNoInteractions(resultService, eventPublisher);
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
		OrderCreateResponse response = paymentOrderService.redeemFreeProduct(USERNAME, request);

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
		assertThatThrownBy(() -> paymentOrderService.createFreeOrder(USERNAME, SUB_CATEGORY_ID))
			.isInstanceOf(PaymentException.class)
			.hasMessage("무료로 제공되는 상품이 아닙니다.");

		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher, discountCodeService,
			couponService);
	}

	@Test
	@DisplayName("무료 이벤트 주문은 원가 0원의 PAID 주문과 free_ 접두사의 0원 Payment 가 저장되고 Result 가 생성되며 완료 이벤트는 amount 0 으로 발행된다")
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
		Payment returned = paymentOrderService.createFreeOrder(USERNAME, SUB_CATEGORY_ID);

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

		// (c) 할인/쿠폰 호출 없음. 완료 이벤트는 amount 0 으로 발행되고 알림 리스너가 0원이면 보내지 않는다
		verifyNoInteractions(discountCodeService, couponService);
		verify(eventPublisher).publishEvent(new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, 0L));
	}
}
