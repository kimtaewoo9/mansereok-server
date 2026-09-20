package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderDiscountRestorer;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 환불 흐름 "DB 취소 요청 기록(A) → 포트원 취소 → DB 확정(B)" 검증.
 *
 * <p>TransactionTemplate 은 mock PlatformTransactionManager 로 만든 실제 인스턴스라서 트랜잭션 경계(begin·commit·rollback)
 * 횟수와 순서를 mock 으로 관찰할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundServiceTest {

	private static final String USERNAME = "testUser";
	private static final Long USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long SUB_CATEGORY_ID = 1L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final long PRICE = 10000L;
	private static final String REASON = "단순 변심";

	@Mock
	private UserRepository userRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private ResultService resultService;
	@Mock
	private OrderDiscountRestorer orderDiscountRestorer;
	@Mock
	private PortOneClient portOneClient;
	@Mock
	private PlatformTransactionManager transactionManager;

	private PaymentRefundService paymentRefundService;

	@BeforeEach
	void setUp() {
		// TransactionTemplate 은 실제 인스턴스. 트랜잭션마다 새 TransactionStatus 를 돌려준다.
		given(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.willAnswer(invocation -> new SimpleTransactionStatus(true));
		paymentRefundService = new PaymentRefundService(userRepository, paymentRepository,
			orderRepository, resultService, orderDiscountRestorer, portOneClient,
			transactionManager);
	}

	// ===== 픽스처 =====

	private User user(Long id) {
		User user = User.create(USERNAME, "김태우", "password", "taewoo@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private Payment payment(PaymentStatus status, long amount, String impUid) {
		Payment payment = Payment.create(impUid, MERCHANT_UID, amount, status, ORDER_ID, USER_ID,
			SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		return payment;
	}

	private Payment paidPayment() {
		return payment(PaymentStatus.PAID, PRICE, PAYMENT_ID);
	}

	private Order order(OrderStatus status, Long couponId) {
		Order order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, (int) PRICE, (int) PRICE,
			null, couponId, status, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		return order;
	}

	private void givenRequester() {
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user(USER_ID)));
	}

	/** 트랜잭션 A·B 의 잠금 조회가 같은 인스턴스를 돌려주도록 stub 한다. C 는 findById 를 쓰므로 필요한 테스트에서 따로 stub 한다. */
	private void givenLockedPaymentAndOrder(Payment payment, Order order) {
		given(paymentRepository.findByImpUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
		given(orderRepository.findByMerchantUidWithLock(MERCHANT_UID)).willReturn(
			Optional.of(order));
	}

	// ===== (a) 정상 흐름 =====

	@Test
	@DisplayName("정상 환불은 CANCEL_REQUESTED 커밋 → 포트원 취소 → CANCELLED 확정·주문 취소·Result 삭제·할인 복구 순서로 진행된다")
	void cancel_success_runsInOrderAcrossThreeSteps() {
		// given
		givenRequester();
		Payment payment = spy(paidPayment());
		Order order = spy(order(OrderStatus.PAID, 100L));
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));

		// when
		paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON);

		// then
		InOrder inOrder = inOrder(payment, paymentRepository, orderRepository, transactionManager,
			portOneClient, order, resultService, orderDiscountRestorer);
		// A: 결제 → 주문 순서로 잠근 뒤 취소 요청 기록 후 커밋
		inOrder.verify(paymentRepository).findByImpUidWithLock(PAYMENT_ID);
		inOrder.verify(orderRepository).findByMerchantUidWithLock(MERCHANT_UID);
		inOrder.verify(payment).markCancelRequested();
		inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
		// 트랜잭션 밖: 포트원 취소
		inOrder.verify(portOneClient).cancelPayment(PAYMENT_ID, REASON);
		// B: A 와 같은 순서(결제 → 주문)로 잠근 뒤 확정
		inOrder.verify(paymentRepository).findByImpUidWithLock(PAYMENT_ID);
		inOrder.verify(orderRepository).findByMerchantUidWithLock(MERCHANT_UID);
		inOrder.verify(payment).markCancelled();
		inOrder.verify(order).markCancelled();
		inOrder.verify(resultService).deleteInitialResult(PAYMENT_PK_ID);
		inOrder.verify(orderDiscountRestorer).restore(order);
		inOrder.verify(transactionManager).commit(any(TransactionStatus.class));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
		verify(transactionManager, never()).rollback(any(TransactionStatus.class));
		verify(payment, never()).revertCancelRequest();
		// B 는 잠금 없는 findById 로 결제를 읽지 않는다 (OSIV 에서는 잠금을 전혀 잡지 않으므로)
		verify(paymentRepository, never()).findById(anyLong());
		verify(paymentRepository, times(2)).findByImpUidWithLock(PAYMENT_ID);
		verify(orderRepository, times(2)).findByMerchantUidWithLock(MERCHANT_UID);
	}

	@Test
	@DisplayName("세 트랜잭션 모두 REQUIRES_NEW 로 시작해 바깥 트랜잭션에 합류하지 않는다")
	void cancel_everyTransactionStartsWithRequiresNew() {
		// given
		givenRequester();
		Payment payment = paidPayment();
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));

		// when
		paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON);

		// then: A·B 두 트랜잭션 모두 REQUIRES_NEW 정의로 시작한다
		verify(transactionManager, times(2)).getTransaction(argThat(definition ->
			definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
		verify(transactionManager, never()).getTransaction(argThat(definition ->
			definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW));
	}

	// ===== (b) 포트원 실패 =====

	@Test
	@DisplayName("포트원 취소가 실패하면 별도 트랜잭션으로 PAID 로 되돌리고 예외를 다시 던지며 확정 단계는 실행되지 않는다")
	void cancel_portOneFails_revertsToPaidAndRethrows() {
		// given
		givenRequester();
		Payment payment = spy(paidPayment());
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.of(payment));
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));
		willThrow(new PortOneUnavailableException("포트원 응답 없음"))
			.given(portOneClient).cancelPayment(PAYMENT_ID, REASON);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasMessage("포트원 응답 없음");

		InOrder inOrder = inOrder(payment, portOneClient, transactionManager);
		inOrder.verify(payment).markCancelRequested();
		inOrder.verify(portOneClient).cancelPayment(PAYMENT_ID, REASON);
		inOrder.verify(payment).revertCancelRequest();
		inOrder.verify(transactionManager).commit(any(TransactionStatus.class));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verify(payment, never()).markCancelled();
		verify(resultService, never()).deleteInitialResult(anyLong());
		verifyNoInteractions(orderDiscountRestorer);
		// A 커밋 + C 커밋 = 2회, B 는 시작되지 않는다
		verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
		verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
	}

	@Test
	@DisplayName("포트원 실패 뒤 되돌리기(C)까지 실패하면 C 의 예외를 삼키고 원래 포트원 예외를 던지며 확정 단계는 시작되지 않는다")
	void cancel_portOneFailsAndRevertFails_rethrowsOriginalAndLeavesCancelRequested() {
		// given
		givenRequester();
		Payment payment = spy(paidPayment());
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));
		willThrow(new PortOneUnavailableException("포트원 응답 없음"))
			.given(portOneClient).cancelPayment(PAYMENT_ID, REASON);
		// C 의 재조회가 DB 장애로 실패한다
		given(paymentRepository.findById(PAYMENT_PK_ID))
			.willThrow(new DataAccessResourceFailureException("db down"));

		// when & then: 호출자가 받는 예외는 C 의 예외가 아니라 원래 포트원 예외다
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PortOneUnavailableException.class)
			.hasMessage("포트원 응답 없음");

		// A 는 커밋됐고 C 는 롤백됐다. B 는 시작되지 않는다 (getTransaction = A + C 2회)
		verify(transactionManager, times(1)).commit(any(TransactionStatus.class));
		verify(transactionManager, times(1)).rollback(any(TransactionStatus.class));
		verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
		verify(payment, never()).revertCancelRequest();
		verify(payment, never()).markCancelled();
		verify(resultService, never()).deleteInitialResult(anyLong());
		verifyNoInteractions(orderDiscountRestorer);
		// 메모리상 결제는 A 가 기록한 CANCEL_REQUESTED 그대로 남아 수동 확인 대상이 된다
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	@DisplayName("포트원 취소 성공 뒤 DB 확정이 실패하면 CANCEL_REQUESTED 로 남긴 채 예외를 전파하고 PAID 로 되돌리지 않는다")
	void cancel_finalizeFails_leavesCancelRequestedForManualCheck() {
		// given
		givenRequester();
		Payment payment = spy(paidPayment());
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));
		willThrow(new DataAccessResourceFailureException("connection lost"))
			.given(resultService).deleteInitialResult(PAYMENT_PK_ID);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(DataAccessResourceFailureException.class);

		verify(portOneClient).cancelPayment(PAYMENT_ID, REASON);
		verify(payment, never()).revertCancelRequest();
		// B 는 롤백되므로 메모리상 전이는 무의미하고, DB 에는 A 가 커밋한 CANCEL_REQUESTED 가 남는다
		verify(transactionManager).rollback(any(TransactionStatus.class));
		verify(transactionManager, times(1)).commit(any(TransactionStatus.class));
		verifyNoInteractions(orderDiscountRestorer);
	}

	// ===== (c) 동시 환불 차단 =====

	@Test
	@DisplayName("이미 CANCEL_REQUESTED 인 결제는 포트원을 호출하지 않고 '취소가 진행 중입니다.' 로 거부한다")
	void cancel_alreadyCancelRequested_rejectsWithoutCallingPortOne() {
		// given
		givenRequester();
		Payment payment = payment(PaymentStatus.CANCEL_REQUESTED, PRICE, PAYMENT_ID);
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("취소가 진행 중입니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
		verifyNoInteractions(portOneClient, orderDiscountRestorer, resultService);
		verify(transactionManager).rollback(any(TransactionStatus.class));
		verify(transactionManager, never()).commit(any(TransactionStatus.class));
	}

	@Test
	@DisplayName("이미 CANCELLED 인 결제는 '이미 취소된 결제입니다.' 로 거부한다")
	void cancel_alreadyCancelled_rejects() {
		// given
		givenRequester();
		Payment payment = payment(PaymentStatus.CANCELLED, PRICE, PAYMENT_ID);
		Order order = order(OrderStatus.CANCELLED, null);
		givenLockedPaymentAndOrder(payment, order);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 취소된 결제입니다.");

		verifyNoInteractions(portOneClient, orderDiscountRestorer, resultService);
	}

	@Test
	@DisplayName("PAID 가 아닌 결제(READY)는 포트원을 호출하기 전에 거부한다")
	void cancel_paymentNotPaid_rejectsBeforeCallingPortOne() {
		// given
		givenRequester();
		Payment payment = payment(PaymentStatus.READY, PRICE, PAYMENT_ID);
		Order order = order(OrderStatus.PENDING, null);
		givenLockedPaymentAndOrder(payment, order);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 완료 상태가 아니라 취소할 수 없습니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
		verifyNoInteractions(portOneClient, orderDiscountRestorer, resultService);
	}

	@Test
	@DisplayName("주문이 CANCELLED 로 갈 수 없는 상태면 포트원을 호출하기 전에 거부한다")
	void cancel_orderNotCancellable_rejectsBeforeCallingPortOne() {
		// given
		givenRequester();
		Payment payment = paidPayment();
		Order order = order(OrderStatus.PENDING, null);
		givenLockedPaymentAndOrder(payment, order);

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("취소할 수 없는 주문 상태입니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verifyNoInteractions(portOneClient, orderDiscountRestorer, resultService);
	}

	// ===== (d) 궁합 상품 =====

	@Test
	@DisplayName("Result 없이 CompatibilityResult 만 있는 궁합 상품도 INPUT_REQUIRED 면 환불된다")
	void cancel_compatibilityProduct_isRefundable() {
		// given: ResultService 가 CompatibilityResult 쪽 상태를 찾아 돌려준다
		givenRequester();
		Payment payment = paidPayment();
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.INPUT_REQUIRED));

		// when
		paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON);

		// then
		verify(portOneClient).cancelPayment(PAYMENT_ID, REASON);
		verify(resultService).deleteInitialResult(PAYMENT_PK_ID);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	// ===== (e) INPUT_REQUIRED 가 아니면 거부 =====

	@Test
	@DisplayName("결과가 PROCESSING 이면 기존 메시지로 거부하고 포트원을 호출하지 않는다")
	void cancel_resultProcessing_rejects() {
		// given
		givenRequester();
		Payment payment = paidPayment();
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(ResultStatus.PROCESSING));

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 사주 해석이 진행되었거나 완료된 건은 환불할 수 없습니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		verifyNoInteractions(portOneClient, orderDiscountRestorer);
		verify(resultService, never()).deleteInitialResult(anyLong());
	}

	@Test
	@DisplayName("Result 도 CompatibilityResult 도 없으면 기존 메시지로 거부한다")
	void cancel_resultMissing_rejects() {
		// given
		givenRequester();
		Payment payment = paidPayment();
		Order order = order(OrderStatus.PAID, null);
		givenLockedPaymentAndOrder(payment, order);
		given(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("해당 결제에 대한 결과 정보를 찾을 수 없습니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		verifyNoInteractions(portOneClient, orderDiscountRestorer);
	}

	// ===== (f) 무료 결제 거부 =====

	@Test
	@DisplayName("0원 결제는 환불 대상이 아니라서 거부한다")
	void cancel_zeroAmount_rejects() {
		// given
		givenRequester();
		Payment payment = payment(PaymentStatus.PAID, 0L, PAYMENT_ID);
		given(paymentRepository.findByImpUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("무료 이벤트 결제는 환불/취소 대상이 아닙니다.");

		verifyNoInteractions(portOneClient, orderRepository, resultService, orderDiscountRestorer);
	}

	@Test
	@DisplayName("free_ 접두사 결제는 금액과 무관하게 환불 대상이 아니라서 거부한다")
	void cancel_freePrefixedImpUid_rejects() {
		// given
		String freeImpUid = MerchantUidGenerator.FREE_PREFIX + "free_order_001";
		givenRequester();
		Payment payment = payment(PaymentStatus.PAID, PRICE, freeImpUid);
		given(paymentRepository.findByImpUidWithLock(freeImpUid)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, freeImpUid, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("무료 이벤트 결제는 환불/취소 대상이 아닙니다.");

		verifyNoInteractions(portOneClient, orderRepository, resultService, orderDiscountRestorer);
	}

	// ===== (g) 타인 결제 거부 =====

	@Test
	@DisplayName("타인의 결제는 거부하고 포트원·주문 잠금·결과 조회를 하지 않는다")
	void cancel_otherUsersPayment_rejects() {
		// given: 요청자는 OTHER_USER_ID, 결제 소유자는 USER_ID
		given(userRepository.findByUsername(USERNAME)).willReturn(
			Optional.of(user(OTHER_USER_ID)));
		Payment payment = paidPayment();
		given(paymentRepository.findByImpUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("본인의 결제 건만 취소할 수 있습니다.");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
		verifyNoInteractions(portOneClient, orderRepository, resultService, orderDiscountRestorer);
	}

	@Test
	@DisplayName("결제 정보가 없으면 거부하고 포트원을 호출하지 않는다")
	void cancel_paymentMissing_rejects() {
		// given
		givenRequester();
		given(paymentRepository.findByImpUidWithLock(anyString())).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentRefundService.cancel(USERNAME, PAYMENT_ID, REASON))
			.isInstanceOf(PaymentException.class)
			.hasMessage("결제 정보를 찾을 수 없습니다.");

		verifyNoInteractions(portOneClient, orderRepository, resultService, orderDiscountRestorer);
	}
}
