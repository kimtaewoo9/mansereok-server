package com.mansereok.server.domain.order.entity;

/**
 * 주문 금액. 할인 전 금액과 실제로 결제할 금액을 한 묶음으로 넘겨 두 값의 순서가 뒤바뀌지 않게 한다.
 *
 * <p>결제할 금액은 0원 이상이고 할인 전 금액을 넘지 않는다. 이 조건을 어기는 금액으로는 주문을 만들지 않는다. 할인 계산이 틀려
 * 정가보다 비싼 결제창이 뜨거나 음수 금액이 저장되는 것을 막는다.
 *
 * @param originalAmount 할인 전 금액(상품 가격)
 * @param finalAmount    할인을 적용해 실제로 결제할 금액
 */
public record OrderAmounts(int originalAmount, int finalAmount) {

	public OrderAmounts {
		if (finalAmount < 0 || finalAmount > originalAmount) {
			throw new IllegalArgumentException(String.format(
				"결제할 금액은 0원 이상이고 할인 전 금액을 넘을 수 없습니다. 할인 전=%d, 결제할 금액=%d",
				originalAmount, finalAmount));
		}
	}
}
