package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.payment.entity.Payment;
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

	/**
	 * 무료 주문의 결제 번호(Payment.impUid, Order.paymentId)를 만든다. 포트원 거래가 없어 주문 번호 앞에 무료 결제 번호 접두사
	 * ({@link Payment#FREE_PAYMENT_ID_PREFIX}, free_)를 붙인다. 무료 주문 번호가 이미 free_ 로 시작하므로 실제 값은
	 * free_free_{epochMillis}_{UUID 앞 8자} 이다. 이미 저장된 무료 결제와 같은 형식을 지키려고 접두사를 겹쳐 둔다.
	 */
	public static String freePaymentIdFor(String freeMerchantUid) {
		return Payment.FREE_PAYMENT_ID_PREFIX + freeMerchantUid;
	}

	private String generate(String prefix) {
		return prefix + System.currentTimeMillis() + "_"
			+ UUID.randomUUID().toString().substring(0, 8);
	}
}
