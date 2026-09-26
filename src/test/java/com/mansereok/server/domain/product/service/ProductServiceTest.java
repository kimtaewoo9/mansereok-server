package com.mansereok.server.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.product.dto.response.SubCategoryDto;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 상품 조회 서비스가 없는 상품을 EntityNotFoundException 으로 알리고, 있는 상품은 categoryId 까지 담아 돌려주는지 확인한다.
 *
 * <p>저장소는 돌려줄 값만 정하고 호출 여부는 확인하지 않는다. 정확한 인자로 스텁해 두면 다른 id 나 카테고리로 조회할 때
 * MockitoExtension 의 strict stubs 가 테스트를 실패시킨다.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	@Mock
	private SubCategoryRepository subCategoryRepository;

	private ProductService productService;

	@BeforeEach
	void setUp() {
		productService = new ProductService(subCategoryRepository);
	}

	@Nested
	@DisplayName("상품 하나를 조회할 때")
	class GetOne {

		@Test
		@DisplayName("상품이 없으면 id 를 담은 메시지로 EntityNotFoundException 을 던진다")
		void throwsEntityNotFoundWhenMissing() {
			// given
			given(subCategoryRepository.findById(999999L)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> productService.getSubCategory(999999L))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("상품을 찾을 수 없습니다: 999999");
		}

		@Test
		@DisplayName("상품이 있으면 그 상품의 categoryId 까지 담아 돌려준다")
		void returnsProductWithCategoryId() {
			// given
			given(subCategoryRepository.findById(5L)).willReturn(Optional.of(
				SubCategoryFixture.paidProduct().id(5L).categoryId(7L).build()));

			// when
			SubCategoryDto product = productService.getSubCategory(5L);

			// then
			assertThat(product.id()).isEqualTo(5L);
			assertThat(product.categoryId()).isEqualTo(7L);
		}
	}

	@Nested
	@DisplayName("카테고리의 상품 목록을 조회할 때")
	class GetList {

		@Test
		@DisplayName("저장소가 돌려준 순서 그대로, 상품마다 categoryId 를 담아 돌려준다")
		void keepsRepositoryOrder() {
			// given
			given(subCategoryRepository.findAllByCategoryId(3L)).willReturn(List.of(
				SubCategoryFixture.paidProduct().id(12L).title("궁합").categoryId(3L).build(),
				SubCategoryFixture.paidProduct().id(11L).title("인생 총운").categoryId(3L).build()));

			// when
			List<SubCategoryDto> products = productService.getSubCategories(3L);

			// then
			assertThat(products)
				.extracting(SubCategoryDto::id, SubCategoryDto::title, SubCategoryDto::categoryId)
				.containsExactly(
					tuple(12L, "궁합", 3L),
					tuple(11L, "인생 총운", 3L));
		}

		@Test
		@DisplayName("카테고리에 상품이 없으면 빈 목록을 돌려준다")
		void returnsEmptyList() {
			// given
			given(subCategoryRepository.findAllByCategoryId(3L)).willReturn(List.of());

			// when
			List<SubCategoryDto> products = productService.getSubCategories(3L);

			// then
			assertThat(products).isEmpty();
		}
	}
}
