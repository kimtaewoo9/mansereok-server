package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.product.entity.SubCategory;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 SubCategory 를 만든다. SubCategory 는 생성자가 protected 라 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다.
 *
 * <p>테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를 돌려주므로 테스트끼리 값이 섞이지 않는다.
 */
public final class SubCategoryFixture {

	private Long id = 1L;
	private String title = "인생 총운";
	private Integer price = 10000;
	private Long categoryId = 1L;

	private SubCategoryFixture() {
	}

	/** 가격이 있는 일반 상품. */
	public static SubCategoryFixture paidProduct() {
		return new SubCategoryFixture();
	}

	/** 가격이 0원인 상품. */
	public static SubCategoryFixture freeProduct() {
		return new SubCategoryFixture().price(0);
	}

	public SubCategoryFixture id(Long id) {
		this.id = id;
		return this;
	}

	public SubCategoryFixture title(String title) {
		this.title = title;
		return this;
	}

	public SubCategoryFixture price(Integer price) {
		this.price = price;
		return this;
	}

	/** DB 에 저장할 때는 id 를 비워 둔다(IDENTITY 가 채운다). */
	public SubCategoryFixture withoutId() {
		this.id = null;
		return this;
	}

	public SubCategory build() {
		SubCategory subCategory = BeanUtils.instantiateClass(SubCategory.class);
		ReflectionTestUtils.setField(subCategory, "id", id);
		ReflectionTestUtils.setField(subCategory, "title", title);
		ReflectionTestUtils.setField(subCategory, "price", price);
		ReflectionTestUtils.setField(subCategory, "categoryId", categoryId);
		return subCategory;
	}
}
