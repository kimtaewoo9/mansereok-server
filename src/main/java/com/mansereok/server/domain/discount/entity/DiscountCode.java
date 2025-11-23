package com.mansereok.server.domain.discount.entity;

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

@Table(name = "discount_codes")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountCode {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false)
	private String code;

	@Enumerated(EnumType.STRING)
	private DiscountType discountType;

	private int discountValue; // 할인 값 .

	private LocalDateTime expiresAt; // 만료 일시

	private int maxUses; //
	private int currentUses; // 현재 사용 횟수 .

	private int minPurchaseAmount; // 최소 주문 금액.
	private boolean isActive; // 현재 활성화 중인가.

	@Column(name = "sub_category_id")
	private Long subCategoryId; // 특정 상품(subCategory) ID. // null 이면 모든 상품 가능 .

	public void validate() {
		if (!this.isActive) {
			throw new PaymentException("비활성화된 코드입니다.");
		}
		if (this.expiresAt.isBefore(LocalDateTime.now())) {
			throw new PaymentException("기간이 만료된 코드입니다.");
		}
		if (this.currentUses >= this.maxUses) {
			throw new PaymentException("선착순 마감된 코드입니다.");
		}
	}

	public int applyDiscount(int originalAmount) {
		if (originalAmount < this.minPurchaseAmount) {
			throw new PaymentException("최소 주문 금액(" + this.minPurchaseAmount + "원)을 충족하지 못했습니다.");
		} // 최소 주문 금액인 1000원이 넘어야함 . 애초에 설계를 1000원 미만으로 할인 못하게 해야함 .

		int discountedAmount;
		if (this.discountType == DiscountType.FIXED_AMOUNT) {
			discountedAmount = originalAmount - this.discountValue;
		} else if (this.discountType == DiscountType.PERCENTAGE) {
			// 정률 계산 시 소수점 버림 (혹은 반올림 - 정책에 따라)
			int discount = (int) Math.floor(originalAmount * (this.discountValue / 100.0));
			discountedAmount = originalAmount - discount;
		} else {
			discountedAmount = originalAmount;
		}

		// 10원 단위로 가격 내림 .. (1의 자리 제거)
		discountedAmount = (discountedAmount / 10) * 10;

		if (this.discountType == DiscountType.PERCENTAGE && this.discountValue == 100) {
			return Math.max(0, discountedAmount); // 0원 결제를 허용
		}

		return Math.max(1000, discountedAmount); // 천원 미만 방지.
	}

	// 선착순 할인 . 할인 횟수 증가 !
	public void incrementUsage() {
		if (this.currentUses < this.maxUses) {
			this.currentUses++;
		} else {
			// 이 로직이 호출되었다는 것은 validate()와 incrementUsage() 사이에
			// 락이 풀렸다는 뜻이지만, PESSIMISTIC_WRITE 락으로 인해 사실상 발생 불가능
			throw new PaymentException("할인 코드 사용 횟수가 초과되었습니다.");
		}
	}

	public static DiscountCode createReviewReward(String code, int discountAmount,
		LocalDateTime expiresAt) {

		DiscountCode discountCode = new DiscountCode();
		discountCode.code = code;
		discountCode.discountType = DiscountType.FIXED_AMOUNT;
		discountCode.discountValue = discountAmount;
		discountCode.expiresAt = expiresAt;
		discountCode.maxUses = 1;
		discountCode.currentUses = 0;
		discountCode.minPurchaseAmount = 0;
		discountCode.isActive = true;
		discountCode.subCategoryId = null;
		return discountCode;
	}
}
