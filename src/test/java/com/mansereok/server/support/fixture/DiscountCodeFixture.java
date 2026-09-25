package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.entity.DiscountType;
import java.time.LocalDateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 DiscountCode 를 만든다. DiscountCode 는 생성자가 protected 이고 값을 바꾸는 메서드가 없어 리플렉션이 필요한데, 그 우회를
 * 이 클래스 한 곳에만 둔다.
 *
 * <p>기본값은 "지금 바로 쓸 수 있는 정액 1,000원 코드" 다. 활성, 만료는 지금부터 30일 뒤, 최대 100번 중 0번 사용, 최소 주문 금액
 * 0원, 상품·카테고리 제한 없음. 테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를 돌려주므로
 * 테스트끼리 값이 섞이지 않는다.
 */
public final class DiscountCodeFixture {

	private Long id = 1L;
	private String code = "TEST-CODE";
	private DiscountType discountType = DiscountType.FIXED_AMOUNT;
	private int discountValue = 1000;
	private LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
	private int maxUses = 100;
	private int currentUses = 0;
	private int minPurchaseAmount = 0;
	private boolean active = true;
	private Long subCategoryId = null;
	private Long categoryId = null;

	private DiscountCodeFixture() {
	}

	/** 지금 바로 쓸 수 있는 정액 1,000원 코드. */
	public static DiscountCodeFixture usableCode() {
		return new DiscountCodeFixture();
	}

	/** 주문 금액에서 amount 원을 빼는 정액 할인 코드. */
	public static DiscountCodeFixture fixedAmount(int amount) {
		return new DiscountCodeFixture().discountType(DiscountType.FIXED_AMOUNT).discountValue(amount);
	}

	/** 주문 금액의 percent % 를 빼는 정률 할인 코드. */
	public static DiscountCodeFixture percentage(int percent) {
		return new DiscountCodeFixture().discountType(DiscountType.PERCENTAGE).discountValue(percent);
	}

	public DiscountCodeFixture id(Long id) {
		this.id = id;
		return this;
	}

	/** DB 에 저장할 때는 id 를 비워 둔다(IDENTITY 가 채운다). */
	public DiscountCodeFixture withoutId() {
		this.id = null;
		return this;
	}

	public DiscountCodeFixture code(String code) {
		this.code = code;
		return this;
	}

	public DiscountCodeFixture discountType(DiscountType discountType) {
		this.discountType = discountType;
		return this;
	}

	public DiscountCodeFixture discountValue(int discountValue) {
		this.discountValue = discountValue;
		return this;
	}

	public DiscountCodeFixture expiresAt(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	public DiscountCodeFixture maxUses(int maxUses) {
		this.maxUses = maxUses;
		return this;
	}

	public DiscountCodeFixture currentUses(int currentUses) {
		this.currentUses = currentUses;
		return this;
	}

	public DiscountCodeFixture minPurchaseAmount(int minPurchaseAmount) {
		this.minPurchaseAmount = minPurchaseAmount;
		return this;
	}

	public DiscountCodeFixture active(boolean active) {
		this.active = active;
		return this;
	}

	/** 이 상품(SubCategory)에만 쓸 수 있게 한다. null 이면 모든 상품. */
	public DiscountCodeFixture subCategoryId(Long subCategoryId) {
		this.subCategoryId = subCategoryId;
		return this;
	}

	/** 이 카테고리에만 쓸 수 있게 한다. null 이면 모든 카테고리. */
	public DiscountCodeFixture categoryId(Long categoryId) {
		this.categoryId = categoryId;
		return this;
	}

	public DiscountCode build() {
		DiscountCode discountCode = BeanUtils.instantiateClass(DiscountCode.class);
		ReflectionTestUtils.setField(discountCode, "id", id);
		ReflectionTestUtils.setField(discountCode, "code", code);
		ReflectionTestUtils.setField(discountCode, "discountType", discountType);
		ReflectionTestUtils.setField(discountCode, "discountValue", discountValue);
		ReflectionTestUtils.setField(discountCode, "expiresAt", expiresAt);
		ReflectionTestUtils.setField(discountCode, "maxUses", maxUses);
		ReflectionTestUtils.setField(discountCode, "currentUses", currentUses);
		ReflectionTestUtils.setField(discountCode, "minPurchaseAmount", minPurchaseAmount);
		ReflectionTestUtils.setField(discountCode, "isActive", active);
		ReflectionTestUtils.setField(discountCode, "subCategoryId", subCategoryId);
		ReflectionTestUtils.setField(discountCode, "categoryId", categoryId);
		return discountCode;
	}
}
