package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.client.PortOneClient;
import com.mansereok.server.domain.payment.dto.response.PortOnePaymentResponse;
import com.mansereok.server.domain.payment.event.PaymentAnomalyEvent;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.payment.service.ConfirmOutcome.DuplicatePayment;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.PortOneUnavailableException;
import com.mansereok.server.support.fixture.TestOrders;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 중복 결제 취소가 실패했을 때 운영 채널에 알릴지 가리는 규칙을 검증한다.
 *
 * <p>결제 완료 API 와 웹훅은 보통 같은 결제에 대해 함께 오므로, 둘 다 중복 결제로 보고 취소를 부를 수 있다. 뒤에 부른 쪽의 취소는
 * 포트원이 "이미 취소된 결제" 로 거절한다. 그때 "손으로 취소해 주세요" 알림이 나가면 운영자가 이미 끝난 일을 다시 확인하게 되므로,
 * 취소가 실패하면 포트원에서 결제를 다시 조회해 전액 취소돼 있는지 본다. 포트원이 거절한 경우(4xx)에는 알림을 건너뛰고, 응답을 받지
 * 못한 경우(시간 초과·5xx)에는 이 요청의 취소가 반영됐을 수 있어 취소돼 있다는 알림을 한 번 보낸다.
 *
 * <p>포트원과 알림 발행만 목으로 두고, customData 대조는 진짜 PaymentVerifier 로 한다. 취소 단계는 payments 표를 보지 않으므로
 * PaymentRepository 는 생성자를 채우는 데만 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class DuplicatePaymentCancellerTest {

	private static final Long ORDER_ID = 10L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String RECORDED_PAYMENT_ID = "pay_test_001";
	private static final String DUPLICATE_PAYMENT_ID = "pay_test_002";
	private static final String CANCEL_REASON = "같은 주문의 중복 결제 자동 취소";
	private static final String CANCEL_FAILED_ALERT =
		"이미 결제가 끝난 주문에 결제가 한 번 더 승인됐는데 자동 취소에 실패했습니다. 포트원에서 손으로 취소해 주세요.";

	@Mock
	private PortOneClient portOneClient;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private DuplicatePaymentCanceller canceller;

	@BeforeEach
	void setUp() {
		canceller = new DuplicatePaymentCanceller(portOneClient,
			new PaymentVerifier(Jackson2ObjectMapperBuilder.json().build()), paymentRepository, eventPublisher);
	}

	@Test
	@DisplayName("포트원 취소가 성공하면 자동 취소했다는 알림을 한 번 보낸다")
	void cancel_succeeds_alertsOnce() {
		// when
		canceller.cancel(duplicateOfPaidOrder());

		// then
		verify(portOneClient).cancelPayment(DUPLICATE_PAYMENT_ID, CANCEL_REASON);
		PaymentAnomalyEvent alert = capturedAnomalyEvent();
		assertThat(alert.summary()).isEqualTo("이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 자동으로 취소했습니다.");
		assertThat(alert.details())
			.containsEntry("주문 번호", MERCHANT_UID)
			.containsEntry("주문 ID", String.valueOf(ORDER_ID))
			.containsEntry("주문에 기록된 결제 ID", RECORDED_PAYMENT_ID)
			.containsEntry("한 번 더 온 결제 ID", DUPLICATE_PAYMENT_ID);
	}

	@Nested
	@DisplayName("포트원이 취소 요청을 거절하면(PaymentException, 4xx)")
	class WhenPortOneRejectsCancel {

		@BeforeEach
		void givenCancelFails() {
			willThrow(new PaymentException("결제 취소 연동 중 오류가 발생했습니다."))
				.given(portOneClient).cancelPayment(DUPLICATE_PAYMENT_ID, CANCEL_REASON);
		}

		@ParameterizedTest(name = "다시 조회한 상태 {0}")
		@ValueSource(strings = {"CANCELLED", "cancelled"})
		@DisplayName("포트원에서 다시 조회해 전액 취소돼 있으면 다른 요청이 먼저 취소한 것으로 보고 예외도 실패 알림도 없이 끝낸다")
		void skipsAlertWhenAlreadyCancelled(String statusAfterFailure) {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willReturn(
				Optional.of(portOneResponse(statusAfterFailure)));

			// when & then
			assertThatCode(() -> canceller.cancel(duplicateOfPaidOrder())).doesNotThrowAnyException();
			verifyNoInteractions(eventPublisher);
		}

		@ParameterizedTest(name = "다시 조회한 상태 {0}")
		@ValueSource(strings = {"PAID", "PARTIAL_CANCELLED"})
		@DisplayName("다시 조회해도 돈이 남아 있는 상태(승인·부분 취소)면 예외 없이 손으로 취소하라는 알림을 한 번 보낸다")
		void alertsWhenMoneyIsStillCharged(String statusAfterFailure) {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willReturn(
				Optional.of(portOneResponse(statusAfterFailure)));

			// when & then
			assertThatCode(() -> canceller.cancel(duplicateOfPaidOrder())).doesNotThrowAnyException();
			assertThat(capturedAnomalyEvent().summary()).isEqualTo(CANCEL_FAILED_ALERT);
		}

		@Test
		@DisplayName("포트원에 결제가 없다고 나오면 실패 알림에 실패 원인을 담아 한 번 보낸다")
		void alertsWithCauseWhenPaymentNotFound() {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willReturn(Optional.empty());

			// when
			canceller.cancel(duplicateOfPaidOrder());

			// then
			PaymentAnomalyEvent alert = capturedAnomalyEvent();
			assertThat(alert.summary()).isEqualTo(CANCEL_FAILED_ALERT);
			assertThat(alert.details())
				.containsEntry("한 번 더 온 결제 ID", DUPLICATE_PAYMENT_ID)
				.containsEntry("실패 원인", "결제 취소 연동 중 오류가 발생했습니다.");
		}

		@Test
		@DisplayName("다시 조회하는 것마저 실패하면 취소됐는지 알 수 없으므로 예외 없이 실패 알림을 한 번 보낸다")
		void alertsWhenLookupAlsoFails() {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willThrow(
				new PortOneUnavailableException("결제 정보를 조회하는 중 일시적인 오류가 발생했습니다."));

			// when & then
			assertThatCode(() -> canceller.cancel(duplicateOfPaidOrder())).doesNotThrowAnyException();
			assertThat(capturedAnomalyEvent().summary()).isEqualTo(CANCEL_FAILED_ALERT);
		}
	}

	@Nested
	@DisplayName("포트원 취소 응답을 받지 못하면(PortOneUnavailableException, 시간 초과·5xx)")
	class WhenCancelResponseIsLost {

		@BeforeEach
		void givenCancelResponseIsLost() {
			willThrow(new PortOneUnavailableException("결제 취소 연동 중 일시적인 오류가 발생했습니다."))
				.given(portOneClient).cancelPayment(DUPLICATE_PAYMENT_ID, CANCEL_REASON);
		}

		@Test
		@DisplayName("다시 조회해 전액 취소돼 있으면 이 요청의 취소가 반영됐을 수 있어, 예외 없이 취소돼 있다는 알림을 실패 원인과 함께 한 번 보낸다")
		void alertsOnceWhenCancelledAtPortOne() {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willReturn(
				Optional.of(portOneResponse("CANCELLED")));

			// when & then
			assertThatCode(() -> canceller.cancel(duplicateOfPaidOrder())).doesNotThrowAnyException();
			PaymentAnomalyEvent alert = capturedAnomalyEvent();
			assertThat(alert.summary()).isEqualTo("이미 결제가 끝난 주문에 결제가 한 번 더 승인돼 취소를 요청했습니다. "
				+ "취소 응답은 받지 못했지만 포트원에서 취소된 것을 확인했습니다. 따로 할 일은 없습니다.");
			assertThat(alert.details())
				.containsEntry("한 번 더 온 결제 ID", DUPLICATE_PAYMENT_ID)
				.containsEntry("취소 요청 실패 원인", "결제 취소 연동 중 일시적인 오류가 발생했습니다.");
		}

		@Test
		@DisplayName("다시 조회해도 승인 상태면 예외 없이 손으로 취소하라는 알림을 한 번 보낸다")
		void alertsToCancelByHandWhenStillPaid() {
			// given
			given(portOneClient.findPayment(DUPLICATE_PAYMENT_ID)).willReturn(Optional.of(portOneResponse("PAID")));

			// when & then
			assertThatCode(() -> canceller.cancel(duplicateOfPaidOrder())).doesNotThrowAnyException();
			assertThat(capturedAnomalyEvent().summary()).isEqualTo(CANCEL_FAILED_ALERT);
		}
	}

	private DuplicatePayment duplicateOfPaidOrder() {
		Order order = TestOrders.order().id(ORDER_ID).merchantUid(MERCHANT_UID).paymentId(RECORDED_PAYMENT_ID).paid();
		return new DuplicatePayment(order, DUPLICATE_PAYMENT_ID);
	}

	private static PortOnePaymentResponse portOneResponse(String status) {
		PortOnePaymentResponse response = new PortOnePaymentResponse();
		response.setId(DUPLICATE_PAYMENT_ID);
		response.setStatus(status);
		return response;
	}

	/** eventPublisher 에 한 번 발행된 결제 이상 이벤트를 꺼낸다. 두 번 이상 발행됐으면 verify 가 실패한다. */
	private PaymentAnomalyEvent capturedAnomalyEvent() {
		ArgumentCaptor<PaymentAnomalyEvent> captor = ArgumentCaptor.forClass(PaymentAnomalyEvent.class);
		verify(eventPublisher, times(1)).publishEvent(captor.capture());
		return captor.getValue();
	}
}
