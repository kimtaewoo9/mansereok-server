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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 제약 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 UNIQUE 를 검사하지
// 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Entity
@Table(
	name = "coupons",
	uniqueConstraints = {
		// 한 사용자는 한 템플릿의 쿠폰을 한 장만 갖는다. 쿠폰 받기는 템플릿 행을 잠근 채 이미 받았는지 먼저 확인하지만, 그 잠금을
		// 거치지 않고 쿠폰 행이 들어오는 경로가 생겨도 DB 가 마지막으로 막는다. 이미 받았는지 확인하는 조회도 이 인덱스로 한 행만 본다.
		// user_id 를 앞에 두어 user_id 로만 거르는 내 쿠폰함 조회(findAllAvailableByUserId)도 이 인덱스를 쓴다.
		@UniqueConstraint(name = "uk_coupons_user_template", columnNames = {"user_id", "template_id"})
	}
)
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

	/**
	 * 이미 결제가 끝난 주문 몫으로 쿠폰을 사용 처리한다. 만료 뒤 늦게 결제된 주문이 만료 때 돌려놓은 쿠폰을 다시 쓸 때 부른다.
	 *
	 * <p>{@link #use()} 와 달리 쿠폰 기간은 보지 않는다. 주문을 만들 때 기간을 확인했고 결제도 이미 그 쿠폰 할인가로 끝났으므로,
	 * 그사이 기간이 지났다고 사용 처리를 빼면 쿠폰이 미사용으로 남는다.
	 *
	 * @throws IllegalStateException 이미 사용된 쿠폰일 때. 호출자가 먼저 {@link #isUsed()} 로 확인해야 한다.
	 */
	public void useForPaidOrder() {
		if (this.isUsed) {
			throw new IllegalStateException("이미 사용된 쿠폰입니다. couponId=" + this.id);
		}
		this.isUsed = true;
		this.usedAt = LocalDateTime.now();
	}

	public void restore() {
		this.isUsed = false;
		this.usedAt = null;
	}
}
