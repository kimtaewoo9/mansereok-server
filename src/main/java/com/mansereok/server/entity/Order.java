package com.mansereok.server.entity;


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
	private String merchantUid;  // 주문번호
	private String paymentId; // 포트원 결제 ID (결제 후 저장)
	private Long userId; // 사용자 ID
	private Long subCategoryId;  // 구매한 상품 ID

	private Integer amount; // 최종 결제 금액 .
	@Enumerated(EnumType.STRING)
	private OrderStatus status; // 주문 상태

	private LocalDateTime createdAt;

	private LocalDateTime paidAt; // 결제한 시간 .

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public static Order create(String merchantUid, Long userId, Long subCategoryId,
		Integer amount, OrderStatus status) {
		Order order = new Order();
		order.merchantUid = merchantUid;
		order.userId = userId;
		order.subCategoryId = subCategoryId;
		order.amount = amount;
		order.status = status;
		return order;
	}

}
