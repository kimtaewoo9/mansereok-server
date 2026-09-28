package com.mansereok.server.domain.product.service;

import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.product.dto.response.SubCategoryDto;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

	private final SubCategoryRepository subCategoryRepository;

	public List<SubCategoryDto> getSubCategories(Long categoryId) {
		List<SubCategory> subCategories = subCategoryRepository.findAllByCategoryId(categoryId);
		return subCategories.stream()
			.map(SubCategoryDto::from)
			.toList();
	}

	/**
	 * 상품 하나를 조회한다. 없는 상품이면 EntityNotFoundException 을 던져 GlobalExceptionHandler 가 404 로 답하게 한다.
	 */
	public SubCategoryDto getSubCategory(Long subCategoryId) {
		SubCategory subCategory = subCategoryRepository.findById(subCategoryId).orElseThrow(
			() -> new EntityNotFoundException("상품을 찾을 수 없습니다: " + subCategoryId)
		);
		return SubCategoryDto.from(subCategory);
	}
}
