package com.mansereok.server.domain.coupon.entity;

import com.mansereok.server.domain.discount.entity.DiscountType;
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

@Entity
@Table(name = "coupon_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponTemplate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name; // 쿠폰 이름 .
	private String description; // 예: "전 상품 사용 가능!"

	@Enumerated(EnumType.STRING)
	private DiscountType discountType; // PERCENTAGE, FIXED_AMOUNT
	private int discountValue;
	private int minPurchaseAmount;

	// 발급 가능 기간 (이벤트 기간)
	private LocalDateTime issueStartDate;
	private LocalDateTime issueEndDate;

	// 유효 기간 설정 (둘 중 하나 사용)
	private Integer validDaysAfterIssue; // 발급 후 30일간 유효
	private LocalDateTime validUntil;    // 특정 날짜까지만 유효 (2024-12-31)

	// 선착순 관리 (Null이면 무제한)
	private Integer maxIssueCount;
	private Integer currentIssueCount;

	// 1인당 발급 가능 횟수 (보통 1회)
	private int maxCountPerUser;

	// 생성자 및 비즈니스 로직 (재고 증가 등)
	public void incrementIssueCount() {
		if (currentIssueCount == null) {
			currentIssueCount = 0;
		}
		if (maxIssueCount != null && currentIssueCount >= maxIssueCount) {
			throw new IllegalStateException("선착순 마감되었습니다.");
		}
		this.currentIssueCount++;
	}

}
