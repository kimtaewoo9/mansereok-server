package com.mansereok.server.domain.order.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {

	PENDING("결제 대기"),
	VIRTUAL_ACCOUNT_ISSUED("가상계좌 발급됨"),
	PAID("결제 완료"),
	CANCELLED("결제 취소"),
	FAILED("결제 실패"),
	EXPIRED("주문 만료");

	private final String description;

	/**
	 * 허용 전이 표. 여기 없는 조합은 모두 불허다.
	 *
	 * <ul>
	 *   <li>PENDING, VIRTUAL_ACCOUNT_ISSUED → PAID, FAILED, EXPIRED</li>
	 *   <li>EXPIRED → PAID (만료 직후 결제가 완료되는 경합과 늦은 입금을 위해 허용, 전이 시 warn 로그). 만료 때 쿠폰·할인 코드를
	 *       되돌렸으므로, 결제 확정은 이 전이 전에 같은 트랜잭션에서 그 할인을 다시 잡는다(OrderDiscountRestorer.reclaim).
	 *       그사이 다른 주문이 쿠폰을 썼거나 할인 코드가 최대 횟수에 닿았으면 전이하지 않고 결제를 취소한다.</li>
	 *   <li>PAID → CANCELLED</li>
	 *   <li>CANCELLED, FAILED 는 종단 상태</li>
	 * </ul>
	 *
	 * <p>enum 상수 초기화 순서 때문에 enum 필드에 직접 EnumSet 을 두지 못하므로 static Map 으로 든다.
	 */
	private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
		PENDING, EnumSet.of(PAID, FAILED, EXPIRED),
		VIRTUAL_ACCOUNT_ISSUED, EnumSet.of(PAID, FAILED, EXPIRED),
		PAID, EnumSet.of(CANCELLED),
		EXPIRED, EnumSet.of(PAID),
		CANCELLED, EnumSet.noneOf(OrderStatus.class),
		FAILED, EnumSet.noneOf(OrderStatus.class)
	);

	/**
	 * 현재 상태에서 next 로의 전이가 허용되는지 돌려준다. 같은 상태로의 전이(PAID→PAID 등)는 불허다.
	 * 표에 없는 상태(새 상수가 추가됐는데 표에 빠진 경우)는 모두 불허로 본다.
	 */
	public boolean canTransitionTo(OrderStatus next) {
		return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(next);
	}
}
