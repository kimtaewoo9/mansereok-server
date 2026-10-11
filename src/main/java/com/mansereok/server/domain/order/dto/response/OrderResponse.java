package com.mansereok.server.domain.order.dto.response;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;

/**
 * 주문 응답 DTO.
 *
 * <p>컴포넌트 이름은 기존에 {@link Order} 엔티티를 그대로 직렬화하던 JSON 키와 같다. 프론트 계약을
 * 유지하기 위한 것이므로 키를 바꾸거나 빼려면 프론트와 함께 조정한다.
 */
public record OrderResponse(
	Long id,
	String merchantUid,
	String paymentId,
	Long paymentPkId,
	Long userId,
	Long subCategoryId,
	String buyerName,
	String buyerEmail,
	Integer amount,
	OrderStatus status,
	LocalDateTime createdAt,
	LocalDateTime paidAt,
	Integer originalAmount,
	String appliedDiscountCode,
	Long couponId
) {

	public static OrderResponse from(Order order) {
		return new OrderResponse(
			order.getId(),
			order.getMerchantUid(),
			order.getPaymentId(),
			order.getPaymentPkId(),
			order.getUserId(),
			order.getSubCategoryId(),
			order.getBuyerName(),
			order.getBuyerEmail(),
			order.getAmount(),
			order.getStatus(),
			order.getCreatedAt(),
			order.getPaidAt(),
			order.getOriginalAmount(),
			order.getAppliedDiscountCode(),
			order.getCouponId()
		);
	}
}
