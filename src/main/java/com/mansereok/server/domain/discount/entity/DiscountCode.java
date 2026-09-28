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

	private int discountValue; // 정액이면 원, 정률이면 %

	private LocalDateTime expiresAt; // 만료 일시

	private int maxUses; // 최대 사용 횟수(선착순)
	private int currentUses; // 현재 사용 횟수

	private int minPurchaseAmount; // 최소 주문 금액
	private boolean isActive; // 현재 활성화 중인가

	@Column(name = "sub_category_id")
	private Long subCategoryId; // 특정 상품(subCategory) ID. null 이면 모든 상품 가능.

	@Column(name = "category_id")
	private Long categoryId; // 특정 카테고리 ID. null이면 모든 카테고리 가능.

	/**
	 * 지금(now) 쓸 수 있는 코드인지 확인한다.
	 *
	 * @throws PaymentException 비활성이거나 만료 시각이 지났거나 사용 횟수가 최대 횟수에 닿았을 때
	 */
	public void validate(LocalDateTime now) {
		if (!this.isActive) {
			throw new PaymentException("비활성화된 코드입니다.");
		}
		if (this.expiresAt.isBefore(now)) {
			throw new PaymentException("기간이 만료된 코드입니다.");
		}
		if (this.currentUses >= this.maxUses) {
			throw new PaymentException("선착순 마감된 코드입니다.");
		}
	}

	/**
	 * 원래 금액에 이 코드의 할인을 적용한 결제 금액. 계산 규칙은 쿠폰과 같고({@link DiscountPolicy}), 100% 정률 할인만 0원(무료)이
	 * 된다.
	 *
	 * @throws PaymentException 원래 금액이 최소 주문 금액보다 적을 때, 할인을 적용해도 결제 금액이 원래 금액보다 싸지 않을 때
	 */
	public int applyDiscount(int originalAmount) {
		return DiscountPolicy.FULL_PERCENTAGE_IS_FREE.discountedAmount(originalAmount, this.discountType,
			this.discountValue, this.minPurchaseAmount);
	}

	// 주문을 만들 때 사용 횟수를 1 올린다. 호출자가 행을 잠근 채 validate 로 남은 횟수를 확인한 뒤 부른다.
	public void incrementUsage() {
		if (this.currentUses < this.maxUses) {
			this.currentUses++;
		} else {
			// 이 로직이 호출되었다는 것은 validate()와 incrementUsage() 사이에
			// 락이 풀렸다는 뜻이지만, PESSIMISTIC_WRITE 락으로 인해 사실상 발생 불가능
			throw new PaymentException("할인 코드 사용 횟수가 초과되었습니다.");
		}
	}

	/**
	 * 이미 결제가 끝난 주문 몫으로 사용 횟수를 1 올린다. 최대 횟수에 닿았어도 올린다.
	 *
	 * <p>만료 뒤 늦게 결제된 주문이 만료 때 돌려놓은 사용 횟수를 다시 셀 때 부른다. 결제는 이미 이 코드의 할인가로 끝났으므로 거절할
	 * 수 없고, 사용 횟수는 실제로 쓰인 수와 맞아야 한다. 넘었는지는 {@link #exceedsMaxUses()} 로 확인해 운영에 알린다.
	 */
	public void incrementUsageAllowingOverflow() {
		this.currentUses++;
	}

	/** 사용 횟수가 최대 횟수를 넘었으면 true. 최대 횟수와 같으면 넘은 것이 아니다. */
	public boolean exceedsMaxUses() {
		return this.currentUses > this.maxUses;
	}

	public void decreaseUsage() {
		if (this.currentUses > 0) {
			this.currentUses--;
		}
	}
}
