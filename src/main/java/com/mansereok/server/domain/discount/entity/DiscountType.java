package com.mansereok.server.domain.discount.entity;

/**
 * 할인 종류. 종류마다 원래 금액에서 얼마를 빼는지를 스스로 정한다.
 *
 * <p>할인액 계산을 상수마다 두어, 새 종류를 더하면서 계산을 빠뜨리면 컴파일이 되지 않는다. 할인액을 뺀 뒤의 절삭·최소 결제 금액과,
 * 할인으로 싸지지 않을 때의 거절은 {@link DiscountPolicy} 가 정한다.
 */
public enum DiscountType {

	/** 정액 할인. 할인 값(원)을 그대로 뺀다. */
	FIXED_AMOUNT {
		@Override
		public int discountAmount(int originalAmount, int discountValue) {
			return discountValue;
		}
	},

	/** 정률 할인. 원래 금액의 할인 값(%)만큼 빼고, 1원 아래는 버린다. */
	PERCENTAGE {
		@Override
		public int discountAmount(int originalAmount, int discountValue) {
			// 정수로 곱하고 나눠 버린다. 실수로 계산하면 29% 같은 값에서 할인액이 1원 모자라게 나온다(0.29 * 100 = 28.99...).
			return (int) ((long) originalAmount * discountValue / 100);
		}
	};

	/**
	 * 원래 금액에서 뺄 할인액을 돌려준다. 원래 금액보다 클 수 있다(정액 할인이 상품 가격보다 큰 경우). 그 경우의 처리는
	 * {@link DiscountPolicy} 가 한다.
	 */
	public abstract int discountAmount(int originalAmount, int discountValue);
}
