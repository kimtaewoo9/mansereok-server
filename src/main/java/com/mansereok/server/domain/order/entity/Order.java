package com.mansereok.server.domain.order.entity;


import com.mansereok.server.global.exception.OrderStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 제약·인덱스 이름을 고정해 엔티티로 만드는 로컬·테스트 DB 와 schema.sql 이 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라
// UNIQUE 와 인덱스를 검사하지도 만들지도 않는다. 이 선언은 로컬·테스트 DB 가 운영과 같은 컬럼의 인덱스를 갖게 한다. 운영은 배포 전
// DDL 로 이 이름대로 만들되, 같은 컬럼·같은 순서의 인덱스가 다른 이름으로 이미 있으면 새로 만들지 않고 그 이름을 그대로 쓴다.
// 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Table(
	name = "orders",
	uniqueConstraints = {
		// 결제 확정·웹훅·환불이 merchant_uid 로 주문 행을 잠근다(OrderRepository.findByMerchantUidWithLock). InnoDB 는 인덱스가 없는
		// 컬럼으로 잠그면 훑은 모든 행과 그 사이 틈을 잠가, 다른 주문의 결제와 새 주문 INSERT 까지 멈춘다.
		@UniqueConstraint(name = "uk_orders_merchant_uid", columnNames = "merchant_uid"),
		// 결제 한 건은 주문 한 건에만 붙는다. 결제 PK 로 주문 찾기(OrderRepository.findByPaymentPkId)도 이 인덱스를 쓴다.
		// MySQL 의 UNIQUE 는 NULL 을 여러 개 허용하므로 아직 결제가 붙지 않은 주문끼리는 부딪히지 않는다.
		@UniqueConstraint(name = "uk_orders_payment_pk_id", columnNames = "payment_pk_id")
	},
	indexes = {
		// 만료 스캔(OrderRepository.findIdsByStatusAndCreatedAtBefore). 같다 조건인 status 를 앞에, 범위 조건인 created_at 을
		// 뒤에 둔다. 보조 인덱스에는 PK 가 함께 담겨 있어 id 만 읽는 이 조회는 인덱스만 읽고 끝난다.
		@Index(name = "idx_orders_status_created_at", columnList = "status, created_at"),
		// 할인 복구가 같은 쿠폰을 쥔 다른 주문이 있는지 본다(OrderRepository.existsByCouponIdAndStatusInAndIdNot).
		@Index(name = "idx_orders_coupon_id", columnList = "coupon_id"),
		// 탈퇴 때 그 사용자의 주문에서만 사용자 연결을 끊는다(OrderRepository.detachUser).
		@Index(name = "idx_orders_user_id", columnList = "user_id")
	}
)
@Entity
@Getter
@Slf4j
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false)
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
	 *
	 * <p>만료 스케줄러는 경합 방지를 위해 {@code OrderRepository.updateStatusIf} 의 조건부 UPDATE 를 쓰므로
	 * 이 메서드를 거치지 않는다. 만료 허용 상태를 바꿀 때는 {@link OrderStatus} 전이 표와 그 UPDATE 조건을 함께 고친다.
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
