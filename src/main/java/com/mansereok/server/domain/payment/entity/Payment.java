package com.mansereok.server.domain.payment.entity;

import com.mansereok.server.global.exception.OrderStateException;
import jakarta.persistence.Column;
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
import org.springframework.data.annotation.CreatedDate;


@Entity
@Table(name = "payments")
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
	private Long userId; // user는 여러 결제 정보를 가질 수 있음 .

	private Long subCategoryId;

	private Long amount; // 검증을 위해 필수
	@Enumerated(EnumType.STRING)
	private PaymentStatus status; // 결제 상태
	@CreatedDate
	private LocalDateTime createdAt;

	public static Payment create(String paymentId, String merchantUid, Long amount,
		PaymentStatus status, Long orderId, Long userId, Long subCategoryId) {
		Payment payment = new Payment();
		payment.impUid = paymentId;
		payment.merchantUid = merchantUid;
		payment.amount = amount;
		payment.status = status;
		payment.createdAt = LocalDateTime.now();
		payment.orderId = orderId;
		payment.userId = userId;
		payment.subCategoryId = subCategoryId;
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
