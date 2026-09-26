package com.mansereok.server.domain.discount.entity;

import com.mansereok.server.global.exception.PaymentException;
import java.util.Objects;

/**
 * 쿠폰과 할인 코드가 함께 쓰는 할인가 계산 규칙.
 *
 * <ol>
 *   <li>주문 금액이 최소 주문 금액보다 적으면 거절한다.</li>
 *   <li>할인 종류({@link DiscountType})가 정한 할인액을 뺀다.</li>
 *   <li>10원 단위 아래는 버린다.</li>
 *   <li>최소 결제 금액(1,000원)보다 낮아지면 1,000원으로 올린다. 다만 원래 금액보다 비싸지지는 않는다. 원래 금액이 1,000원보다
 *   싼 상품은 할인을 써도 원래 금액 그대로다.</li>
 * </ol>
 *
 * <p>100% 정률 할인만 쿠폰과 할인 코드가 다르다. 할인 코드는 0원(무료)이 되고, 쿠폰은 다른 할인처럼 최소 결제 금액을 받는다.
 * 어느 쪽을 따를지는 {@link #FULL_PERCENTAGE_IS_FREE} 와 {@link #FULL_PERCENTAGE_PAYS_MINIMUM} 중에서 고른다.
 */
public final class DiscountPolicy {

	/** 할인을 써도 이 금액 아래로는 내려가지 않는다. 원래 금액이 이보다 싸면 원래 금액이 하한이다. */
	public static final int MIN_PAYABLE_AMOUNT = 1000;

	/** 할인가는 이 단위 아래를 버린다. */
	public static final int ROUNDING_UNIT = 10;

	/** 100% 정률 할인이면 0원(무료)으로 만든다. 할인 코드가 쓴다. */
	public static final DiscountPolicy FULL_PERCENTAGE_IS_FREE = new DiscountPolicy(true);

	/** 100% 정률 할인이어도 다른 할인처럼 최소 결제 금액을 받는다. 쿠폰이 쓴다. */
	public static final DiscountPolicy FULL_PERCENTAGE_PAYS_MINIMUM = new DiscountPolicy(false);

	private final boolean fullPercentageIsFree;

	private DiscountPolicy(boolean fullPercentageIsFree) {
		this.fullPercentageIsFree = fullPercentageIsFree;
	}

	/**
	 * 원래 금액에 할인을 적용한 결제 금액을 돌려준다. 결과는 0 이상이고 원래 금액을 넘지 않는다.
	 *
	 * @throws PaymentException 원래 금액이 최소 주문 금액보다 적을 때
	 */
	public int discountedAmount(int originalAmount, DiscountType discountType, int discountValue,
		int minPurchaseAmount) {
		Objects.requireNonNull(discountType, "할인 종류(discountType)가 비어 있습니다.");
		if (originalAmount < minPurchaseAmount) {
			throw new PaymentException("최소 주문 금액(" + minPurchaseAmount + "원)을 충족하지 못했습니다.");
		}
		if (fullPercentageIsFree && isFullPercentage(discountType, discountValue)) {
			return 0;
		}

		int discounted = originalAmount - discountType.discountAmount(originalAmount, discountValue);
		int rounded = discounted / ROUNDING_UNIT * ROUNDING_UNIT;
		return Math.min(originalAmount, Math.max(MIN_PAYABLE_AMOUNT, rounded));
	}

	private static boolean isFullPercentage(DiscountType discountType, int discountValue) {
		return discountType == DiscountType.PERCENTAGE && discountValue == 100;
	}
}
