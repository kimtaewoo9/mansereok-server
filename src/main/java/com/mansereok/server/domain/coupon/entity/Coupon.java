package com.mansereok.server.domain.coupon.entity;

import com.mansereok.server.domain.discount.entity.DiscountPolicy;
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

	private int minPurchaseAmount; // 최소 주문 금액

	// 만료 일시. 이 시각까지 쓸 수 있다. null 이면 기간 없는 쿠폰이다(템플릿에 유효 기간이 없을 때). 해석은 isExpired 한 곳에서 한다.
	private LocalDateTime expiresAt;

	private boolean isUsed; // 사용 여부
	private LocalDateTime usedAt; // 사용 일시

	@Column(name = "template_id")
	private Long templateId;

	/**
	 * 템플릿으로 사용자 몫의 쿠폰을 만든다. 만료 시각은 받은 시각(issuedAt)을 기준으로 템플릿이 정한다
	 * ({@link CouponTemplate#couponExpiresAt(LocalDateTime)}).
	 */
	public static Coupon createFromTemplate(CouponTemplate template, Long userId, LocalDateTime issuedAt) {
		Coupon coupon = new Coupon();
		coupon.userId = userId;
		coupon.templateId = template.getId(); // 원본 연결
		coupon.name = template.getName();
		coupon.discountType = template.getDiscountType();
		coupon.discountValue = template.getDiscountValue();
		coupon.minPurchaseAmount = template.getMinPurchaseAmount();
		coupon.expiresAt = template.couponExpiresAt(issuedAt);
		coupon.isUsed = false;
		return coupon;
	}

	/**
	 * 원래 금액에 이 쿠폰의 할인을 적용한 결제 금액. 계산 규칙은 할인 코드와 같고({@link DiscountPolicy}), 100% 정률 쿠폰도 다른
	 * 할인처럼 최소 결제 금액(1,000원)을 받는다. 그래서 1,000원 이하 상품에는 어떤 쿠폰도 쓸 수 없다.
	 *
	 * @throws PaymentException 원래 금액이 최소 주문 금액보다 적을 때, 할인을 적용해도 결제 금액이 원래 금액보다 싸지 않을 때
	 */
	public int applyDiscount(int originalAmount) {
		return DiscountPolicy.FULL_PERCENTAGE_PAYS_MINIMUM.discountedAmount(originalAmount, this.discountType,
			this.discountValue, this.minPurchaseAmount);
	}

	/**
	 * 지금(now) 만료된 쿠폰이면 true. 만료 시각 그 순간까지는 쓸 수 있고, 만료 시각이 없는(null) 쿠폰은 만료되지 않는다.
	 *
	 * <p>쿠폰 사용({@link #use(LocalDateTime)}), 결제 전 쿠폰 확인, 내 쿠폰함 조회(CouponRepository.findAllAvailableByUserId)가
	 * 이 판정을 따른다.
	 */
	public boolean isExpired(LocalDateTime now) {
		return this.expiresAt != null && now.isAfter(this.expiresAt);
	}

	/**
	 * 주문을 만들며 쿠폰을 사용 처리한다.
	 *
	 * @throws PaymentException 이미 사용했거나 지금(now) 만료된 쿠폰일 때
	 */
	public void use(LocalDateTime now) {
		if (this.isUsed) {
			throw new PaymentException("이미 사용된 쿠폰입니다.");
		}
		if (isExpired(now)) {
			throw new PaymentException("기간이 만료된 쿠폰입니다.");
		}
		this.isUsed = true;
		this.usedAt = now;
	}

	/**
	 * 이미 결제가 끝난 주문 몫으로 쿠폰을 사용 처리한다. 만료 뒤 늦게 결제된 주문이 만료 때 돌려놓은 쿠폰을 다시 쓸 때 부른다.
	 *
	 * <p>{@link #use(LocalDateTime)} 와 달리 쿠폰 기간은 보지 않는다. 주문을 만들 때 기간을 확인했고 결제도 이미 그 쿠폰 할인가로
	 * 끝났으므로, 그사이 기간이 지났다고 사용 처리를 빼면 쿠폰이 미사용으로 남는다.
	 *
	 * @param now 사용 시각으로 남길 지금 시각
	 * @throws IllegalStateException 이미 사용된 쿠폰일 때. 호출자가 먼저 {@link #isUsed()} 로 확인해야 한다.
	 */
	public void useForPaidOrder(LocalDateTime now) {
		if (this.isUsed) {
			throw new IllegalStateException("이미 사용된 쿠폰입니다. couponId=" + this.id);
		}
		this.isUsed = true;
		this.usedAt = now;
	}

	public void restore() {
		this.isUsed = false;
		this.usedAt = null;
	}
}
