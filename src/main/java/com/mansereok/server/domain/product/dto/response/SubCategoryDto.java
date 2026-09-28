package com.mansereok.server.domain.product.dto.response;

import com.mansereok.server.domain.product.entity.SubCategory;

/**
 * 상품 목록과 상품 상세로 내려주는 상품 한 건.
 *
 * <p>record 라 구성 요소를 더하면 {@link #from} 도 그 값을 넘겨야 컴파일된다. 값 하나를 빠뜨려 응답에 늘 null 이 나가는
 * 일을 컴파일러가 막는다. 응답 JSON 의 키는 구성 요소 이름 그대로다.
 */
public record SubCategoryDto(
	Long id,
	String title,
	String description,
	String icon,
	Integer price,
	Long categoryId
) {

	public static SubCategoryDto from(SubCategory subCategory) {
		return new SubCategoryDto(
			subCategory.getId(),
			subCategory.getTitle(),
			subCategory.getDescription(),
			subCategory.getIcon(),
			subCategory.getPrice(),
			subCategory.getCategoryId()
		);
	}
}
