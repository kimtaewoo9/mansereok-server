package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.event.PaymentCompletedEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 포트원 웹훅 처리 "파싱 → Paid 필터 → 재조회 → 잠금·멱등 → 금액 → 확정" 검증.
 *
 * <p>PaidOrderFinalizer 는 mock 하지 않고 mock 리포지토리로 만든 실제 인스턴스를 넘겨 "주문이 PAID 가 되고
 * Payment 와 Result 가 만들어진다"를 계속 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

	private static final String BUYER_NAME = "김태우";
	private static final String BUYER_EMAIL = "taewoo@example.com";
	private static final Long USER_ID = 1L;
	private static final Long SUB_CATEGORY_ID = 1L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final int PRICE = 10000;
	private static final String CUSTOM_DATA = "{\"merchantUid\":\"" + MERCHANT_UID
		+ "\",\"subCategoryId\":1}";

	private PaymentWebhookService paymentWebhookService;

	// 웹훅 본문과 customData 파싱을 실제로 검증하도록 mock 이 아닌 진짜 ObjectMapper 를 쓴다.
	// Spring Boot 자동 구성과 같이 FAIL_ON_UNKNOWN_PROPERTIES 가 꺼진 매퍼다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ResultService resultService;
	@Mock
	private PortOneClient portOneClient;
	@Mock
	private OrderDiscountRestorer orderDiscountRestorer;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@BeforeEach
	void setUp() {
		PaidOrderFinalizer paidOrderFinalizer = new PaidOrderFinalizer(orderRepository,
			paymentRepository, resultService, eventPublisher);
		paymentWebhookService = new PaymentWebhookService(
			objectMapper,
			portOneClient,
			new PaymentVerifier(objectMapper), // customData 파싱·금액·상태 규칙을 실제로 검증한다
			orderRepository,
			paymentRepository,
			paidOrderFinalizer,
			orderDiscountRestorer
		);
	}

	// ===== 테스트 픽스처 =====

	private Order createOrder(OrderStatus status, String appliedDiscountCode, Long couponId) {
		Order order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, PRICE, PRICE,
			appliedDiscountCode, couponId, status, BUYER_NAME, BUYER_EMAIL);
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
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

	private PortOnePaymentResponse portOneResponseWithCustomData(String status, long total) {
		PortOnePaymentResponse response = portOneResponse(status, total);
		response.setCustomData(CUSTOM_DATA);
		return response;
	}

	private static String webhookBody(String status) {
		return "{\"tx_id\":\"tx_1\",\"payment_id\":\"" + PAYMENT_ID + "\",\"status\":\"" + status
			+ "\",\"timestamp\":\"2026-01-01T00:00:00Z\"}";
	}

	private void givenOrderSaveReturnsArgument() {
		given(orderRepository.save(any(Order.class))).willAnswer(
			invocation -> invocation.getArgument(0));
	}

	private void givenPaymentSaveAssignsId() {
		given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
			return payment;
		});
	}

	// ===== processWebhook =====

	@Test
	@DisplayName("Paid 웹훅은 포트원 재조회 뒤 주문을 PAID 로 확정하고 Payment 저장과 초기 Result 생성을 하며, Discord 알림 대신 PaymentCompletedEvent 를 발행한다")
	void processWebhook_paid_finalizesOrder() {
		// given
		Order order = createOrder(OrderStatus.PENDING, "WELCOME10", null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		paymentWebhookService.processWebhook(webhookBody("Paid"));

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(order.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);

		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(paymentCaptor.capture());
		Payment savedPayment = paymentCaptor.getValue();
		assertThat(savedPayment.getImpUid()).isEqualTo(PAYMENT_ID);
		assertThat(savedPayment.getMerchantUid()).isEqualTo(MERCHANT_UID);
		assertThat(savedPayment.getAmount()).isEqualTo((long) PRICE);
		assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);

		verify(resultService).createInitialResult(savedPayment, order);
		// 알림은 커밋 뒤 리스너가 담당하므로 여기서는 이벤트 발행만 확인한다
		verify(eventPublisher).publishEvent(
			new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, (long) PRICE));
	}

	@Test
	@DisplayName("Ready 웹훅은 결제 완료 이벤트가 아니므로 포트원 조회와 주문 조회 없이 무시한다")
	void processWebhook_ready_ignored() {
		// when
		paymentWebhookService.processWebhook(webhookBody("Ready"));

		// then
		verifyNoInteractions(portOneClient, orderRepository, paymentRepository, resultService,
			eventPublisher);
	}

	@Test
	@DisplayName("금액 불일치 웹훅은 예외 없이 정상 반환하고 주문을 FAILED 로 저장한 뒤 할인을 복구하며 Payment 와 Result 는 만들지 않는다")
	void processWebhook_amountMismatch_marksFailedAndReturns() {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, 7L);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE - 9900));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		givenOrderSaveReturnsArgument();

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.FAILED);
		verify(orderDiscountRestorer).restore(order);
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@Test
	@DisplayName("FAILED 로 전이할 수 없는 EXPIRED 주문에 금액 불일치 웹훅이 오면 상태를 그대로 두고 저장·복구 없이 정상 반환한다")
	void processWebhook_expiredOrderAmountMismatch_leavesStatusAndReturns() {
		// given
		Order order = createOrder(OrderStatus.EXPIRED, "WELCOME10", null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE - 1));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(orderDiscountRestorer, resultService, eventPublisher);
	}

	@Test
	@DisplayName("이미 PAID 인 주문에 같은 웹훅이 다시 오면 아무것도 저장하지 않고 정상 반환한다")
	void processWebhook_alreadyPaid_ignored() {
		// given
		Order order = createOrder(OrderStatus.PAID, null, null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verify(orderRepository, never()).save(any(Order.class));
		verifyNoInteractions(paymentRepository, orderDiscountRestorer, resultService,
			eventPublisher);
	}

	@Test
	@DisplayName("포트원 일시 장애(PortOneUnavailableException)는 감싸지 않고 그대로 전파되며 주문은 조회하지 않는다")
	void processWebhook_portOneUnavailable_propagates() {
		// given
		given(portOneClient.getPayment(PAYMENT_ID)).willThrow(
			new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다."));

		// when & then
		assertThatThrownBy(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasMessage("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다.");

		verifyNoInteractions(orderRepository, paymentRepository, resultService);
	}

	@Test
	@DisplayName("웹훅 본문이 JSON 이 아니면 '웹훅 페이로드 파싱 실패' PaymentException 이 나고 포트원은 호출하지 않는다")
	void processWebhook_malformedBody_throwsPaymentException() {
		assertThatThrownBy(() -> paymentWebhookService.processWebhook("not-json"))
			.isInstanceOf(PaymentException.class)
			.hasMessage("웹훅 페이로드 파싱 실패");

		verifyNoInteractions(portOneClient, orderRepository);
	}

	@Test
	@DisplayName("customData 의 merchantUid 로 주문을 찾지 못하면 '주문을 찾을 수 없습니다.' PaymentException 이 난다")
	void processWebhook_orderNotFound_throwsPaymentException() {
		// given
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.isInstanceOf(PaymentException.class)
			.hasMessage("주문을 찾을 수 없습니다.");

		verifyNoInteractions(paymentRepository, resultService);
	}

	@Test
	@DisplayName("포트원 재조회 상태가 FAILED 면 예외 없이 주문을 FAILED 로 저장하고 할인을 복구하며 Payment 는 만들지 않는다")
	void processWebhook_portOneFailedStatus_marksFailedAndReturns() {
		// given
		Order order = createOrder(OrderStatus.PENDING, "WELCOME10", null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("FAILED", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		givenOrderSaveReturnsArgument();

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		InOrder inOrder = inOrder(orderRepository, orderDiscountRestorer);
		inOrder.verify(orderRepository).save(order);
		inOrder.verify(orderDiscountRestorer).restore(order);
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@ParameterizedTest(name = "재조회 상태 {0}")
	@ValueSource(strings = {"READY", "PAY_PENDING", "VIRTUAL_ACCOUNT_ISSUED"})
	@DisplayName("포트원 재조회 상태가 진행 중(READY·PAY_PENDING·VIRTUAL_ACCOUNT_ISSUED)이면 FAILED 로 굳히지 않고 주문을 그대로 둔 채 정상 반환한다")
	void processWebhook_inProgressStatus_leavesOrderPending(String rawStatus) {
		// given
		Order order = createOrder(OrderStatus.PENDING, "WELCOME10", null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData(rawStatus, PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(orderDiscountRestorer, resultService, eventPublisher);
	}

	@Test
	@DisplayName("포트원 재조회 상태를 모르면 예외 없이 정상 반환하고 주문은 PENDING 그대로 두며 아무것도 저장하지 않는다")
	void processWebhook_unknownStatus_leavesOrderPending() {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("SOMETHING_NEW", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(orderDiscountRestorer, resultService, eventPublisher);
	}

	@Test
	@DisplayName("포트원 재조회 상태가 PARTIAL_CANCELLED 면 CANCELLED 로 매핑돼 주문을 FAILED 로 기록하고 할인을 복구한다")
	void processWebhook_partialCancelled_marksFailed() {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PARTIAL_CANCELLED", PRICE));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		givenOrderSaveReturnsArgument();

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		verify(orderDiscountRestorer).restore(order);
		verify(paymentRepository, never()).save(any(Payment.class));
	}
}
