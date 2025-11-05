package com.mansereok.server.domain.product.dto.response;

import com.mansereok.server.domain.product.entity.SubCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubCategoryDto {

	private Long id;
	private String title;
	private String description;
	private String icon;
	private Integer price;
	private Long categoryId;

	public static SubCategoryDto from(SubCategory subCategory) {
		SubCategoryDto subCategoryDto = new SubCategoryDto();
		subCategoryDto.setId(subCategory.getId());
		subCategoryDto.setTitle(subCategory.getTitle());
		subCategoryDto.setDescription(subCategory.getDescription());
		subCategoryDto.setIcon(subCategory.getIcon());
		subCategoryDto.setPrice(subCategory.getPrice());
		return subCategoryDto;
	}
}
