package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 해석 요청의 paymentId(PK) 가 요청자 본인의 결제 완료 건인지 확인하는 규칙 검증.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEntitlementServiceTest {

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
	private PaymentEntitlementService paymentEntitlementService;

	@Mock
	private UserRepository userRepository;
	@Mock
	private PaymentRepository paymentRepository;

	// ===== 테스트 픽스처 =====

	private User createUser() {
		User user = User.create(USERNAME, BUYER_NAME, "password", BUYER_EMAIL,
			LocalDate.now(), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private Payment createPaidPayment() {
		Payment payment = Payment.create(PAYMENT_ID, MERCHANT_UID, (long) PRICE,
			PaymentStatus.PAID, ORDER_ID, USER_ID, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		return payment;
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
		assertThatCode(() -> paymentEntitlementService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("결제가 없으면 '유효한 결제 정보가 아닙니다.' PaymentException 이 난다")
	void verifyPaidOwnership_paymentNotFound_throws() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(
			() -> paymentEntitlementService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
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
		assertThatThrownBy(
			() -> paymentEntitlementService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
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
		assertThatThrownBy(
			() -> paymentEntitlementService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 결제 정보가 아닙니다.");
	}

	@Test
	@DisplayName("paymentId 가 null 이면 결제를 조회하지 않고 같은 메시지의 PaymentException 이 난다")
	void verifyPaidOwnership_nullPaymentId_throwsWithoutLookup() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(createUser()));

		// when & then
		assertThatThrownBy(() -> paymentEntitlementService.verifyPaidOwnership(null, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("유효한 결제 정보가 아닙니다.");
		verify(paymentRepository, never()).findById(any());
	}

	@Test
	@DisplayName("사용자를 찾을 수 없으면 결제를 조회하지 않고 PaymentException 이 난다")
	void verifyPaidOwnership_userNotFound_throwsWithoutLookup() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(
			() -> paymentEntitlementService.verifyPaidOwnership(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");
		verify(paymentRepository, never()).findById(any());
	}
}
