package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.request.PaymentCompleteRequest;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.event.PaymentCompletedEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 결제 완료 확정 "포트원 조회(트랜잭션 밖) → 잠금·검증·확정(트랜잭션 안)" 검증.
 *
 * <p>TransactionTemplate 은 mock PlatformTransactionManager 로 만든 실제 인스턴스라서 트랜잭션 시작·커밋·롤백을
 * 포트원 호출과의 순서로 관찰할 수 있다. PaidOrderFinalizer 는 mock 하지 않고 mock 리포지토리로 만든 실제
 * 인스턴스를 써서 "주문이 PAID 가 되고 Payment 와 Result 가 만들어진다"를 계속 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmServiceTest {

	private static final String USERNAME = "testUser";
	private static final String BUYER_NAME = "김태우";
	private static final String BUYER_EMAIL = "taewoo@example.com";
	private static final Long USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long SUB_CATEGORY_ID = 1L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final int PRICE = 10000;
	private static final String CUSTOM_DATA = "{\"merchantUid\":\"" + MERCHANT_UID
		+ "\",\"subCategoryId\":1}";

	// customData 파싱을 실제로 검증하도록 mock 이 아닌 진짜 ObjectMapper 를 쓴다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Mock
	private UserRepository userRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ResultService resultService;
	@Mock
	private PortOneClient portOneClient;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private PlatformTransactionManager transactionManager;

	private PaymentConfirmService paymentConfirmService;

	@BeforeEach
	void setUp() {
		// 트랜잭션 시작은 필요한 테스트에서만 일어나므로 strict stubs 에 걸리지 않도록 lenient 로 둔다.
		lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.thenAnswer(invocation -> new SimpleTransactionStatus(true));
		PaidOrderFinalizer paidOrderFinalizer = new PaidOrderFinalizer(orderRepository,
			paymentRepository, resultService, eventPublisher);
		paymentConfirmService = new PaymentConfirmService(
			userRepository,
			orderRepository,
			paymentRepository,
			portOneClient,
			new PaymentVerifier(objectMapper),
			paidOrderFinalizer,
			transactionManager
		);
	}

	// ===== 픽스처 =====

	private User createUser() {
		User user = User.create(USERNAME, BUYER_NAME, "password", BUYER_EMAIL,
			LocalDate.now(), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private Order createOrder(OrderStatus status) {
		Order order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, PRICE, PRICE,
			null, null, status, BUYER_NAME, BUYER_EMAIL);
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

	private static PaymentCompleteRequest completeRequest() {
		PaymentCompleteRequest request = new PaymentCompleteRequest();
		request.setPaymentId(PAYMENT_ID);
		request.setMerchantUid(MERCHANT_UID);
		return request;
	}

	private void givenRequester() {
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
	}

	private void givenLockedOrder(Order order) {
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
	}

	private void givenNoDuplicatePayment() {
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(Optional.empty());
	}

	private void givenPortOneReturns(PortOnePaymentResponse response) {
		given(portOneClient.getPayment(PAYMENT_ID)).willReturn(response);
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

	// ===== 트랜잭션 경계 =====

	@Test
	@DisplayName("포트원 조회는 트랜잭션 시작과 주문 잠금보다 먼저 일어나고, 확정 뒤 커밋된다")
	void complete_callsPortOneBeforeTransactionAndLock() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		paymentConfirmService.complete(USERNAME, completeRequest());

		// then: 포트원 조회 → 트랜잭션 시작 → 잠금 조회 → 확정 → 커밋
		InOrder inOrder = inOrder(portOneClient, transactionManager, orderRepository,
			paymentRepository, resultService);
		inOrder.verify(portOneClient).getPayment(PAYMENT_ID);
		inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
		inOrder.verify(orderRepository).findByMerchantUidWithLock(MERCHANT_UID);
		inOrder.verify(paymentRepository).save(any(Payment.class));
		inOrder.verify(resultService).createInitialResult(any(Payment.class), eq(order));
		inOrder.verify(transactionManager).commit(any(TransactionStatus.class));

		verify(transactionManager, times(1)).getTransaction(any(TransactionDefinition.class));
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("포트원 일시 장애(PortOneUnavailableException)는 트랜잭션을 시작하기 전에 그대로 전파되고 주문은 잠그지 않는다")
	void complete_portOneUnavailable_propagatesWithoutStartingTransaction() {
		// given
		given(portOneClient.getPayment(PAYMENT_ID)).willThrow(
			new PortOneUnavailableException("포트원 일시 장애"));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PortOneUnavailableException.class);

		verifyNoInteractions(transactionManager, userRepository, orderRepository,
			paymentRepository, resultService);
	}

	@Test
	@DisplayName("트랜잭션 안에서 검증이 실패하면 롤백되고 예외가 그대로 전파된다")
	void complete_validationFailure_rollsBack() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE - 1000));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class);

		verify(transactionManager).rollback(any(TransactionStatus.class));
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
	}

	// ===== 정상 확정 =====

	@Test
	@DisplayName("포트원이 PAID 와 일치하는 금액을 돌려주면 주문이 PAID 가 되고 Payment 저장과 초기 Result 생성이 이뤄진다")
	void complete_paidAndAmountMatches_marksOrderPaid() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

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
	@DisplayName("결제 확정 시 Discord 알림을 직접 보내지 않고 PaymentCompletedEvent(orderId, paymentPkId, amount) 를 발행한다")
	void complete_paid_publishesPaymentCompletedEvent() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		verify(eventPublisher).publishEvent(
			new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, (long) PRICE));
	}

	// ===== 검증 실패 =====

	@Test
	@DisplayName("포트원 결제 금액이 주문 금액과 다르면 PaymentException 이 나고 Payment 는 저장되지 않는다")
	void complete_amountMismatch_throwsAndDoesNotSavePayment() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE - 1000));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 금액이 일치하지 않습니다.");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(orderRepository, never()).save(any(Order.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@Test
	@DisplayName("포트원이 모르는 상태 문자열을 돌려주면 주문을 PENDING 그대로 반환하고 Payment 는 저장하지 않는다")
	void complete_unknownStatus_returnsOrderUnchanged() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("SOMETHING_NEW", PRICE));

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result).isSameAs(order);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
		verify(transactionManager).commit(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("포트원 상태가 PAY_PENDING 이면 READY 로 매핑돼 주문을 PENDING 그대로 반환한다")
	void complete_payPending_returnsOrderUnchanged() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAY_PENDING", PRICE));

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(eventPublisher);
	}

	@ParameterizedTest(name = "포트원 상태 {0}")
	@ValueSource(strings = {"FAILED", "CANCELLED"})
	@DisplayName("포트원 상태가 실패·취소면 주문을 FAILED 로 바꾸지 않고 PENDING 그대로 돌려준다 (웹훅과 달리 markOrderFailed 를 타지 않는다)")
	void complete_failedOrCancelledStatus_returnsOrderUnchanged(String status) {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse(status, PRICE));

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result).isSameAs(order);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any(Order.class));
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
		verify(transactionManager).commit(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("이미 PAID 인 주문은 포트원 응답과 무관하게 그대로 반환하고 중복 검사·저장을 하지 않는다")
	void complete_alreadyPaid_returnsOrderWithoutSaving() {
		// given
		Order order = createOrder(OrderStatus.PAID);
		givenRequester();
		givenLockedOrder(order);
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result).isSameAs(order);
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		verifyNoInteractions(paymentRepository, resultService, eventPublisher);
		verify(orderRepository, never()).save(any(Order.class));
	}

	@Test
	@DisplayName("같은 paymentId 의 Payment 가 이미 있으면 '이미 처리된 결제입니다.' PaymentException 이 나고 저장은 없다")
	void complete_duplicatePaymentId_throws() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		given(paymentRepository.findByImpUid(PAYMENT_ID)).willReturn(
			Optional.of(Payment.create(PAYMENT_ID, "order_other", (long) PRICE,
				PaymentStatus.PAID, 99L, USER_ID, SUB_CATEGORY_ID)));
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 처리된 결제입니다.");

		verify(paymentRepository, never()).save(any(Payment.class));
		verify(orderRepository, never()).save(any(Order.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@Test
	@DisplayName("CANCELLED 주문에 결제 완료 요청이 오면 OrderStateException 이 나고 Payment 는 저장되지 않는다")
	void complete_cancelledOrder_throwsOrderStateException() {
		// given: 멱등 검사(PAID 조기 반환)는 통과하고 markPaid 가드에서 걸린다
		Order order = createOrder(OrderStatus.CANCELLED);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED 에서 PAID 로");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isNull();
		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
		verify(transactionManager).rollback(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("타인의 PENDING 주문에 결제 완료 요청을 보내면 AccessDeniedException 이 나고 중복 검사와 저장은 일어나지 않는다")
	void complete_otherUsersOrder_throwsAccessDenied() {
		// given: 요청자는 USER_ID(1L), 주문 소유자는 2L
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		ReflectionTestUtils.setField(order, "userId", OTHER_USER_ID);
		givenLockedOrder(order);
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 결제 완료 처리할 수 있습니다.");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verifyNoInteractions(paymentRepository, resultService, eventPublisher);
		verify(orderRepository, never()).save(any(Order.class));
		verify(transactionManager).rollback(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("타인의 PAID 주문은 멱등 반환 대신 AccessDeniedException 으로 거부해 주문 정보가 새지 않는다")
	void complete_otherUsersPaidOrder_throwsAccessDeniedInsteadOfReturningOrder() {
		// given
		givenRequester();
		Order order = createOrder(OrderStatus.PAID);
		ReflectionTestUtils.setField(order, "userId", OTHER_USER_ID);
		givenLockedOrder(order);
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 결제 완료 처리할 수 있습니다.");

		verifyNoInteractions(paymentRepository, resultService, eventPublisher);
	}

	@Test
	@DisplayName("소유자(userId)가 없는 주문은 누구의 것도 아니므로 AccessDeniedException 으로 거부한다")
	void complete_orderWithoutOwner_throwsAccessDenied() {
		// given
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		ReflectionTestUtils.setField(order, "userId", null);
		givenLockedOrder(order);
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(paymentRepository, resultService, eventPublisher);
	}

	@Test
	@DisplayName("요청자를 찾을 수 없으면 주문을 잠그기 전에 PaymentException 이 난다")
	void complete_unknownRequester_throwsBeforeLockingOrder() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");

		verifyNoInteractions(orderRepository, paymentRepository, resultService, eventPublisher);
	}

	@Test
	@DisplayName("주문을 찾을 수 없으면 '주문을 찾을 수 없습니다.' PaymentException 이 난다")
	void complete_orderNotFound_throws() {
		// given
		givenRequester();
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.empty());
		givenPortOneReturns(portOneResponse("PAID", PRICE));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("주문을 찾을 수 없습니다.");

		verifyNoInteractions(paymentRepository, resultService, eventPublisher);
	}

	// ===== customData 대조 =====

	@Test
	@DisplayName("포트원 customData 의 merchantUid 가 주문 번호와 다르면 PaymentException 이 나고 주문·Payment·Result 는 바뀌지 않는다")
	void complete_customDataMerchantUidMismatch_throwsAndSavesNothing() {
		// given: 결제 P 는 customData 상 order_other 에 묶여 있는데 요청은 MERCHANT_UID 주문을 가리킨다
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		PortOnePaymentResponse response = portOneResponse("PAID", PRICE);
		response.setCustomData("{\"merchantUid\":\"order_other_999\",\"subCategoryId\":1}");
		givenPortOneReturns(response);

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보의 주문 번호가 일치하지 않습니다.");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentId()).isNull();
		verify(paymentRepository, never()).save(any(Payment.class));
		verify(orderRepository, never()).save(any(Order.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@Test
	@DisplayName("포트원 customData 의 merchantUid 가 주문 번호와 같으면 정상적으로 PAID 처리된다")
	void complete_customDataMerchantUidMatches_marksOrderPaid() {
		// given
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponseWithCustomData("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(result.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);
		verify(paymentRepository).save(any(Payment.class));
		verify(resultService).createInitialResult(any(Payment.class), eq(order));
	}

	@ParameterizedTest(name = "customData={0}")
	@NullSource
	@ValueSource(strings = {"", "   "})
	@DisplayName("포트원 customData 가 비어 있으면 주문 번호 대조를 건너뛰고(하위 호환) 정상적으로 PAID 처리된다")
	void complete_blankCustomData_skipsMerchantUidCheck(String customData) {
		// given
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		PortOnePaymentResponse response = portOneResponse("PAID", PRICE);
		response.setCustomData(customData);
		givenPortOneReturns(response);
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		Order result = paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(result.getPaymentId()).isEqualTo(PAYMENT_ID);
		verify(paymentRepository).save(any(Payment.class));
		verify(resultService).createInitialResult(any(Payment.class), eq(order));
	}

	@Test
	@DisplayName("customData 가 JSON 이 아니면 WebhookCustomData 의 PaymentException 이 그대로 나고 저장은 없다")
	void complete_malformedCustomData_throwsPaymentException() {
		// given
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		PortOnePaymentResponse response = portOneResponse("PAID", PRICE);
		response.setCustomData("not-json");
		givenPortOneReturns(response);

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 API 응답의 customData 파싱 중 오류 발생");

		verify(paymentRepository, never()).save(any(Payment.class));
		verifyNoInteractions(resultService, eventPublisher);
	}

	@Test
	@DisplayName("Payment 저장에서 UNIQUE 위반이 나면 PaidOrderFinalizer 가 바꾼 '이미 처리된 결제입니다.' PaymentException 이 그대로 전파되고 Result 는 만들지 않는다")
	void complete_duplicateImpUidOnSave_propagatesAlreadyProcessedPaymentException() {
		// given: findByImpUid 선검사는 통과했지만(동시 요청) INSERT 에서 UNIQUE 에 걸린다
		givenRequester();
		Order order = createOrder(OrderStatus.PENDING);
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		// Spring 이 Hibernate 의 UNIQUE 위반을 번역한 모양 그대로(cause = ConstraintViolationException(UNIQUE))
		given(paymentRepository.save(any(Payment.class))).willThrow(
			new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("could not execute statement",
					new SQLIntegrityConstraintViolationException(
						"Duplicate entry 'pay_test_001' for key 'payments.imp_uid'", "23000", 1062),
					"insert into payments ...", ConstraintKind.UNIQUE, "payments.imp_uid")));

		// when & then
		assertThatThrownBy(() -> paymentConfirmService.complete(USERNAME, completeRequest()))
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 처리된 결제입니다.");

		verifyNoInteractions(resultService, eventPublisher);
		verify(transactionManager).rollback(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("포트원 조회는 요청의 paymentId 로 정확히 한 번만 호출된다")
	void complete_callsPortOneExactlyOnce() {
		// given
		Order order = createOrder(OrderStatus.PENDING);
		givenRequester();
		givenLockedOrder(order);
		givenNoDuplicatePayment();
		givenPortOneReturns(portOneResponse("PAID", PRICE));
		givenOrderSaveReturnsArgument();
		givenPaymentSaveAssignsId();

		// when
		paymentConfirmService.complete(USERNAME, completeRequest());

		// then
		verify(portOneClient, times(1)).getPayment(PAYMENT_ID);
		verifyNoMoreInteractions(portOneClient);
	}
}
