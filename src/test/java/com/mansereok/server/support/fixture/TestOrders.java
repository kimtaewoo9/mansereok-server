package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.order.entity.AppliedDiscount;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderAmounts;
import com.mansereok.server.domain.order.entity.OrderBuyer;
import com.mansereok.server.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 주문을 만든다. 주문은 운영 코드처럼 {@link Order#pending} 으로만 만들고, 다른 상태는 운영 코드와 같은 전이 메서드
 * (markPaid, markExpired, markFailed, markCancelled)를 불러 만든다. 그래서 전이 표가 막는 모양의 주문은 테스트에도 생기지 않는다.
 *
 * <p>VIRTUAL_ACCOUNT_ISSUED 로 가는 전이는 운영 코드에 없어 이 도우미로도 만들 수 없다.
 *
 * <p>테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 사용자 id 와 상품 id 의 기본값을 서로 다르게 두어 두 값이
 * 뒤바뀌면 드러나게 한다. 호출할 때마다 새 도우미를 돌려주므로 테스트끼리 값이 섞이지 않는다.
 */
public final class TestOrders {

	private Long id;
	private String merchantUid = "order_test_001";
	private Long userId = 1L;
	private String buyerName = "구매자";
	private String buyerEmail = "buyer@example.com";
	private Long subCategoryId = 3L;
	private int originalAmount = 10000;
	private int finalAmount = 10000;
	private AppliedDiscount discount = AppliedDiscount.none();
	private String paymentId = "pay_test_001";
	private LocalDateTime paidAt = LocalDateTime.of(2026, 9, 26, 12, 0);

	private TestOrders() {
	}

	public static TestOrders order() {
		return new TestOrders();
	}

	/** 저장된 주문처럼 id 를 채운다. 비워 두면 저장 전 주문이다. */
	public TestOrders id(Long id) {
		this.id = id;
		return this;
	}

	public TestOrders merchantUid(String merchantUid) {
		this.merchantUid = merchantUid;
		return this;
	}

	/** 주문한 사용자 id. null 이면 탈퇴로 사용자 연결이 끊긴 주문이다. */
	public TestOrders userId(Long userId) {
		this.userId = userId;
		return this;
	}

	public TestOrders buyer(String name, String email) {
		this.buyerName = name;
		this.buyerEmail = email;
		return this;
	}

	public TestOrders subCategoryId(Long subCategoryId) {
		this.subCategoryId = subCategoryId;
		return this;
	}

	/** 할인 없이 가격 그대로 결제하는 주문. */
	public TestOrders price(int price) {
		return amounts(price, price);
	}

	public TestOrders amounts(int originalAmount, int finalAmount) {
		this.originalAmount = originalAmount;
		this.finalAmount = finalAmount;
		return this;
	}

	public TestOrders discount(AppliedDiscount discount) {
		this.discount = discount;
		return this;
	}

	/**
	 * PAID·CANCELLED 로 만들 때 markPaid 에 넘길 결제 번호. null 을 주면 결제 번호가 기록되지 않은 예전 결제 완료 주문이 된다.
	 */
	public TestOrders paymentId(String paymentId) {
		this.paymentId = paymentId;
		return this;
	}

	public TestOrders paidAt(LocalDateTime paidAt) {
		this.paidAt = paidAt;
		return this;
	}

	public Order pending() {
		Order order = Order.pending(merchantUid, new OrderBuyer(userId, buyerName, buyerEmail), subCategoryId,
			new OrderAmounts(originalAmount, finalAmount), discount);
		if (id != null) {
			ReflectionTestUtils.setField(order, "id", id);
		}
		return order;
	}

	public Order paid() {
		return inStatus(OrderStatus.PAID);
	}

	/**
	 * PENDING 에서 출발해 전이 메서드만으로 status 에 이른 주문을 만든다. CANCELLED 는 결제 완료를 거친다.
	 *
	 * @throws IllegalArgumentException VIRTUAL_ACCOUNT_ISSUED 처럼 운영 코드의 전이로 갈 수 없는 상태일 때
	 */
	public Order inStatus(OrderStatus status) {
		Order order = pending();
		switch (status) {
			case PENDING -> {
			}
			case PAID -> order.markPaid(paymentId, paidAt);
			case CANCELLED -> {
				order.markPaid(paymentId, paidAt);
				order.markCancelled();
			}
			case EXPIRED -> order.markExpired();
			case FAILED -> order.markFailed();
			case VIRTUAL_ACCOUNT_ISSUED -> throw new IllegalArgumentException(
				"VIRTUAL_ACCOUNT_ISSUED 로 가는 전이는 운영 코드에 없어 만들 수 없습니다.");
		}
		return order;
	}
}
