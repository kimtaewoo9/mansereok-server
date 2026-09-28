package com.mansereok.server.domain.order.entity;

import java.util.Objects;

/**
 * 주문에 적용한 할인. 주문의 appliedDiscountCode 와 couponId 에 들어갈 값을 한 묶음으로 넘긴다. 운영 코드는 정적 팩터리 네 개
 * 가운데 하나로 만든다. record 라 생성자도 열려 있어, 테스트는 코드와 쿠폰 id 를 마음대로 짝지은 할인을 생성자로 만들기도 한다.
 *
 * <ul>
 *   <li>{@link #none()}: 할인 없음. 둘 다 null</li>
 *   <li>{@link #coupon(String, Long)}: 쿠폰. 쿠폰 이름과 쿠폰 id</li>
 *   <li>{@link #code(String)}: 사용자가 입력한 할인 코드</li>
 *   <li>{@link #eventFree()}: 무료 이벤트 발급. 할인 코드 자리에 시스템 표기 {@link #EVENT_FREE_CODE} 를 적는다</li>
 * </ul>
 *
 * @param code     주문에 적을 할인 표기(쿠폰 이름, 할인 코드, 시스템 표기). 할인이 없으면 null
 * @param couponId 쓴 쿠폰의 id. 쿠폰을 쓰지 않았으면 null
 */
public record AppliedDiscount(String code, Long couponId) {

	/**
	 * 무료 이벤트로 발급한 주문의 할인 코드 자리에 적는 시스템 표기. discount_codes 에 없는 값이라 할인 코드처럼 되돌리거나 다시
	 * 쓰면 안 된다({@link Order#hasSystemDiscountCode()}).
	 */
	public static final String EVENT_FREE_CODE = "EVENT_FREE";

	public static AppliedDiscount none() {
		return new AppliedDiscount(null, null);
	}

	/** 인자 순서는 record 구성요소(code, couponId)와 같다. */
	public static AppliedDiscount coupon(String couponName, Long couponId) {
		return new AppliedDiscount(couponName, Objects.requireNonNull(couponId, "couponId"));
	}

	public static AppliedDiscount code(String discountCode) {
		return new AppliedDiscount(discountCode, null);
	}

	public static AppliedDiscount eventFree() {
		return new AppliedDiscount(EVENT_FREE_CODE, null);
	}
}
