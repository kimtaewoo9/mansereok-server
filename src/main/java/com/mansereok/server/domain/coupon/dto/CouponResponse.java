package com.mansereok.server.domain.coupon.dto;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.discount.entity.DiscountType;
import java.time.LocalDateTime;

/**
 * 내 쿠폰함(GET /api/coupons/my)의 쿠폰 한 장.
 *
 * <p>컴포넌트 이름은 예전에 {@link Coupon} 엔티티를 그대로 직렬화하던 JSON 키와 같다. 사용 여부는 엔티티 필드 isUsed 의 Lombok
 * getter(isUsed()) 때문에 키가 used 였으므로 여기서도 used 로 둔다. 프론트 계약을 유지하기 위한 것이므로 키를 바꾸거나 빼려면
 * 프론트와 함께 조정한다.
 */
public record CouponResponse(
	Long id,
	Long userId,
	String name,
	DiscountType discountType,
	int discountValue,
	int minPurchaseAmount,
	LocalDateTime expiresAt,
	boolean used,
	LocalDateTime usedAt,
	Long templateId
) {

	public static CouponResponse from(Coupon coupon) {
		return new CouponResponse(
			coupon.getId(),
			coupon.getUserId(),
			coupon.getName(),
			coupon.getDiscountType(),
			coupon.getDiscountValue(),
			coupon.getMinPurchaseAmount(),
			coupon.getExpiresAt(),
			coupon.isUsed(),
			coupon.getUsedAt(),
			coupon.getTemplateId()
		);
	}
}
