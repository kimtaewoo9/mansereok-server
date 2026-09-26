package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 Order 를 만든다. id 는 DB 가 채우고 결제 시각은 결제 처리 코드가 채우는 값이라, 저장하지 않은 주문에 넣으려면 리플렉션이
 * 필요하다. 그 우회를 이 클래스 한 곳에만 둔다. 필드 이름이 바뀌면 이 클래스만 고친다.
 *
 * <p>기본값은 회원 10 이 상품 3 을 2026-09-10 10:00 에 결제한 PAID 주문(id 100)이다. 회원 id 와 상품 id 를 서로 다르게 두어 두
 * 값이 뒤바뀌면 드러나게 한다. 테스트는 결과에 영향을 주는 값만 바꾼다. 호출할 때마다 새 빌더를 돌려주므로 테스트끼리 값이 섞이지
 * 않는다.
 */
public final class OrderFixture {

	private Long id = 100L;
	private Long userId = 10L;
	private Long subCategoryId = 3L;
	private OrderStatus status = OrderStatus.PAID;
	private LocalDateTime paidAt = LocalDateTime.of(2026, 9, 10, 10, 0);

	private OrderFixture() {
	}

	/** 결제를 마친 주문. */
	public static OrderFixture paidOrder() {
		return new OrderFixture();
	}

	public OrderFixture id(Long id) {
		this.id = id;
		return this;
	}

	/** 주문한 회원 id. null 이면 탈퇴로 회원 연결이 끊긴 주문이다. */
	public OrderFixture userId(Long userId) {
		this.userId = userId;
		return this;
	}

	public OrderFixture subCategoryId(Long subCategoryId) {
		this.subCategoryId = subCategoryId;
		return this;
	}

	public OrderFixture status(OrderStatus status) {
		this.status = status;
		return this;
	}

	/** 결제 시각. null 이면 결제한 적 없는 주문이다. */
	public OrderFixture paidAt(LocalDateTime paidAt) {
		this.paidAt = paidAt;
		return this;
	}

	public Order build() {
		Order order = Order.create("order_test_001", userId, subCategoryId, 10000, 10000, null, null, status,
			"구매자", "buyer@example.com");
		ReflectionTestUtils.setField(order, "id", id);
		ReflectionTestUtils.setField(order, "paidAt", paidAt);
		return order;
	}
}
