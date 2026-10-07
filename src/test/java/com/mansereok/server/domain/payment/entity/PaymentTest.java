package com.mansereok.server.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.TestOrders;
import com.mansereok.server.support.fixture.TestPayments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

	private Payment paymentWith(PaymentStatus status) {
		return TestPayments.payment().paymentId("pay_test_001").merchantUid("order_test_001").amount(10000L)
			.orderId(10L).inStatus(status);
	}

	@Nested
	@DisplayName("paid 는")
	class Paid {

		@ParameterizedTest(name = "[{index}] 주문 상태 {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 주문 상태 | 오류 문구
			PENDING   | 결제 완료(PAID)로 확정한 주문으로만 결제를 만들 수 있습니다. 주문 상태=PENDING, merchantUid=order_test_001
			EXPIRED   | 결제 완료(PAID)로 확정한 주문으로만 결제를 만들 수 있습니다. 주문 상태=EXPIRED, merchantUid=order_test_001
			FAILED    | 결제 완료(PAID)로 확정한 주문으로만 결제를 만들 수 있습니다. 주문 상태=FAILED, merchantUid=order_test_001
			CANCELLED | 결제 완료(PAID)로 확정한 주문으로만 결제를 만들 수 있습니다. 주문 상태=CANCELLED, merchantUid=order_test_001
			""")
		@DisplayName("결제 완료(PAID)로 확정하지 않은 주문으로 결제를 만들려 하면 OrderStateException 으로 거부한다")
		void rejectsOrderNotPaid(OrderStatus orderStatus, String expectedMessage) {
			// given
			Order order = TestOrders.order().id(10L).merchantUid("order_test_001").inStatus(orderStatus);

			// when & then
			assertThatThrownBy(() -> Payment.paid(order, "pay_test_001", 10000L))
				.isInstanceOf(OrderStateException.class)
				.hasMessage(expectedMessage);
		}
	}

	// ===== markCancelled =====

	@Test
	@DisplayName("PAID 결제에 markCancelled 를 부르면 CANCELLED 가 되고 나머지 필드는 유지된다")
	void markCancelled_fromPaid() {
		// given
		Payment payment = paymentWith(PaymentStatus.PAID);

		// when
		payment.markCancelled();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(payment.getImpUid()).isEqualTo("pay_test_001");
		assertThat(payment.getMerchantUid()).isEqualTo("order_test_001");
		assertThat(payment.getAmount()).isEqualTo(10000L);
		assertThat(payment.getOrderId()).isEqualTo(10L);
	}

	@Test
	@DisplayName("CANCEL_REQUESTED 결제에 markCancelled 를 부르면 CANCELLED 가 된다 (환불 확정 단계)")
	void markCancelled_fromCancelRequested() {
		// given
		Payment payment = paymentWith(PaymentStatus.CANCEL_REQUESTED);

		// when
		payment.markCancelled();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
	}

	@ParameterizedTest(name = "{0} 결제에 markCancelled 를 부르면 OrderStateException 이 난다")
	@DisplayName("PAID 도 CANCEL_REQUESTED 도 아닌 결제는 취소할 수 없다")
	@EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "READY",
		"VIRTUAL_ACCOUNT_ISSUED"})
	void markCancelled_fromOtherStatus_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::markCancelled)
			.isInstanceOf(OrderStateException.class)
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}

	// ===== markCancelRequested =====

	@Test
	@DisplayName("PAID 결제에 markCancelRequested 를 부르면 CANCEL_REQUESTED 가 된다")
	void markCancelRequested_fromPaid() {
		// given
		Payment payment = paymentWith(PaymentStatus.PAID);

		// when
		payment.markCancelRequested();

		// then
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
	}

	@ParameterizedTest(name = "{0} 결제에 markCancelRequested 를 부르면 OrderStateException 이 난다")
	@DisplayName("PAID 가 아닌 결제는 취소 요청 상태로 갈 수 없다 (CANCEL_REQUESTED 재진입 포함)")
	@EnumSource(value = PaymentStatus.class, names = {"CANCEL_REQUESTED", "CANCELLED", "FAILED",
		"READY", "VIRTUAL_ACCOUNT_ISSUED"})
	void markCancelRequested_fromNonPaid_throws(PaymentStatus status) {
		// given
		Payment payment = paymentWith(status);

		// when & then
		assertThatThrownBy(payment::markCancelRequested)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining(status.name());

		assertThat(payment.getStatus()).isEqualTo(status);
	}

	@Nested
	@DisplayName("무료 판정(isFree, isFreePayment)은")
	class IsFree {

		@ParameterizedTest(name = "[{index}] 결제 번호 {0}, 금액 {1}원 → 무료 {2}")
		@CsvSource(textBlock = """
			# 결제 번호, 금액, 무료 여부
			free_free_1727000000000_ab12cd34,     0, true
			free_free_1727000000000_ab12cd34,   500, true
			pay_01J000000000000000000000,         0, true
			pay_01J000000000000000000000,       500, false
			""")
		@DisplayName("결제 번호가 free_ 로 시작하거나 금액이 0원이면 무료로 본다")
		void freeWhenFreePrefixOrZeroAmount(String paymentId, long amount, boolean expected) {
			// given
			Payment payment = TestPayments.payment().paymentId(paymentId).amount(amount).paid();

			// when & then
			assertThat(payment.isFree()).isEqualTo(expected);
		}

		@ParameterizedTest(name = "[{index}] 결제 번호 {0}, 금액 {1} → 무료 {2}")
		@CsvSource(nullValues = "null", textBlock = """
			# 결제 번호, 금액, 무료 여부. 포트원 응답은 결제 번호나 금액이 비어 올 수 있다
			free_free_1727000000000_ab12cd34, null, true
			null,                                0, true
			pay_01J000000000000000000000,     null, false
			null,                              500, false
			null,                             null, false
			""")
		@DisplayName("포트원 응답처럼 결제 번호나 금액이 null 이면 그 값으로는 무료로 보지 않고 나머지 값으로 판정한다")
		void freePaymentRuleIgnoresMissingValue(String paymentId, Long amount, boolean expected) {
			// when & then
			assertThat(Payment.isFreePayment(paymentId, amount)).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("isRefundable 은")
	class IsRefundable {

		@ParameterizedTest(name = "[{index}] 결제 {0}, 결과 {1}, 결제 번호 {2}, 금액 {3}원 → 환불 가능 {4}")
		@CsvSource(nullValues = "null", textBlock = """
			# 결제 상태, 결과 상태, 결제 번호, 금액, 환불 가능 여부
			PAID,             INPUT_REQUIRED, pay_test_001,     10000, true
			PAID,             PROCESSING,     pay_test_001,     10000, false
			PAID,             COMPLETED,      pay_test_001,     10000, false
			PAID,             null,           pay_test_001,     10000, false
			CANCEL_REQUESTED, INPUT_REQUIRED, pay_test_001,     10000, false
			CANCELLED,        INPUT_REQUIRED, pay_test_001,     10000, false
			PAID,             INPUT_REQUIRED, free_free_test,     500, false
			PAID,             INPUT_REQUIRED, pay_test_001,         0, false
			""")
		@DisplayName("PAID 이고 정보 입력 전(INPUT_REQUIRED)이며 무료가 아닐 때만 true 다")
		void refundableOnlyWhenPaidBeforeInputAndNotFree(PaymentStatus status, ResultStatus resultStatus,
			String paymentId, long amount, boolean expected) {
			// given
			Payment payment = TestPayments.payment().paymentId(paymentId).amount(amount).inStatus(status);

			// when & then
			assertThat(payment.isRefundable(resultStatus)).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("isOwnedBy 는")
	class IsOwnedBy {

		@ParameterizedTest(name = "[{index}] 결제 소유자 {0}, 요청자 {1} → {2}")
		@CsvSource(nullValues = "null", textBlock = """
			# 결제 소유자 id, 요청자 id, 본인 여부
			   7,    7, true
			   7,    8, false
			null,    7, false
			   7, null, false
			null, null, false
			""")
		@DisplayName("소유자와 요청자가 같은 id 일 때만 true 이고, 어느 쪽이든 null 이면(탈퇴로 연결이 끊긴 결제 포함) false 다")
		void trueOnlyForSameNonNullId(Long ownerId, Long requesterId, boolean expected) {
			// given
			Payment payment = TestPayments.payment().userId(ownerId).paid();

			// when & then
			assertThat(payment.isOwnedBy(requesterId)).isEqualTo(expected);
		}
	}
}
