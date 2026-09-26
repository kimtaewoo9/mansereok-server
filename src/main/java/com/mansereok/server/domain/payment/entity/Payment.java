package com.mansereok.server.domain.payment.entity;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.global.exception.OrderStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


// 인덱스 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 인덱스를 검사하지도
// 만들지도 않는다. 여기 선언한 세 인덱스는 엔티티로 만드는 로컬·테스트 DB 에도 운영과 같은 이름으로 생기고, 바꿀 때는 운영 DDL 과
// schema.sql 을 함께 고친다. schema.sql 의 idx_order_id 는 쓰는 조회가 없어 여기 두지 않았고, imp_uid UNIQUE 는 impUid 의
// @Column(unique = true) 로 선언해 로컬·테스트 DB 에서는 Hibernate 가 지은 이름으로 생긴다.
@Entity
@Table(
	name = "payments",
	indexes = {
		// 대사가 하루치 결제를 created_at 범위로 읽는다(PaymentRepository.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan).
		@Index(name = "idx_payments_created_at", columnList = "created_at"),
		// 대사가 환불 도중 멈춘 CANCEL_REQUESTED 결제를 찾는다(PaymentRepository.findAllByStatus). 상태 종류는 적지만 찾는 상태가
		// 드물어 그 값의 범위만 읽는다.
		@Index(name = "idx_payments_status", columnList = "status"),
		// 내 결제 목록(PaymentRepository.findAllByUserIdOrderByCreatedAtDesc)이 user_id 로 거르고 created_at 순서로 읽어 따로
		// 정렬하지 않는다. 탈퇴의 사용자 연결 끊기(PaymentRepository.detachUser)는 앞 컬럼 user_id 만으로 이 인덱스를 쓴다.
		@Index(name = "idx_payments_user_id_created_at", columnList = "user_id, created_at")
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	/**
	 * 포트원 고유 거래 번호(paymentId). 결제 한 건이 주문 두 건에 붙지 못하도록 UNIQUE 다.
	 * DB(payments.imp_uid)에는 이미 UNIQUE 가 있고, 이 선언은 엔티티가 그 제약을 알게 한다.
	 */
	@Column(unique = true, nullable = false)
	private String impUid;

	private String merchantUid;
	private Long orderId;
	/**
	 * 결제한 사용자. 한 사용자는 결제를 여러 건 가질 수 있다. 탈퇴하면 결제 이력은 남기고 이 값만 NULL 로 바꾸므로
	 * (PaymentRepository.detachUser) NULL 을 허용한다.
	 */
	@Column(nullable = true)
	private Long userId;

	private Long subCategoryId;

	private long amount; // 결제된 금액. payments.amount 는 NOT NULL 이라 기본 타입으로 둔다.
	@Enumerated(EnumType.STRING)
	private PaymentStatus status; // 결제 상태
	// 스프링 데이터의 생성 시각 자동 채움(@CreatedDate)을 켜 두지 않았으므로 paid() 가 직접 넣는다.
	private LocalDateTime createdAt;

	/**
	 * 확정된 주문의 결제를 만든다. 상태는 PAID 로 고정하고, 환불은 전이 메서드(markCancelRequested, markCancelled)로만 간다.
	 * 주문 번호와 주문·사용자·상품 id 는 주문에서 옮겨 적어, 같은 타입 id 의 순서가 뒤바뀔 자리를 두지 않는다.
	 *
	 * @param order     PAID 로 확정한 주문. 저장돼 id 가 있어야 한다.
	 * @param paymentId 포트원 거래 번호. 포트원 거래가 없는 무료 결제는 free_ 로 시작하는 자체 번호
	 * @param amount    결제된 금액
	 */
	public static Payment paid(Order order, String paymentId, long amount) {
		Payment payment = new Payment();
		payment.impUid = paymentId;
		payment.merchantUid = order.getMerchantUid();
		payment.orderId = order.getId();
		payment.userId = order.getUserId();
		payment.subCategoryId = order.getSubCategoryId();
		payment.amount = amount;
		payment.status = PaymentStatus.PAID;
		payment.createdAt = LocalDateTime.now();
		return payment;
	}

	/**
	 * 환불 진행 중임을 기록한다. 포트원 취소 API 를 부르기 전에 커밋해 두는 흔적이다. PAID 에서만 허용된다.
	 */
	public void markCancelRequested() {
		if (this.status != PaymentStatus.PAID) {
			throw new OrderStateException(
				String.format("결제 상태가 PAID 가 아니라 취소 요청할 수 없습니다. 현재 상태=%s, impUid=%s",
					this.status, this.impUid));
		}
		this.status = PaymentStatus.CANCEL_REQUESTED;
	}

	/**
	 * 결제를 취소 상태로 표시한다. CANCEL_REQUESTED(정상 환불 흐름) 또는 PAID 에서만 허용되고, 아니면
	 * OrderStateException 을 던진다.
	 */
	public void markCancelled() {
		if (this.status != PaymentStatus.PAID && this.status != PaymentStatus.CANCEL_REQUESTED) {
			throw new OrderStateException(
				String.format("결제 상태가 PAID 또는 CANCEL_REQUESTED 가 아니라 취소할 수 없습니다. 현재 상태=%s, impUid=%s",
					this.status, this.impUid));
		}
		this.status = PaymentStatus.CANCELLED;
	}

	/**
	 * 포트원 취소가 실패했을 때 취소 요청을 되돌린다. CANCEL_REQUESTED 에서만 PAID 로 돌아간다.
	 */
	public void revertCancelRequest() {
		if (this.status != PaymentStatus.CANCEL_REQUESTED) {
			throw new OrderStateException(
				String.format("결제 상태가 CANCEL_REQUESTED 가 아니라 취소 요청을 되돌릴 수 없습니다. 현재 상태=%s, impUid=%s",
					this.status, this.impUid));
		}
		this.status = PaymentStatus.PAID;
	}
}
