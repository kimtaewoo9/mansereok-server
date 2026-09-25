package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.mansereok.server.domain.payment.event.PaymentAnomalyEvent;
import com.mansereok.server.domain.payment.event.PaymentCompletedEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
	private static final String SECOND_PAYMENT_ID = "pay_test_002";
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
	@Mock
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void setUp() {
		// 트랜잭션 시작은 주문을 잠그는 테스트에서만 일어나므로 strict stubs 에 걸리지 않도록 lenient 로 둔다.
		lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.thenAnswer(invocation -> new SimpleTransactionStatus(true));
		PaidOrderFinalizer paidOrderFinalizer = new PaidOrderFinalizer(orderRepository,
			paymentRepository, resultService, orderDiscountRestorer, eventPublisher);
		PaymentVerifier paymentVerifier = new PaymentVerifier(objectMapper); // customData 파싱·금액·상태 규칙을 실제로 검증한다
		paymentWebhookService = new PaymentWebhookService(
			objectMapper,
			portOneClient,
			paymentVerifier,
			orderRepository,
			paymentRepository,
			paidOrderFinalizer,
			orderDiscountRestorer,
			new DuplicatePaymentCanceller(portOneClient, paymentVerifier, eventPublisher),
			transactionManager
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
		return portOneResponse(PAYMENT_ID, status, total);
	}

	private PortOnePaymentResponse portOneResponse(String paymentId, String status, long total) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(paymentId);
		response.setStatus(status);
		PortOnePaymentResponse.Amount amount = new PortOnePaymentResponse.Amount();
		amount.setTotal(total);
		response.setAmount(amount);
		return response;
	}

	private PortOnePaymentResponse portOneResponseWithCustomData(String status, long total) {
		return portOneResponseWithCustomData(PAYMENT_ID, status, total);
	}

	/** 이 주문(MERCHANT_UID)을 customData 에 담은 결제 paymentId 의 포트원 응답. */
	private PortOnePaymentResponse portOneResponseWithCustomData(String paymentId, String status,
		long total) {
		PortOnePaymentResponse response = portOneResponse(paymentId, status, total);
		response.setCustomData(CUSTOM_DATA);
		return response;
	}

	/** 결제 recordedPaymentId 로 이미 확정된 주문. */
	private Order paidOrder(String recordedPaymentId) {
		Order order = createOrder(OrderStatus.PENDING, null, null);
		order.markPaid(recordedPaymentId, LocalDateTime.of(2026, 9, 26, 12, 0));
		return order;
	}

	private void givenLockedOrder(Order order) {
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(Optional.of(order));
	}

	/** eventPublisher 에 한 번 발행된 결제 이상 이벤트를 꺼낸다. 두 번 이상 발행됐으면 verify 가 실패한다. */
	private PaymentAnomalyEvent capturedAnomalyEvent() {
		ArgumentCaptor<PaymentAnomalyEvent> captor = ArgumentCaptor.forClass(PaymentAnomalyEvent.class);
		verify(eventPublisher, times(1)).publishEvent(captor.capture());
		return captor.getValue();
	}

	private static String webhookBody(String status) {
		return webhookBody(PAYMENT_ID, status);
	}

	private static String webhookBody(String paymentId, String status) {
		return "{\"tx_id\":\"tx_1\",\"payment_id\":\"" + paymentId + "\",\"status\":\"" + status
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
	@DisplayName("같은 결제로 이미 PAID 인 주문에 같은 웹훅이 다시 오면 아무것도 저장·취소·알림하지 않고 정상 반환한다")
	void processWebhook_alreadyPaidWithSamePayment_ignored() {
		// given
		Order order = paidOrder(PAYMENT_ID);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		givenLockedOrder(order);

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verify(orderRepository, never()).save(any(Order.class));
		verify(portOneClient, never()).cancelPayment(any(), any());
		verifyNoInteractions(paymentRepository, orderDiscountRestorer, resultService,
			eventPublisher);
	}

	@Test
	@DisplayName("환불로 CANCELLED 가 된 주문에 같은 결제 ID 의 Payment 가 이미 있으면 원래 결제의 웹훅이 늦게 와도 아무것도 하지 않고 정상 반환한다")
	void processWebhook_cancelledOrderWithRecordedPayment_ignored() {
		// given
		Order order = createOrder(OrderStatus.CANCELLED, "WELCOME10", null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		givenLockedOrder(order);
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(
			Optional.of(Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE, PaymentStatus.CANCELLED,
				ORDER_ID, USER_ID, SUB_CATEGORY_ID)));

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(portOneClient, never()).cancelPayment(any(), any());
		verifyNoInteractions(orderDiscountRestorer, resultService, eventPublisher);
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
	@DisplayName("포트원 재조회 응답의 결제 ID 가 웹훅의 결제 ID 와 다르면 '결제 정보의 결제 ID가 일치하지 않습니다.' 로 거부하고 주문을 잠그거나 확정하지 않는다")
	void processWebhook_responsePaymentIdDiffers_throwsWithoutTouchingOrder() {
		// given: 'pay_A#1' 을 재조회했는데 포트원이 다른 결제(pay_A)를 돌려준 상황. 금액·customData 는 모두 맞다.
		PortOnePaymentResponse otherPayment = portOneResponse("pay_A", "PAID", PRICE);
		otherPayment.setCustomData(CUSTOM_DATA);
		given(portOneClient.getPayment("pay_A#1")).willReturn(otherPayment);

		// when & then
		assertThatThrownBy(() -> paymentWebhookService.processWebhook(webhookBody("pay_A#1", "Paid")))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보의 결제 ID가 일치하지 않습니다.");

		verifyNoInteractions(orderRepository, paymentRepository, orderDiscountRestorer, resultService,
			eventPublisher);
	}

	@Test
	@DisplayName("웹훅 본문이 JSON 이 아니면 '웹훅 페이로드 파싱 실패' PaymentException 이 나고, JSON 예외를 원인으로 이으며 포트원은 호출하지 않는다")
	void processWebhook_malformedBody_throwsPaymentException() {
		assertThatThrownBy(() -> paymentWebhookService.processWebhook("not-json"))
			.isInstanceOf(PaymentException.class)
			.hasMessage("웹훅 페이로드 파싱 실패")
			.hasCauseInstanceOf(JsonProcessingException.class);

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

	// ===== 이미 다른 결제로 확정된 주문에 결제가 또 옴 =====

	@Nested
	@DisplayName("결제 pay_test_001 로 이미 PAID 가 된 주문에 다른 결제 pay_test_002 의 Paid 웹훅이 오면")
	class WhenSecondPaymentWebhookArrivesForPaidOrder {

		private final Order order = paidOrder(PAYMENT_ID);

		@BeforeEach
		void givenPaidOrder() {
			givenLockedOrder(order);
		}

		@Test
		@DisplayName("포트원에서 승인된 결제면 그 결제를 취소하고 알림을 한 번 보낸 뒤 예외 없이 끝내며 주문은 첫 결제 그대로 둔다")
		void cancelsSecondPaymentAndReturnsNormally() {
			// given
			given(portOneClient.getPayment(SECOND_PAYMENT_ID)).willReturn(
				portOneResponseWithCustomData(SECOND_PAYMENT_ID, "PAID", PRICE));

			// when
			assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody(SECOND_PAYMENT_ID, "Paid")))
				.doesNotThrowAnyException();

			// then
			verify(portOneClient, times(1)).cancelPayment(SECOND_PAYMENT_ID, "같은 주문의 중복 결제 자동 취소");
			PaymentAnomalyEvent alert = capturedAnomalyEvent();
			assertThat(alert.summary()).contains("자동으로 취소했습니다");
			assertThat(alert.details())
				.containsEntry("주문에 기록된 결제 ID", PAYMENT_ID)
				.containsEntry("한 번 더 온 결제 ID", SECOND_PAYMENT_ID);
			assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
			assertThat(order.getPaymentId()).isEqualTo(PAYMENT_ID);
			verify(orderRepository, never()).save(any(Order.class));
			verifyNoInteractions(paymentRepository, orderDiscountRestorer, resultService);
		}

		@Test
		@DisplayName("두 번째 결제의 취소는 주문을 잠근 트랜잭션이 커밋된 뒤에 부른다")
		void cancelsAfterTransactionCommits() {
			// given
			given(portOneClient.getPayment(SECOND_PAYMENT_ID)).willReturn(
				portOneResponseWithCustomData(SECOND_PAYMENT_ID, "PAID", PRICE));

			// when
			paymentWebhookService.processWebhook(webhookBody(SECOND_PAYMENT_ID, "Paid"));

			// then
			InOrder inOrder = inOrder(orderRepository, transactionManager, portOneClient);
			inOrder.verify(orderRepository).findByMerchantUidWithLock(MERCHANT_UID);
			inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
			inOrder.verify(portOneClient).cancelPayment(SECOND_PAYMENT_ID, "같은 주문의 중복 결제 자동 취소");
		}

		@Test
		@DisplayName("포트원 취소가 실패해도 예외 없이 끝내 포트원에 200 을 돌려주고, 손으로 취소하라는 알림을 한 번 보낸다")
		void returnsNormallyAndAlertsWhenCancelFails() {
			// given
			given(portOneClient.getPayment(SECOND_PAYMENT_ID)).willReturn(
				portOneResponseWithCustomData(SECOND_PAYMENT_ID, "PAID", PRICE));
			willThrow(new PaymentException("결제 취소 연동 중 오류가 발생했습니다."))
				.given(portOneClient).cancelPayment(SECOND_PAYMENT_ID, "같은 주문의 중복 결제 자동 취소");
			given(portOneClient.findPayment(SECOND_PAYMENT_ID)).willReturn(
				Optional.of(portOneResponseWithCustomData(SECOND_PAYMENT_ID, "PAID", PRICE)));

			// when
			assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody(SECOND_PAYMENT_ID, "Paid")))
				.doesNotThrowAnyException();

			// then
			assertThat(capturedAnomalyEvent().summary()).contains("자동 취소에 실패했습니다");
			verifyNoInteractions(paymentRepository, resultService);
		}

		@ParameterizedTest(name = "포트원 재조회 상태 {0}")
		@ValueSource(strings = {"READY", "FAILED", "CANCELLED"})
		@DisplayName("두 번째 결제가 포트원에서 승인된 상태가 아니면 돈이 빠져나가지 않았으므로 취소·알림 없이 정상 반환한다")
		void ignoresSecondPaymentThatIsNotPaid(String status) {
			// given
			given(portOneClient.getPayment(SECOND_PAYMENT_ID)).willReturn(
				portOneResponseWithCustomData(SECOND_PAYMENT_ID, status, PRICE));

			// when
			paymentWebhookService.processWebhook(webhookBody(SECOND_PAYMENT_ID, "Paid"));

			// then
			assertThat(order.getPaymentId()).isEqualTo(PAYMENT_ID);
			verify(portOneClient, never()).cancelPayment(any(), any());
			verifyNoInteractions(eventPublisher, paymentRepository, resultService);
		}
	}

	@Test
	@DisplayName("결제 ID 가 기록되지 않은 예전 PAID 주문에 승인된 결제의 웹훅이 오면 같은 결제인지 가릴 수 없어 취소하지 않고 알림만 한 번 보낸다")
	void processWebhook_paidOrderWithoutRecordedPaymentId_alertsWithoutCancel() {
		// given
		Order order = createOrder(OrderStatus.PAID, null, null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData("PAID", PRICE));
		givenLockedOrder(order);

		// when
		assertThatCode(() -> paymentWebhookService.processWebhook(webhookBody("Paid")))
			.doesNotThrowAnyException();

		// then
		verify(portOneClient, never()).cancelPayment(any(), any());
		PaymentAnomalyEvent alert = capturedAnomalyEvent();
		assertThat(alert.summary()).contains("자동 취소는 하지 않았습니다");
		assertThat(alert.details()).containsEntry("주문에 기록된 결제 ID", "없음");
		verifyNoInteractions(paymentRepository, resultService);
	}

	// ===== 포트원 재조회 상태별 처리 =====

	/**
	 * 포트원 재조회 상태(상수 이름 그대로)마다 PENDING 주문이 웹훅 뒤 어느 상태가 돼야 하는지 적은 표. PaymentStatus 에 상수가
	 * 늘면 {@link #statusTableCoversEveryPaymentStatus()} 가 실패해 이 표를 채우게 한다. CANCEL_REQUESTED 는 포트원
	 * 상태에서 나오지 않는 내부 상태라 모르는 상태와 같이 주문을 그대로 둔다.
	 */
	private static final Map<PaymentStatus, OrderStatus> ORDER_STATUS_AFTER_WEBHOOK = Map.of(
		PaymentStatus.PAID, OrderStatus.PAID,
		PaymentStatus.READY, OrderStatus.PENDING,
		PaymentStatus.VIRTUAL_ACCOUNT_ISSUED, OrderStatus.PENDING,
		PaymentStatus.FAILED, OrderStatus.FAILED,
		PaymentStatus.CANCELLED, OrderStatus.FAILED,
		PaymentStatus.CANCEL_REQUESTED, OrderStatus.PENDING
	);

	@Test
	@DisplayName("상태별 기대 결과 표에는 PaymentStatus 의 모든 상수가 있다")
	void statusTableCoversEveryPaymentStatus() {
		assertThat(ORDER_STATUS_AFTER_WEBHOOK).containsOnlyKeys(PaymentStatus.values());
	}

	@ParameterizedTest(name = "포트원 재조회 상태 {0}")
	@EnumSource(PaymentStatus.class)
	@DisplayName("포트원 재조회 상태마다 표에 적은 대로 PENDING 주문을 확정·실패 기록하거나 그대로 둔다")
	void processWebhook_eachPortOneStatus_followsStatusTable(PaymentStatus status) {
		// given
		Order order = createOrder(OrderStatus.PENDING, null, null);
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(
			portOneResponseWithCustomData(status.name(), PRICE));
		givenLockedOrder(order);
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
		// 저장은 확정(PAID)·실패 기록(FAILED) 상태에서만 일어나므로 lenient 로 둔다.
		lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
		lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
			Payment payment = invocation.getArgument(0);
			ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
			return payment;
		});

		// when
		paymentWebhookService.processWebhook(webhookBody("Paid"));

		// then
		assertThat(order.getStatus()).isEqualTo(ORDER_STATUS_AFTER_WEBHOOK.get(status));
	}

	// ===== 웹훅 본문 형식 =====

	@Test
	@DisplayName("status 없이 type 만 있는 신형 웹훅 본문이 오면 포트원·주문을 건드리지 않고 warn 로그를 남긴 뒤 정상 반환한다")
	void processWebhook_newFormatBody_logsWarnAndIgnores() {
		// given: 포트원 콘솔에서 웹훅 버전을 2024-04-25 로 바꾸면 오는 형식
		String newFormatBody = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-01-01T00:00:00Z\","
			+ "\"data\":{\"paymentId\":\"" + PAYMENT_ID + "\",\"storeId\":\"store_1\",\"transactionId\":\"tx_1\"}}";
		Logger logger = (Logger) LoggerFactory.getLogger(PaymentWebhookService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			// when
			paymentWebhookService.processWebhook(newFormatBody);

			// then
			assertThat(appender.list).anySatisfy(logEvent -> {
				assertThat(logEvent.getLevel()).isEqualTo(Level.WARN);
				assertThat(logEvent.getFormattedMessage())
					.contains("status 없이 type 만 있는 웹훅 본문")
					.contains("Transaction.Paid");
			});
			verifyNoInteractions(portOneClient, orderRepository, paymentRepository, eventPublisher);
		} finally {
			logger.detachAppender(appender);
		}
	}
}
