package com.mansereok.server.domain.coupon.entity;

import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.global.exception.PaymentException;
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

@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	private String name; // 쿠폰 이름

	@Enumerated(EnumType.STRING)
	private DiscountType discountType; // FIXED_AMOUNT, PERCENTAGE

	private int discountValue;

	private int minPurchaseAmount; // 최소 주문 금액 .. 1000

	private LocalDateTime expiresAt; // 만료 일시

	private boolean isUsed; // 사용 여부
	private LocalDateTime usedAt; // 사용 일시

	@Column(name = "template_id")
	private Long templateId;

	// 생성자 (쿠폰 발급용)
	public static Coupon createFromTemplate(CouponTemplate template, Long userId) {
		Coupon coupon = new Coupon();
		coupon.userId = userId;
		coupon.templateId = template.getId(); // 원본 연결
		coupon.name = template.getName();
		coupon.discountType = template.getDiscountType();
		coupon.discountValue = template.getDiscountValue();
		coupon.minPurchaseAmount = template.getMinPurchaseAmount();

		// 만료일 계산 로직 (D+30일 방식 vs 고정 날짜 방식)
		if (template.getValidDaysAfterIssue() != null) {
			coupon.expiresAt = LocalDateTime.now().plusDays(template.getValidDaysAfterIssue());
		} else {
			coupon.expiresAt = template.getValidUntil();
		}

		coupon.isUsed = false;
		return coupon;
	}

	// 할인 금액 계산 (DiscountCode 로직과 동일하게 구현)
	public int applyDiscount(int originalAmount) {
		if (originalAmount < this.minPurchaseAmount) {
			throw new PaymentException("최소 주문 금액(" + this.minPurchaseAmount + "원)을 충족하지 못했습니다.");
		}

		int discountedAmount;
		if (this.discountType == DiscountType.FIXED_AMOUNT) {
			discountedAmount = originalAmount - this.discountValue;
		} else if (this.discountType == DiscountType.PERCENTAGE) {
			int discount = (int) Math.floor(originalAmount * (this.discountValue / 100.0));
			discountedAmount = originalAmount - discount;
		} else {
			discountedAmount = originalAmount;
		}

		// 10원 단위 절삭
		discountedAmount = (discountedAmount / 10) * 10;

		// 최소 결제 금액 1000원 방지
		return Math.max(1000, discountedAmount);
	}

	// 쿠폰 검증 및 사용 처리
	public void use() {
		if (this.isUsed) {
			throw new PaymentException("이미 사용된 쿠폰입니다.");
		}
		if (this.expiresAt.isBefore(LocalDateTime.now())) {
			throw new PaymentException("기간이 만료된 쿠폰입니다.");
		}
		this.isUsed = true;
		this.usedAt = LocalDateTime.now();
	}

	public void restore() {
		this.isUsed = false;
		this.usedAt = null;
	}
}
