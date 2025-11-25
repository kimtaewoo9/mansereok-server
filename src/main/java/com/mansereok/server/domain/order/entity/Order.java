package com.mansereok.server.domain.order.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Table(name = "orders")
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String merchantUid;
	private String paymentId; // Payment 엔티티의 id

	private Long paymentPkId; // Payment 엔티티의 PK ID 저장용 필드 (Long 타입)

	private Long userId; // 사용자 ID (누가 주문 했는가)
	private Long subCategoryId;  // 구매한 상품 ID

	private String buyerName;
	private String buyerEmail;

	private Integer amount; // 최종 결제 금액 .
	@Enumerated(EnumType.STRING)
	private OrderStatus status; // 주문 상태

	private LocalDateTime createdAt;

	private LocalDateTime paidAt; // 결제한 시간 .

	private Integer originalAmount;
	private String appliedDiscountCode;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public static Order create(String merchantUid, Long userId, Long subCategoryId,
		Integer originalAmount, Integer finalAmount, String appliedDiscountCode,
		OrderStatus status, String buyerName, String buyerEmail) {
		Order order = new Order();
		order.merchantUid = merchantUid;
		order.userId = userId;
		order.subCategoryId = subCategoryId;
		order.originalAmount = originalAmount;
		order.amount = finalAmount;
		order.appliedDiscountCode = appliedDiscountCode;
		order.status = status;

		order.buyerName = buyerName;
		order.buyerEmail = buyerEmail;
		return order;
	}

}
