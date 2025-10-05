package com.mansereok.server.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Table(name = "orders")
@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.AUTO)
	private Long id;
	private String orderId;
	private Long userId; // 주문한 사용자 .
	private Long itemId;
	private Long amount; // 최종 결제 금액 .
	@Enumerated(EnumType.STRING)
	private PaymentStatus status; // 주문 상태 ex) PENDING, PAID, FAILED

	private LocalDateTime createdAt;

	public static Order create(String orderId, Long userId, Long itemId) {
		Order order = new Order();
		order.orderId = orderId;
		order.userId = userId;
		order.itemId = itemId;
		return order;
	}
}
