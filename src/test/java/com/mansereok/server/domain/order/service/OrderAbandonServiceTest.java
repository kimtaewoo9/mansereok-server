package com.mansereok.server.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.service.PaymentUserLookup;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.support.fixture.TestOrders;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class OrderAbandonServiceTest {

	private static final Long ORDER_ID = 10L;
	private static final Long OWNER_ID = 1L;
	private static final String OWNER = "buyer";

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentUserLookup paymentUserLookup;
	@Mock
	private OrderExpirationService orderExpirationService;
	@InjectMocks
	private OrderAbandonService orderAbandonService;

	private void ownerSignedIn() {
		User owner = mock(User.class);
		given(owner.getId()).willReturn(OWNER_ID);
		given(paymentUserLookup.getByUsername(OWNER)).willReturn(owner);
	}

	@Test
	@DisplayName("결제 대기 주문이면 만료 경로로 EXPIRED 로 바꾸고 바뀐 주문을 돌려준다")
	void pendingOrderIsExpired() {
		// given
		givenOrderReadsAs(OrderStatus.PENDING, OrderStatus.EXPIRED);
		given(orderExpirationService.expireIfStillPending(ORDER_ID)).willReturn(true);

		// when
		Order abandoned = orderAbandonService.abandon(ORDER_ID, OWNER);

		// then
		assertThat(abandoned.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		then(orderExpirationService).should().expireIfStillPending(ORDER_ID);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(value = OrderStatus.class, names = {"EXPIRED", "FAILED"})
	@DisplayName("할인을 이미 돌려준 주문이면 같은 알림이 다시 와도 오류 없이 그 주문을 돌려준다")
	void alreadyReleasedOrderIsReturnedAsIs(OrderStatus status) {
		// given
		givenOrderReadsAs(status, status);
		given(orderExpirationService.expireIfStillPending(ORDER_ID)).willReturn(false);

		// when
		Order abandoned = orderAbandonService.abandon(ORDER_ID, OWNER);

		// then
		assertThat(abandoned.getStatus()).isEqualTo(status);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(value = OrderStatus.class, names = {"PAID", "CANCELLED"})
	@DisplayName("결제됐거나 환불된 주문이면 할인을 돌려주지 않고 400 으로 거절한다")
	void orderPastPaymentWindowIsRejected(OrderStatus status) {
		// given
		givenOrderReadsAs(status, status);
		given(orderExpirationService.expireIfStillPending(ORDER_ID)).willReturn(false);

		// when & then
		assertThatThrownBy(() -> orderAbandonService.abandon(ORDER_ID, OWNER))
			.isInstanceOf(OrderStateException.class)
			.hasMessage("이미 결제된 주문은 취소할 수 없습니다. 환불을 요청해 주세요.");
	}

	@Test
	@DisplayName("남의 주문이면 403 으로 거절하고 만료 경로를 부르지 않는다")
	void otherUsersOrderIsRejected() {
		// given
		ownerSignedIn();
		given(orderRepository.findById(ORDER_ID))
			.willReturn(Optional.of(TestOrders.order().id(ORDER_ID).userId(99L).pending()));

		// when & then
		assertThatThrownBy(() -> orderAbandonService.abandon(ORDER_ID, OWNER))
			.isInstanceOf(AccessDeniedException.class);
		then(orderExpirationService).should(never()).expireIfStillPending(anyLong());
	}

	@Test
	@DisplayName("없는 주문이면 404 로 거절한다")
	void missingOrderIsRejected() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> orderAbandonService.abandon(ORDER_ID, OWNER))
			.isInstanceOf(EntityNotFoundException.class);
		then(orderExpirationService).should(never()).expireIfStillPending(anyLong());
	}

	/** 소유권 확인 때와 만료 경로를 거친 뒤 다시 읽을 때의 주문 상태. */
	private void givenOrderReadsAs(OrderStatus before, OrderStatus after) {
		ownerSignedIn();
		given(orderRepository.findById(ORDER_ID)).willReturn(
			Optional.of(TestOrders.order().id(ORDER_ID).userId(OWNER_ID).inStatus(before)),
			Optional.of(TestOrders.order().id(ORDER_ID).userId(OWNER_ID).inStatus(after)));
	}
}
