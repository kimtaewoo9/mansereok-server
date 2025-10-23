package com.mansereok.server.controller;

import com.mansereok.server.service.ProductService;
import com.mansereok.server.service.response.SubCategoryDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProductController {

	private final ProductService productService;

	@GetMapping("/api/v1/products")
	public ResponseEntity<List<SubCategoryDto>> getProductsByCategory(
		@RequestParam Long categoryId
	) {
		List<SubCategoryDto> products = productService.getSubCategories(categoryId);
		return ResponseEntity.ok(products);
	}

	@GetMapping("/api/v1/products/{productId}")
	public ResponseEntity<SubCategoryDto> getProductDetail(
		@PathVariable("productId") Long productId) {
		SubCategoryDto subCategory = productService.getSubCategory(productId);
		return ResponseEntity.ok(subCategory);
	}
}
