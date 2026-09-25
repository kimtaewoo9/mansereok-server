package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 해석 시작을 허용하는 규칙 검증. 결제가 PAID 이고, 요청자 본인의 결제이고, 결제한 상품이 해석하려는 상품과 같을 때만 넘겨받은
 * 결과 변경을 한 번 부른다. 어긋나면 모두 같은 메시지로 거부하고 결과 변경을 부르지 않는다.
 *
 * <p>결제 조회 스텁은 행 잠금 조회(findByIdWithLock)에 정확한 PK 로 건다. 코드가 잠그지 않는 findById 로 읽거나 다른 PK 로
 * 읽으면 strict stubs 가 테스트를 실패시킨다. 결과 변경은 목 대신 부른 결제 PK 를 모으는 람다로 받아, 불렸는지를 값으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEntitlementServiceTest {

	private static final String USERNAME = "testUser";
	private static final Long USER_ID = 1L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final Long PAID_PRODUCT_ID = 3L;
	private static final String REJECTED_MESSAGE = "유효한 결제 정보가 아닙니다.";

	@InjectMocks
	private PaymentEntitlementService paymentEntitlementService;

	@Mock
	private UserRepository userRepository;
	@Mock
	private PaymentRepository paymentRepository;

	// 결과 변경이 불린 결제 PK 를 부른 순서대로 모은다.
	private final List<Long> startedPaymentIds = new ArrayList<>();

	@Nested
	@DisplayName("요청자가 있으면")
	class WhenUserExists {

		@BeforeEach
		void givenUser() {
			given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));
		}

		@Test
		@DisplayName("본인의 PAID 결제이고 결제한 상품으로 해석하면 결과 변경을 결제 PK 로 한 번 부른다")
		void startsWhenOwnPaidPaymentOfSameProduct() {
			// given
			givenLockedPayment(payment(PaymentStatus.PAID, USER_ID, PAID_PRODUCT_ID));

			// when
			paymentEntitlementService.startInterpretation(PAYMENT_PK_ID, USERNAME, PAID_PRODUCT_ID,
				startedPaymentIds::add);

			// then
			assertThat(startedPaymentIds).containsExactly(PAYMENT_PK_ID);
		}

		@ParameterizedTest(name = "[{index}] 결제 상태 {0}")
		@EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
		@DisplayName("결제 상태가 PAID 가 아니면(환불 진행 중 CANCEL_REQUESTED 포함) 같은 메시지로 거부하고 결과를 바꾸지 않는다")
		void rejectsWhenNotPaid(PaymentStatus status) {
			// given
			givenLockedPayment(payment(status, USER_ID, PAID_PRODUCT_ID));

			// when & then
			assertRejectedWithoutStarting(PAYMENT_PK_ID, PAID_PRODUCT_ID);
		}

		@Test
		@DisplayName("탈퇴 처리로 결제의 userId 가 비었으면 같은 메시지로 거부하고 결과를 바꾸지 않는다")
		void rejectsWhenPaymentHasNoOwner() {
			// given
			givenLockedPayment(payment(PaymentStatus.PAID, null, PAID_PRODUCT_ID));

			// when & then
			assertRejectedWithoutStarting(PAYMENT_PK_ID, PAID_PRODUCT_ID);
		}

		@Test
		@DisplayName("다른 사용자의 결제면 존재 여부를 드러내지 않는 같은 메시지로 거부하고 결과를 바꾸지 않는다")
		void rejectsWhenOtherUsersPayment() {
			// given
			givenLockedPayment(payment(PaymentStatus.PAID, 999L, PAID_PRODUCT_ID));

			// when & then
			assertRejectedWithoutStarting(PAYMENT_PK_ID, PAID_PRODUCT_ID);
		}

		@Test
		@DisplayName("상품 3 을 결제하고 상품 5 로 해석하려 하면 같은 메시지로 거부하고 결과를 바꾸지 않는다")
		void rejectsWhenProductDiffersFromPaidProduct() {
			// given
			givenLockedPayment(payment(PaymentStatus.PAID, USER_ID, 3L));

			// when & then
			assertRejectedWithoutStarting(PAYMENT_PK_ID, 5L);
		}

		@Test
		@DisplayName("결제가 없으면 같은 메시지로 거부하고 결과를 바꾸지 않는다")
		void rejectsWhenPaymentNotFound() {
			// given
			given(paymentRepository.findByIdWithLock(PAYMENT_PK_ID)).willReturn(Optional.empty());

			// when & then
			assertRejectedWithoutStarting(PAYMENT_PK_ID, PAID_PRODUCT_ID);
		}

		@Test
		@DisplayName("paymentId 가 null 이면 결제를 조회하지 않고 같은 메시지로 거부한다")
		void rejectsNullPaymentIdWithoutLookup() {
			// when & then
			assertRejectedWithoutStarting(null, PAID_PRODUCT_ID);
			verifyNoInteractions(paymentRepository);
		}

		private void assertRejectedWithoutStarting(Long paymentPkId, Long subCategoryId) {
			assertThatThrownBy(() -> paymentEntitlementService.startInterpretation(paymentPkId, USERNAME,
				subCategoryId, startedPaymentIds::add))
				.isInstanceOf(PaymentException.class)
				.hasMessage(REJECTED_MESSAGE);
			assertThat(startedPaymentIds).as("결과 변경이 불린 결제 PK").isEmpty();
		}
	}

	@Test
	@DisplayName("요청자를 찾을 수 없으면 결제를 조회하지 않고 PaymentException 으로 거부한다")
	void rejectsUnknownUserWithoutLookup() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentEntitlementService.startInterpretation(PAYMENT_PK_ID, USERNAME,
			PAID_PRODUCT_ID, startedPaymentIds::add))
			.isInstanceOf(PaymentException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");
		assertThat(startedPaymentIds).isEmpty();
		verifyNoInteractions(paymentRepository);
	}

	// ===== 테스트 데이터 =====

	private void givenLockedPayment(Payment payment) {
		given(paymentRepository.findByIdWithLock(PAYMENT_PK_ID)).willReturn(Optional.of(payment));
	}

	private static User user() {
		User user = User.create(USERNAME, "김태우", "password", "taewoo@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private static Payment payment(PaymentStatus status, Long ownerId, Long subCategoryId) {
		Payment payment = Payment.create("pay_test_001", "order_test_001", 10000L, status, 10L, ownerId,
			subCategoryId);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
		return payment;
	}
}
