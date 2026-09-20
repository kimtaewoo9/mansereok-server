package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaidOrderFinalizerTest {

	private static final Long ORDER_ID = 10L;
	private static final Long USER_ID = 1L;
	private static final Long SUB_CATEGORY_ID = 3L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final long AMOUNT = 9000L;
	private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 9, 21, 12, 30);

	@InjectMocks
	private PaidOrderFinalizer paidOrderFinalizer;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ResultService resultService;

	private Order order;

	/** 주문 저장 시점마다 주문 상태를 기록해 markPaid 가 첫 저장보다 먼저 일어났는지 확인한다. */
	private final List<OrderStatus> statusAtOrderSave = new ArrayList<>();

	@BeforeEach
	void setUp() {
		order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, 10000, 9000, "SALE10", null,
			OrderStatus.PENDING, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", ORDER_ID);

		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order saved = invocation.getArgument(0);
			statusAtOrderSave.add(saved.getStatus());
			return saved;
		});
	}

	/** Payment 저장이 성공해 PK 가 채워지는 정상 경로 stub. 필요한 테스트에서만 호출해 strict stubs 를 유지한다. */
	private void givenPaymentSaveAssignsId() {
		given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> {
			Payment saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", PAYMENT_PK_ID);
			return saved;
		});
	}

	/** Spring 이 Hibernate 제약 위반을 번역한 모양 그대로: DataIntegrityViolationException(cause = ConstraintViolationException(kind)). */
	private static DataIntegrityViolationException dataIntegrityViolation(ConstraintKind kind, String constraintName) {
		ConstraintViolationException cause = new ConstraintViolationException(
			"could not execute statement",
			new SQLIntegrityConstraintViolationException("constraint violated", "23000", 0),
			"insert into payments ...", kind, constraintName);
		return new DataIntegrityViolationException(cause.getMessage(), cause);
	}

	@Test
	@DisplayName("Payment 가 paymentId, merchantUid, amount, PAID, orderId, userId, subCategoryId 로 저장되고 그대로 반환된다")
	void finalizePaid_savesPaymentWithOrderFields() {
		// given
		givenPaymentSaveAssignsId();

		// when
		Payment returned = paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT);

		// then
		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		verify(paymentRepository).save(paymentCaptor.capture());
		Payment savedPayment = paymentCaptor.getValue();

		assertThat(savedPayment.getImpUid()).isEqualTo(PAYMENT_ID);
		assertThat(savedPayment.getMerchantUid()).isEqualTo(MERCHANT_UID);
		assertThat(savedPayment.getAmount()).isEqualTo(AMOUNT);
		assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(savedPayment.getUserId()).isEqualTo(USER_ID);
		assertThat(savedPayment.getSubCategoryId()).isEqualTo(SUB_CATEGORY_ID);

		assertThat(returned).isSameAs(savedPayment);
	}

	@Test
	@DisplayName("주문이 PAID, paymentId, paidAt 으로 바뀌고 paymentPkId 가 저장된 Payment 의 id 로 연결된다")
	void finalizePaid_marksOrderPaidAndLinksPayment() {
		// given
		givenPaymentSaveAssignsId();

		// when
		paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
		assertThat(order.getPaymentPkId()).isEqualTo(PAYMENT_PK_ID);

		// 주문은 두 번 저장되고, 첫 저장 시점에 이미 PAID 상태여야 한다
		verify(orderRepository, times(2)).save(order);
		assertThat(statusAtOrderSave).containsExactly(OrderStatus.PAID, OrderStatus.PAID);
	}

	@Test
	@DisplayName("초기 Result 생성이 저장된 Payment 와 주문으로 호출된다")
	void finalizePaid_createsInitialResultWithSavedPaymentAndOrder() {
		// given
		givenPaymentSaveAssignsId();

		// when
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT);

		// then
		verify(resultService).createInitialResult(savedPayment, order);
	}

	@Test
	@DisplayName("호출 순서는 주문 저장 → Payment 저장 → 주문 저장 → Result 생성이다")
	void finalizePaid_callsInOrder() {
		// given
		givenPaymentSaveAssignsId();

		// when
		Payment savedPayment = paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT);

		// then
		InOrder inOrder = inOrder(orderRepository, paymentRepository, resultService);
		inOrder.verify(orderRepository).save(order);
		inOrder.verify(paymentRepository).save(any(Payment.class));
		inOrder.verify(orderRepository).save(order);
		inOrder.verify(resultService).createInitialResult(savedPayment, order);
		inOrder.verifyNoMoreInteractions();
	}

	@Test
	@DisplayName("Payment 저장에서 imp_uid UNIQUE 위반(DataIntegrityViolationException)이 나면 '이미 처리된 결제입니다.' PaymentException 으로 바꿔 던지고 연관관계 연결과 Result 생성은 하지 않는다")
	void finalizePaid_duplicateImpUid_translatesToPaymentException() {
		// given
		DataIntegrityViolationException uniqueViolation =
			dataIntegrityViolation(ConstraintKind.UNIQUE, "payments.imp_uid");
		willThrow(uniqueViolation).given(paymentRepository).save(any(Payment.class));

		// when & then
		assertThatThrownBy(() -> paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT))
			.isInstanceOf(PaymentException.class)
			.hasMessage("이미 처리된 결제입니다.")
			.hasCause(uniqueViolation);

		// 주문 PAID 저장(1회)까지는 진행됐지만 연관관계 연결 저장과 Result 생성은 없다
		verify(orderRepository, times(1)).save(order);
		assertThat(order.getPaymentPkId()).isNull();
		verifyNoInteractions(resultService);
	}

	@Test
	@DisplayName("Payment 저장에서 UNIQUE 가 아닌 무결성 위반(NOT NULL 등)이 나면 '이미 처리된 결제' 로 바꾸지 않고 DataIntegrityViolationException 을 그대로 던진다")
	void finalizePaid_nonUniqueIntegrityViolation_isRethrownAsIs() {
		// given
		DataIntegrityViolationException notNullViolation =
			dataIntegrityViolation(ConstraintKind.OTHER, null);
		willThrow(notNullViolation).given(paymentRepository).save(any(Payment.class));

		// when & then
		assertThatThrownBy(() -> paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT))
			.isSameAs(notNullViolation);

		verify(orderRepository, times(1)).save(order);
		assertThat(order.getPaymentPkId()).isNull();
		verifyNoInteractions(resultService);
	}

	@Test
	@DisplayName("Payment 저장의 DataIntegrityViolationException 에 Hibernate 제약 위반 원인이 없으면 그대로 던진다")
	void finalizePaid_integrityViolationWithoutHibernateCause_isRethrownAsIs() {
		// given
		DataIntegrityViolationException unknownViolation =
			new DataIntegrityViolationException("could not execute statement");
		willThrow(unknownViolation).given(paymentRepository).save(any(Payment.class));

		// when & then
		assertThatThrownBy(() -> paidOrderFinalizer.finalizePaid(order, PAYMENT_ID, AMOUNT, PAID_AT))
			.isSameAs(unknownViolation);

		verifyNoInteractions(resultService);
	}
}
