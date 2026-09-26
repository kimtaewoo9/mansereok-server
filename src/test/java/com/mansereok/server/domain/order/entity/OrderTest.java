package com.mansereok.server.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.exception.OrderStateException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.TestOrders;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTest {

	private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 9, 21, 12, 30);

	private static final LocalDateTime FIRST_PAID_AT = LocalDateTime.of(2026, 9, 21, 12, 0);

	/** 전이만으로 status 에 이른 주문. PAID·CANCELLED 는 결제 ID pay_first 로 FIRST_PAID_AT 에 결제된 주문이다. */
	private Order orderWith(OrderStatus status) {
		return TestOrders.order().amounts(10000, 9000).discount(AppliedDiscount.code("SALE10"))
			.paymentId("pay_first").paidAt(FIRST_PAID_AT).inStatus(status);
	}

	private Order pendingOrder() {
		return orderWith(OrderStatus.PENDING);
	}

	// ===== markPaid =====

	@Test
	@DisplayName("PENDING 주문에 markPaid 를 부르면 PAID 가 되고 paymentId 와 paidAt 이 채워진다")
	void markPaid_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markPaid("pay_test_001", PAID_AT);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);

		// 다른 필드는 건드리지 않는다
		assertThat(order.getPaymentPkId()).isNull();
		assertThat(order.getAmount()).isEqualTo(9000);
		assertThat(order.getAppliedDiscountCode()).isEqualTo("SALE10");
	}

	@Test
	@DisplayName("이미 PAID 인 주문에 markPaid 를 부르면 OrderStateException 이 나고 값이 바뀌지 않는다")
	void markPaid_onPaidOrder_throws() {
		// given
		Order order = orderWith(OrderStatus.PAID);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_second", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.isInstanceOf(PaymentException.class)
			.hasMessageContaining("PAID 에서 PAID 로");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_first");
		assertThat(order.getPaidAt()).isEqualTo(FIRST_PAID_AT);
	}

	@Test
	@DisplayName("EXPIRED 주문에 markPaid 를 부르면 만료 직후 결제 경합으로 보고 PAID 를 허용한다")
	void markPaid_fromExpired_allowed() {
		// given
		Order order = orderWith(OrderStatus.EXPIRED);

		// when
		order.markPaid("pay_late", PAID_AT);

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_late");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	@DisplayName("CANCELLED 주문에 markPaid 를 부르면 OrderStateException 이 난다")
	void markPaid_fromCancelled_throws() {
		// given
		Order order = orderWith(OrderStatus.CANCELLED);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_again", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED")
			.hasMessageContaining("PAID");

		// 환불 전 첫 결제의 기록이 그대로 남는다
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isEqualTo("pay_first");
		assertThat(order.getPaidAt()).isEqualTo(FIRST_PAID_AT);
	}

	@Test
	@DisplayName("FAILED 주문에 markPaid 를 부르면 OrderStateException 이 난다")
	void markPaid_fromFailed_throws() {
		// given
		Order order = orderWith(OrderStatus.FAILED);

		// when & then
		assertThatThrownBy(() -> order.markPaid("pay_again", PAID_AT))
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("FAILED")
			.hasMessageContaining("PAID");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
	}

	// ===== markCancelled =====

	@Test
	@DisplayName("PAID 주문에 markCancelled 를 부르면 CANCELLED 가 되고 결제 정보는 남는다")
	void markCancelled_fromPaid() {
		// given
		Order order = pendingOrder();
		order.markPaid("pay_test_001", PAID_AT);

		// when
		order.markCancelled();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	@Test
	@DisplayName("PENDING 주문에 markCancelled 를 부르면 OrderStateException 이 난다")
	void markCancelled_fromPending_throws() {
		// given
		Order order = pendingOrder();

		// when & then
		assertThatThrownBy(order::markCancelled)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("PENDING")
			.hasMessageContaining("CANCELLED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
	}

	// ===== markExpired =====

	@Test
	@DisplayName("PENDING 주문에 markExpired 를 부르면 EXPIRED 가 된다")
	void markExpired_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markExpired();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
		assertThat(order.getPaymentId()).isNull();
		assertThat(order.getPaidAt()).isNull();
	}

	@Test
	@DisplayName("PAID 주문에 markExpired 를 부르면 OrderStateException 이 나고 PAID 가 유지된다")
	void markExpired_fromPaid_throws() {
		// given
		Order order = pendingOrder();
		order.markPaid("pay_test_001", PAID_AT);

		// when & then
		assertThatThrownBy(order::markExpired)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("PAID")
			.hasMessageContaining("EXPIRED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(order.getPaymentId()).isEqualTo("pay_test_001");
		assertThat(order.getPaidAt()).isEqualTo(PAID_AT);
	}

	// ===== markFailed =====

	@Test
	@DisplayName("PENDING 주문에 markFailed 를 부르면 FAILED 가 된다")
	void markFailed_fromPending() {
		// given
		Order order = pendingOrder();

		// when
		order.markFailed();

		// then
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
	}

	@Test
	@DisplayName("CANCELLED 주문에 markFailed 를 부르면 OrderStateException 이 난다")
	void markFailed_fromCancelled_throws() {
		// given
		Order order = orderWith(OrderStatus.CANCELLED);

		// when & then
		assertThatThrownBy(order::markFailed)
			.isInstanceOf(OrderStateException.class)
			.hasMessageContaining("CANCELLED")
			.hasMessageContaining("FAILED");

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	// ===== linkPayment =====

	@Test
	@DisplayName("linkPayment 는 paymentPkId 만 채우고 상태와 paymentId 는 바꾸지 않는다")
	void linkPayment_setsOnlyPaymentPkId() {
		// given
		Order order = pendingOrder();

		// when
		order.linkPayment(100L);

		// then
		assertThat(order.getPaymentPkId()).isEqualTo(100L);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getPaymentId()).isNull();
		assertThat(order.getPaidAt()).isNull();
	}

	@Nested
	@DisplayName("pending 으로 만들면")
	class Pending {

		@Test
		@DisplayName("상태는 PENDING 이고 결제 번호·결제 시각·결제 PK 는 비어 있으며, 묶음으로 받은 값이 제자리에 들어간다")
		void startsPendingWithGivenValues() {
			// when
			Order order = Order.pending("order_pending_001", new OrderBuyer(7L, "김태우", "taewoo@example.com"), 3L,
				new OrderAmounts(10000, 9000), AppliedDiscount.coupon(100L, "가입 쿠폰"));

			// then
			assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
			assertThat(order.getPaymentId()).isNull();
			assertThat(order.getPaidAt()).isNull();
			assertThat(order.getPaymentPkId()).isNull();
			assertThat(order.getMerchantUid()).isEqualTo("order_pending_001");
			assertThat(order.getUserId()).as("사용자 id").isEqualTo(7L);
			assertThat(order.getSubCategoryId()).as("상품 id").isEqualTo(3L);
			assertThat(order.getBuyerName()).isEqualTo("김태우");
			assertThat(order.getBuyerEmail()).isEqualTo("taewoo@example.com");
			assertThat(order.getOriginalAmount()).as("할인 전 금액").isEqualTo(10000);
			assertThat(order.getAmount()).as("결제할 금액").isEqualTo(9000);
			assertThat(order.getAppliedDiscountCode()).isEqualTo("가입 쿠폰");
			assertThat(order.getCouponId()).isEqualTo(100L);
		}
	}

	@Nested
	@DisplayName("테스트 도우미 TestOrders 는")
	class TestOrdersReachesStates {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(value = OrderStatus.class, mode = Mode.EXCLUDE, names = "VIRTUAL_ACCOUNT_ISSUED")
		@DisplayName("운영 코드의 전이만으로 각 상태의 주문을 만든다")
		void reachesStatusThroughTransitions(OrderStatus status) {
			// when
			Order order = TestOrders.order().inStatus(status);

			// then
			assertThat(order.getStatus()).isEqualTo(status);
		}

		@Test
		@DisplayName("운영 코드에 가는 전이가 없는 VIRTUAL_ACCOUNT_ISSUED 는 만들지 않는다")
		void refusesStatusWithoutTransition() {
			assertThatThrownBy(() -> TestOrders.order().inStatus(OrderStatus.VIRTUAL_ACCOUNT_ISSUED))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("VIRTUAL_ACCOUNT_ISSUED");
		}
	}

	@Nested
	@DisplayName("주문 금액 묶음(OrderAmounts)은")
	class Amounts {

		@ParameterizedTest(name = "[{index}] 할인 전 {0}원, 결제할 금액 {1}원")
		@CsvSource(textBlock = """
			# 할인 전 금액, 결제할 금액
			10000, 10000
			10000,  9999
			10000,     0
			    0,     0
			""")
		@DisplayName("결제할 금액이 0원 이상이고 할인 전 금액 이하이면 받는다")
		void acceptsFinalAmountBetweenZeroAndOriginal(int originalAmount, int finalAmount) {
			// when
			OrderAmounts amounts = new OrderAmounts(originalAmount, finalAmount);

			// then
			assertThat(amounts.finalAmount()).isEqualTo(finalAmount);
		}

		@ParameterizedTest(name = "[{index}] 할인 전 {0}원, 결제할 금액 {1}원")
		@CsvSource(textBlock = """
			# 할인 전 금액, 결제할 금액
			10000, 10001
			10000,    -1
			    0,  1000
			""")
		@DisplayName("결제할 금액이 음수이거나 할인 전 금액보다 크면 IllegalArgumentException 으로 거부한다")
		void rejectsFinalAmountOutsideRange(int originalAmount, int finalAmount) {
			assertThatThrownBy(() -> new OrderAmounts(originalAmount, finalAmount))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("결제할 금액은 0원 이상이고 할인 전 금액을 넘을 수 없습니다. 할인 전=" + originalAmount
					+ ", 결제할 금액=" + finalAmount);
		}
	}

	@Nested
	@DisplayName("hasSystemDiscountCode 는")
	class SystemDiscountCode {

		static Stream<Arguments> discounts() {
			return Stream.of(
				Arguments.of("무료 이벤트", AppliedDiscount.eventFree(), true),
				Arguments.of("할인 코드", AppliedDiscount.code("SALE10"), false),
				Arguments.of("쿠폰", AppliedDiscount.coupon(100L, "가입 쿠폰"), false),
				Arguments.of("할인 없음", AppliedDiscount.none(), false));
		}

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("discounts")
		@DisplayName("무료 이벤트 표기일 때만 true 다")
		void trueOnlyForEventFreeMarker(String name, AppliedDiscount discount, boolean expected) {
			// given
			Order order = TestOrders.order().discount(discount).pending();

			// when & then
			assertThat(order.hasSystemDiscountCode()).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("isOwnedBy 는")
	class IsOwnedBy {

		@ParameterizedTest(name = "[{index}] 주문 소유자 {0}, 요청자 {1} → {2}")
		@CsvSource(nullValues = "null", textBlock = """
			# 주문 소유자 id, 요청자 id, 본인 여부
			   7,    7, true
			   7,    8, false
			null,    7, false
			   7, null, false
			null, null, false
			""")
		@DisplayName("소유자와 요청자가 같은 id 일 때만 true 이고, 어느 쪽이든 null 이면(탈퇴로 연결이 끊긴 주문 포함) false 다")
		void trueOnlyForSameNonNullId(Long ownerId, Long requesterId, boolean expected) {
			// given
			Order order = TestOrders.order().userId(ownerId).pending();

			// when & then
			assertThat(order.isOwnedBy(requesterId)).isEqualTo(expected);
		}
	}
}
