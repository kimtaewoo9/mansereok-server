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
	private String impUid; // 포트원 고유 거래 번호 // impUid 로 ..

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
}
