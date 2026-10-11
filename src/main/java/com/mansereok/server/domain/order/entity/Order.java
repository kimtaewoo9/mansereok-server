package com.mansereok.server.domain.order.entity;


import com.mansereok.server.global.exception.OrderStateException;
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
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Table(name = "orders")
@Entity
@Getter
@ToString
@Slf4j
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
	 * 주문을 결제 완료 상태로 표시한다.
	 *
	 * <p>PENDING, VIRTUAL_ACCOUNT_ISSUED, EXPIRED 에서만 허용된다. EXPIRED 에서의 전이는 만료 직후 결제가
	 * 완료되는 경합을 위해 허용하되 warn 로그를 남긴다. 이미 PAID 이거나 CANCELLED, FAILED 이면
	 * OrderStateException 을 던진다.
	 *
	 * @param paymentId 결제 식별자(Payment.impUid 와 같은 값)
	 * @param paidAt    결제 시각
	 */
	public void markPaid(String paymentId, LocalDateTime paidAt) {
		if (this.status == OrderStatus.EXPIRED) {
			log.warn("만료된 주문이 결제 완료로 전이됩니다. orderId={}, merchantUid={}, paymentId={}",
				this.id, this.merchantUid, paymentId);
		}
		transitionTo(OrderStatus.PAID);
		this.paymentId = paymentId;
		this.paidAt = paidAt;
	}

	/**
	 * 주문을 결제 실패로 표시한다. PENDING, VIRTUAL_ACCOUNT_ISSUED 에서만 허용된다.
	 */
	public void markFailed() {
		transitionTo(OrderStatus.FAILED);
	}

	/**
	 * 주문을 만료로 표시한다. PENDING, VIRTUAL_ACCOUNT_ISSUED 에서만 허용된다.
	 */
	public void markExpired() {
		transitionTo(OrderStatus.EXPIRED);
	}

	/**
	 * 주문을 취소로 표시한다. PAID 에서만 허용된다.
	 */
	public void markCancelled() {
		transitionTo(OrderStatus.CANCELLED);
	}

	private void transitionTo(OrderStatus next) {
		if (!this.status.canTransitionTo(next)) {
			throw new OrderStateException(
				String.format("주문 상태를 %s 에서 %s 로 바꿀 수 없습니다. orderId=%s, merchantUid=%s",
					this.status, next, this.id, this.merchantUid));
		}
		this.status = next;
	}

	/**
	 * 저장된 Payment 의 PK 를 주문에 연결한다.
	 */
	public void linkPayment(Long paymentPkId) {
		this.paymentPkId = paymentPkId;
	}

}
