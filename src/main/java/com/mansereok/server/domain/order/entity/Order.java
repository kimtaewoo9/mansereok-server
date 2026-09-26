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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 제약·인덱스 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 UNIQUE 와 인덱스를
// 검사하지도 만들지도 않는다. 이 선언은 엔티티로 만드는 로컬·테스트 DB 가 운영과 같은 인덱스를 갖게 하고, 바꿀 때는 운영 DDL 과
// schema.sql 을 함께 고친다.
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
	/**
	 * 포트원 거래 번호(Payment.impUid 와 같은 값). 결제가 확정될 때 markPaid 가 채운다. 포트원 거래가 없는 무료 주문은 free_ 로
	 * 시작하는 자체 번호다. Payment 엔티티의 PK 는 paymentPkId 에 따로 둔다.
	 */
	private String paymentId;

	private Long paymentPkId; // Payment 엔티티의 PK. 결제가 붙기 전에는 null

	private Long userId; // 사용자 ID (누가 주문 했는가)
	private Long subCategoryId;  // 구매한 상품 ID

	private String buyerName;
	private String buyerEmail;

	// 최종 결제 금액. orders.amount 는 NOT NULL 이라 기본 타입으로 둔다. 결제된 금액과 견줄 때는 amountEquals 를 쓴다.
	private int amount;
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

	/**
	 * 결제를 기다리는 새 주문을 만든다. 상태는 PENDING 으로 고정하고, 다른 상태로는 전이 메서드(markPaid, markExpired 등)로만
	 * 간다. 0원 주문도 PENDING 으로 만든 뒤 PaidOrderFinalizer 가 PAID 로 확정한다.
	 *
	 * @param merchantUid   주문 번호
	 * @param buyer         주문한 사용자와 구매자 이름·이메일
	 * @param subCategoryId 주문한 상품
	 * @param amounts       할인 전 금액과 결제할 금액
	 * @param discount      적용한 쿠폰·할인 코드. 할인이 없으면 {@link AppliedDiscount#none()}
	 */
	public static Order pending(String merchantUid, OrderBuyer buyer, Long subCategoryId,
		OrderAmounts amounts, AppliedDiscount discount) {
		Objects.requireNonNull(buyer, "buyer");
		Objects.requireNonNull(amounts, "amounts");
		Objects.requireNonNull(discount, "discount");

		Order order = new Order();
		order.merchantUid = merchantUid;
		order.userId = buyer.userId();
		order.buyerName = buyer.name();
		order.buyerEmail = buyer.email();
		order.subCategoryId = subCategoryId;
		order.originalAmount = amounts.originalAmount();
		order.amount = amounts.finalAmount();
		order.appliedDiscountCode = discount.code();
		order.couponId = discount.couponId();
		order.status = OrderStatus.PENDING;
		return order;
	}

	/**
	 * userId 의 사용자가 한 주문인지 본다. 탈퇴 처리로 주문의 userId 가 비었으면 누구의 주문도 아니므로, 주문 쪽이든 인자 쪽이든
	 * null 이면 false 다.
	 */
	public boolean isOwnedBy(Long userId) {
		return this.userId != null && this.userId.equals(userId);
	}

	/**
	 * 결제된 금액이 이 주문의 결제 금액과 같은지 본다. 금액 대조는 이 메서드로 한다. 주문 금액(int)과 포트원 금액(Long)을 박싱한 채
	 * equals 로 견주면 타입이 달라 늘 false 가 된다.
	 */
	public boolean amountEquals(long paidAmount) {
		return this.amount == paidAmount;
	}

	/**
	 * 할인 코드 자리에 시스템 표기(무료 이벤트의 {@link AppliedDiscount#EVENT_FREE_CODE})가 들어 있는지 본다. 이 표기는
	 * discount_codes 에 없는 값이라 할인을 되돌리거나 다시 쓸 대상이 아니다.
	 */
	public boolean hasSystemDiscountCode() {
		return AppliedDiscount.EVENT_FREE_CODE.equals(appliedDiscountCode);
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
