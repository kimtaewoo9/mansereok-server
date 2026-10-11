package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.dto.response.PaymentResponseDto;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

	private static final String USERNAME = "testUser";
	private static final Long USER_ID = 7L;
	private static final Long OTHER_USER_ID = 8L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;

	@InjectMocks
	private PaymentQueryService paymentQueryService;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ResultRepository resultRepository;

	// ===== 픽스처 =====

	private User user() {
		User user = User.create(USERNAME, "김태우", "password", "taewoo@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private Order orderOwnedBy(Long ownerId) {
		Order order = Order.create("order_test_001", ownerId, 1L, 10000, 10000, null, null,
			OrderStatus.PAID, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		ReflectionTestUtils.setField(order, "paymentPkId", PAYMENT_PK_ID);
		return order;
	}

	private Payment paymentWithId(Long id, String impUid) {
		Payment payment = Payment.create(impUid, "order_" + impUid, 10000L, PaymentStatus.PAID,
			ORDER_ID, USER_ID, 1L);
		ReflectionTestUtils.setField(payment, "id", id);
		return payment;
	}

	// ===== getOwnedOrder =====

	@Test
	@DisplayName("getOwnedOrder: 본인 주문이면 주문을 돌려준다")
	void getOwnedOrder_owner_returnsOrder() {
		// given
		Order order = orderOwnedBy(USER_ID);
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when
		Order found = paymentQueryService.getOwnedOrder(ORDER_ID, USERNAME);

		// then
		assertThat(found).isSameAs(order);
	}

	@Test
	@DisplayName("getOwnedOrder: 타인 주문이면 AccessDeniedException 을 던진다")
	void getOwnedOrder_otherUser_throwsAccessDenied() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(orderOwnedBy(OTHER_USER_ID)));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getOwnedOrder(ORDER_ID, USERNAME))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 조회할 수 있습니다.");
	}

	@Test
	@DisplayName("getOwnedOrder: 주문이 없으면 EntityNotFoundException 을 던진다")
	void getOwnedOrder_missing_throwsNotFound() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getOwnedOrder(ORDER_ID, USERNAME))
			.isInstanceOf(EntityNotFoundException.class);
		verifyNoInteractions(userRepository);
	}

	@Test
	@DisplayName("getOwnedOrder: 주문 userId 가 null(탈퇴 사용자)이면 NPE 대신 AccessDeniedException 을 던진다")
	void getOwnedOrder_nullOwner_throwsAccessDenied() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(orderOwnedBy(null)));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getOwnedOrder(ORDER_ID, USERNAME))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 조회할 수 있습니다.");
	}

	@Test
	@DisplayName("getOwnedOrder: 사용자를 찾을 수 없으면 EntityNotFoundException 을 던진다")
	void getOwnedOrder_userMissing_throwsNotFound() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(orderOwnedBy(USER_ID)));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getOwnedOrder(ORDER_ID, USERNAME))
			.isInstanceOf(EntityNotFoundException.class);
	}

	// ===== getOwnedOrderByPaymentPkId =====

	@Test
	@DisplayName("getOwnedOrderByPaymentPkId: 본인 주문이면 주문을 돌려준다")
	void getOwnedOrderByPaymentPkId_owner_returnsOrder() {
		// given
		Order order = orderOwnedBy(USER_ID);
		given(orderRepository.findByPaymentPkId(PAYMENT_PK_ID)).willReturn(Optional.of(order));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when
		Order found = paymentQueryService.getOwnedOrderByPaymentPkId(PAYMENT_PK_ID, USERNAME);

		// then
		assertThat(found).isSameAs(order);
	}

	@Test
	@DisplayName("getOwnedOrderByPaymentPkId: 타인 주문이면 AccessDeniedException 을 던진다")
	void getOwnedOrderByPaymentPkId_otherUser_throwsAccessDenied() {
		// given
		given(orderRepository.findByPaymentPkId(PAYMENT_PK_ID))
			.willReturn(Optional.of(orderOwnedBy(OTHER_USER_ID)));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when & then
		assertThatThrownBy(
			() -> paymentQueryService.getOwnedOrderByPaymentPkId(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 조회할 수 있습니다.");
	}

	@Test
	@DisplayName("getOwnedOrderByPaymentPkId: 주문이 없으면 EntityNotFoundException 을 던진다")
	void getOwnedOrderByPaymentPkId_missing_throwsNotFound() {
		// given
		given(orderRepository.findByPaymentPkId(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(
			() -> paymentQueryService.getOwnedOrderByPaymentPkId(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(EntityNotFoundException.class);
		verifyNoInteractions(userRepository);
	}

	@Test
	@DisplayName("getOwnedOrderByPaymentPkId: 주문 userId 가 null(탈퇴 사용자)이면 AccessDeniedException 을 던진다")
	void getOwnedOrderByPaymentPkId_nullOwner_throwsAccessDenied() {
		// given
		given(orderRepository.findByPaymentPkId(PAYMENT_PK_ID))
			.willReturn(Optional.of(orderOwnedBy(null)));
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));

		// when & then
		assertThatThrownBy(
			() -> paymentQueryService.getOwnedOrderByPaymentPkId(PAYMENT_PK_ID, USERNAME))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("본인의 주문만 조회할 수 있습니다.");
	}

	// ===== getPayments (PaymentService 에서 옮겨온 조회) =====

	@Test
	@DisplayName("getPayments: 결제마다 Result 상태를 붙이고, Result 가 없는 결제는 resultStatus 가 null 이다")
	void getPayments_mapsResultStatusPerPayment() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));
		Payment p1 = paymentWithId(1L, "imp_001");
		Payment p2 = paymentWithId(2L, "imp_002");
		given(paymentRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID))
			.willReturn(List.of(p1, p2));
		Result r1 = Result.createInitial(USER_ID, 1L, "상품");
		r1.completeInterpretation("해석", "요약");
		given(resultRepository.findByPaymentIdIn(List.of(1L, 2L))).willReturn(List.of(r1));

		// when
		List<PaymentResponseDto> dtos = paymentQueryService.getPayments(USERNAME);

		// then
		assertThat(dtos).hasSize(2);
		assertThat(dtos.get(0).getPaymentId()).isEqualTo("imp_001");
		assertThat(dtos.get(0).getResultStatus()).isEqualTo(ResultStatus.COMPLETED);
		assertThat(dtos.get(1).getPaymentId()).isEqualTo("imp_002");
		assertThat(dtos.get(1).getResultStatus()).isNull();
		verify(resultRepository, times(1)).findByPaymentIdIn(List.of(1L, 2L));
	}

	@Test
	@DisplayName("getPayments: 사용자를 찾을 수 없으면 IllegalArgumentException 을 던진다")
	void getPayments_userMissing_throwsIllegalArgument() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getPayments(USERNAME))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");
		verifyNoInteractions(paymentRepository, resultRepository);
	}

	@Test
	@DisplayName("getPayment: 결제가 없으면 EntityNotFoundException 을 던진다")
	void getPayment_missing_throwsNotFound() {
		// given
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentQueryService.getPayment(PAYMENT_PK_ID))
			.isInstanceOf(EntityNotFoundException.class);
	}
}
