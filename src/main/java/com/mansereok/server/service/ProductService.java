package com.mansereok.server.service;

import com.mansereok.server.entity.SubCategory;
import com.mansereok.server.repository.SubCategoryRepository;
import com.mansereok.server.service.response.SubCategoryDto;
import java.util.List;
import java.util.stream.Collectors;
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
			.collect(Collectors.toList());
	}

	public SubCategoryDto getSubCategory(Long subCategoryId) {
		SubCategory subCategory = subCategoryRepository.findById(subCategoryId).orElseThrow(
			() -> new RuntimeException("SubCategory not found")
		);
		return SubCategoryDto.from(subCategory);
	}
}
