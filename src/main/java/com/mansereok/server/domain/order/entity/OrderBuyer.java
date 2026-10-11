package com.mansereok.server.domain.order.entity;

import com.mansereok.server.domain.user.entity.User;

/**
 * 주문한 사람. 주문에 남기는 사용자 id 와 결제창에 넘기는 이름·이메일을 한 묶음으로 넘겨, 주문을 만들 때 사용자 id 와 상품 id 처럼
 * 같은 타입 값의 순서가 뒤바뀌지 않게 한다.
 *
 * @param userId 주문한 사용자 id
 * @param name   구매자 이름
 * @param email  구매자 이메일
 */
public record OrderBuyer(Long userId, String name, String email) {

	public static OrderBuyer from(User user) {
		return new OrderBuyer(user.getId(), user.getName(), user.getEmail());
	}
}
