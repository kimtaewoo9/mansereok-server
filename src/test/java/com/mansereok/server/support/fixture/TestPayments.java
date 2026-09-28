package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 결제를 만든다. 결제는 운영 코드처럼 PAID 로 확정한 주문을 {@link Payment#paid} 에 넘겨서만 만들고,
 * CANCEL_REQUESTED·CANCELLED 는 운영 코드와 같은 전이 메서드로 만든다.
 *
 * <p>READY·FAILED·VIRTUAL_ACCOUNT_ISSUED 는 운영 코드에 그 상태로 결제를 만들거나 바꾸는 길이 없다. 그런 결제를 막는 검사를
 * 확인하는 테스트를 위해 이 세 상태만 필드를 직접 바꾼다. 그 우회는 이 클래스 한 곳에만 둔다.
 *
 * <p>결제의 주문 번호와 주문·사용자·상품 id 는 {@link TestOrders} 로 만든 주문에서 옮겨 온다. 기본 주문은 id 10, 사용자 1, 상품 3
 * 이다.
 */
public final class TestPayments {

	private Long id;
	private String paymentId = "pay_test_001";
	private long amount = 10000L;
	private final TestOrders order = TestOrders.order().id(10L);

	private TestPayments() {
	}

	public static TestPayments payment() {
		return new TestPayments();
	}

	/** 저장된 결제처럼 id 를 채운다. */
	public TestPayments id(Long id) {
		this.id = id;
		return this;
	}

	public TestPayments paymentId(String paymentId) {
		this.paymentId = paymentId;
		return this;
	}

	public TestPayments amount(long amount) {
		this.amount = amount;
		return this;
	}

	public TestPayments merchantUid(String merchantUid) {
		order.merchantUid(merchantUid);
		return this;
	}

	public TestPayments orderId(Long orderId) {
		order.id(orderId);
		return this;
	}

	/** 결제한 사용자 id. null 이면 탈퇴로 사용자 연결이 끊긴 결제다. */
	public TestPayments userId(Long userId) {
		order.userId(userId);
		return this;
	}

	public TestPayments subCategoryId(Long subCategoryId) {
		order.subCategoryId(subCategoryId);
		return this;
	}

	/** 운영 코드처럼 같은 결제 번호로 PAID 확정한 주문에서 결제를 만든다. */
	public Payment paid() {
		Order paidOrder = order.paymentId(paymentId).paid();
		Payment payment = Payment.paid(paidOrder, paymentId, amount);
		if (id != null) {
			ReflectionTestUtils.setField(payment, "id", id);
		}
		return payment;
	}

	/** PAID 에서 출발해 status 에 이른 결제를 만든다. 운영 코드의 전이로 갈 수 없는 상태는 필드를 직접 바꾼다(클래스 설명). */
	public Payment inStatus(PaymentStatus status) {
		Payment payment = paid();
		switch (status) {
			case PAID -> {
			}
			case CANCEL_REQUESTED -> payment.markCancelRequested();
			case CANCELLED -> {
				payment.markCancelRequested();
				payment.markCancelled();
			}
			case READY, FAILED, VIRTUAL_ACCOUNT_ISSUED -> ReflectionTestUtils.setField(payment, "status", status);
		}
		return payment;
	}
}
