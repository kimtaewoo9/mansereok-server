package com.mansereok.server.entity;

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
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;


@Entity
@Table(name = "payments")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false)
	private String impUid;

	@Column(nullable = false)
	private String merchantUid; // 우리 시스템의 주문 ID

	@Column(nullable = false)
	private Long amount; // 검증을 위해 필수

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status; // 결제 상태

	@CreatedDate
	@Column(updatable = false)
	private LocalDateTime createdAt;

	public static Payment create(String paymentId, String orderId, Long amount,
		PaymentStatus status) {
		Payment payment = new Payment();
		payment.impUid = paymentId;
		payment.merchantUid = orderId;
		payment.amount = amount;
		payment.status = status;
		payment.createdAt = LocalDateTime.now();
		return payment;
	}
}
