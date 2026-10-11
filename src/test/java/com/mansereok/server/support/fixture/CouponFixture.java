package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.discount.entity.DiscountType;
import java.time.LocalDateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 Coupon 을 만든다. Coupon 은 생성자가 protected 이고 템플릿 없이 만드는 방법이 없어 리플렉션이 필요한데, 그 우회를 이
 * 클래스 한 곳에만 둔다.
 *
 * <p>기본값은 "지금 바로 쓸 수 있는 정액 1,000원 쿠폰" 이다. 사용자 1번 소유, 미사용, 만료는 지금부터 30일 뒤, 최소 주문 금액
 * 0원, 템플릿 없음(templateId null). 테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를
 * 돌려주므로 테스트끼리 값이 섞이지 않는다.
 */
public final class CouponFixture {

	private Long id = 1L;
	private Long userId = 1L;
	private String name = "테스트 쿠폰";
	private DiscountType discountType = DiscountType.FIXED_AMOUNT;
	private int discountValue = 1000;
	private int minPurchaseAmount = 0;
	private LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
	private boolean used = false;
	private LocalDateTime usedAt = null;
	private Long templateId = null;

	private CouponFixture() {
	}

	/** 지금 바로 쓸 수 있는 정액 1,000원 쿠폰. */
	public static CouponFixture usableCoupon() {
		return new CouponFixture();
	}

	/** 주문 금액에서 amount 원을 빼는 정액 할인 쿠폰. */
	public static CouponFixture fixedAmount(int amount) {
		return new CouponFixture().discountType(DiscountType.FIXED_AMOUNT).discountValue(amount);
	}

	public CouponFixture id(Long id) {
		this.id = id;
		return this;
	}

	/** DB 에 저장할 때는 id 를 비워 둔다(IDENTITY 가 채운다). */
	public CouponFixture withoutId() {
		this.id = null;
		return this;
	}

	public CouponFixture userId(Long userId) {
		this.userId = userId;
		return this;
	}

	public CouponFixture name(String name) {
		this.name = name;
		return this;
	}

	public CouponFixture discountType(DiscountType discountType) {
		this.discountType = discountType;
		return this;
	}

	public CouponFixture discountValue(int discountValue) {
		this.discountValue = discountValue;
		return this;
	}

	public CouponFixture minPurchaseAmount(int minPurchaseAmount) {
		this.minPurchaseAmount = minPurchaseAmount;
		return this;
	}

	public CouponFixture expiresAt(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	/** 이미 usedAt 에 사용한 쿠폰으로 만든다. */
	public CouponFixture usedAt(LocalDateTime usedAt) {
		this.used = true;
		this.usedAt = usedAt;
		return this;
	}

	/** templateId 템플릿(쿠폰 이벤트)에서 받은 쿠폰으로 만든다. */
	public CouponFixture templateId(Long templateId) {
		this.templateId = templateId;
		return this;
	}

	public Coupon build() {
		Coupon coupon = BeanUtils.instantiateClass(Coupon.class);
		ReflectionTestUtils.setField(coupon, "id", id);
		ReflectionTestUtils.setField(coupon, "userId", userId);
		ReflectionTestUtils.setField(coupon, "name", name);
		ReflectionTestUtils.setField(coupon, "discountType", discountType);
		ReflectionTestUtils.setField(coupon, "discountValue", discountValue);
		ReflectionTestUtils.setField(coupon, "minPurchaseAmount", minPurchaseAmount);
		ReflectionTestUtils.setField(coupon, "expiresAt", expiresAt);
		ReflectionTestUtils.setField(coupon, "isUsed", used);
		ReflectionTestUtils.setField(coupon, "usedAt", usedAt);
		ReflectionTestUtils.setField(coupon, "templateId", templateId);
		return coupon;
	}
}
