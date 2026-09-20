package com.mansereok.server.domain.payment.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 주문 식별자(merchantUid) 생성기.
 *
 * <p>형식은 접두사 + epochMillis + "_" + UUID 앞 8자 이며, 접두사로 일반 주문(order_)과
 * 무료 주문(free_)을 구분한다. 세 생성 경로(createOrder, redeemFreeProduct, createFreeOrder)가
 * 같은 규칙을 쓰도록 이 클래스 한 곳에서만 만든다.
 */
@Component
public class MerchantUidGenerator {

	public static final String ORDER_PREFIX = "order_";
	public static final String FREE_PREFIX = "free_";

	public String forOrder() {
		return generate(ORDER_PREFIX);
	}

	public String forFree() {
		return generate(FREE_PREFIX);
	}

	private String generate(String prefix) {
		return prefix + System.currentTimeMillis() + "_"
			+ UUID.randomUUID().toString().substring(0, 8);
	}
}
