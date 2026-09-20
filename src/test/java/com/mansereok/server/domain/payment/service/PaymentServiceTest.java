package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
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
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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

	@InjectMocks
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

	// ===== 테스트 픽스처 =====

	private User createUser() {
		User user = User.create(USERNAME, BUYER_NAME, "password", BUYER_EMAIL,
			LocalDate.now(), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private SubCategory mockSubCategory() {
		// protected 생성자 대응. stub 이 들어있으므로 다른 given(...) 의 인자 안에서 호출하면 안 된다.
		SubCategory subCategory = mock(SubCategory.class);
		given(subCategory.getPrice()).willReturn(PRICE);
		given(subCategory.getId()).willReturn(SUB_CATEGORY_ID);
		given(subCategory.getTitle()).willReturn("인생 총운");
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
		given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
			return payment;
		});

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

		// 쿠폰/할인 코드를 쓰지 않은 주문이므로 복구 로직은 호출되지 않는다
		verify(couponService, never()).restoreCoupon(any());
		verify(discountCodeService, never()).restoreDiscountUsage(any());
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

		willThrow(new PaymentException("결제 취소 연동 중 오류가 발생했습니다: 400 Bad Request"))
			.given(portOneClient).cancelPayment(PAYMENT_ID, "단순 변심");

		// when & then
		assertThatThrownBy(() -> paymentService.cancelPayment(USERNAME, PAYMENT_ID, "단순 변심"))
			.isInstanceOf(PaymentException.class)
			.hasMessageStartingWith("결제 취소 연동 중 오류가 발생했습니다: ");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		verify(orderRepository, never()).findById(any());
		verify(resultRepository, never()).delete(any(Result.class));
		verifyNoInteractions(couponService, discountCodeService);
	}
}
