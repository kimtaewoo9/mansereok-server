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

	private Long couponId;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public static Order create(String merchantUid, Long userId, Long subCategoryId,
		Integer originalAmount, Integer finalAmount, String appliedDiscountCode,
		Long couponId, OrderStatus status, String buyerName, String buyerEmail) {
		Order order = new Order();
		order.merchantUid = merchantUid;
		order.userId = userId;
		order.subCategoryId = subCategoryId;
		order.originalAmount = originalAmount;
		order.amount = finalAmount;
		order.appliedDiscountCode = appliedDiscountCode;

		order.couponId = couponId;

		order.status = status;

		order.buyerName = buyerName;
		order.buyerEmail = buyerEmail;
		return order;
	}

	/**
	 * 주문을 결제 완료 상태로 표시한다. 상태 전이 가드는 두지 않는다(호출자가 검증한다).
	 *
	 * @param paymentId 결제 식별자(Payment.impUid 와 같은 값)
	 * @param paidAt    결제 시각
	 */
	public void markPaid(String paymentId, LocalDateTime paidAt) {
		this.status = OrderStatus.PAID;
		this.paymentId = paymentId;
		this.paidAt = paidAt;
	}

	/**
	 * 저장된 Payment 의 PK 를 주문에 연결한다.
	 */
	public void linkPayment(Long paymentPkId) {
		this.paymentPkId = paymentPkId;
	}

}
