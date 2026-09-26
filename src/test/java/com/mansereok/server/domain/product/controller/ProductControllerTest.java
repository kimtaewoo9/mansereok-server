package com.mansereok.server.domain.product.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.product.service.ProductService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 상품 조회 API 가 없는 상품을 500 이 아니라 404 로 답하고, 상품 응답에 categoryId 를 채워 내보내는지 확인한다.
 *
 * <p>보안 필터는 빼고 운영과 같은 두 예외 처리기만 등록한 standalone MockMvc 로 부른다. 서비스는 진짜를 쓰고 저장소는 돌려줄
 * 값만 정한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

	@Mock
	private SubCategoryRepository subCategoryRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(new ProductService(subCategoryRepository)))
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("상품 상세를 조회할 때")
	class ProductDetail {

		@Test
		@DisplayName("없는 상품이면 404 와 NOT_FOUND, 고정 안내 문구를 돌려준다")
		void missingProductIsNotFound() throws Exception {
			// given
			given(subCategoryRepository.findById(999999L)).willReturn(Optional.empty());

			// when
			ResultActions result = mockMvc.perform(get("/api/v1/products/999999"));

			// then
			result.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("요청하신 리소스를 찾을 수 없습니다."));
		}

		@Test
		@DisplayName("있는 상품이면 200 과 함께 그 상품의 categoryId 를 채워 돌려준다")
		void existingProductHasCategoryId() throws Exception {
			// given
			given(subCategoryRepository.findById(5L)).willReturn(Optional.of(
				SubCategoryFixture.paidProduct().id(5L).title("궁합").price(5000).categoryId(7L).build()));

			// when
			ResultActions result = mockMvc.perform(get("/api/v1/products/5"));

			// then
			result.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(5))
				.andExpect(jsonPath("$.title").value("궁합"))
				.andExpect(jsonPath("$.price").value(5000))
				.andExpect(jsonPath("$.categoryId").value(7));
		}
	}

	@Nested
	@DisplayName("카테고리의 상품 목록을 조회할 때")
	class ProductList {

		@Test
		@DisplayName("200 과 함께 상품마다 categoryId 를 채워 돌려준다")
		void everyProductHasCategoryId() throws Exception {
			// given
			given(subCategoryRepository.findAllByCategoryId(7L)).willReturn(List.of(
				SubCategoryFixture.paidProduct().id(12L).categoryId(7L).build(),
				SubCategoryFixture.paidProduct().id(11L).categoryId(7L).build()));

			// when
			ResultActions result = mockMvc.perform(get("/api/v1/products").param("categoryId", "7"));

			// then
			result.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(12))
				.andExpect(jsonPath("$[0].categoryId").value(7))
				.andExpect(jsonPath("$[1].id").value(11))
				.andExpect(jsonPath("$[1].categoryId").value(7));
		}
	}
}
