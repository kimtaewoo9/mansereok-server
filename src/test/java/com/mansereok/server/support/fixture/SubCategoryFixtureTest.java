package com.mansereok.server.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.product.entity.SubCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SubCategoryFixture 는 SubCategory 필드를 이름으로 채운다. 필드 이름이 바뀌면 이 빌더를 쓰는 테스트가 실행 중에야 깨지므로,
 * 여기서 먼저 알린다.
 */
class SubCategoryFixtureTest {

	@Test
	@DisplayName("기본값으로 만든 유료 상품은 id 1, 제목, 가격 10,000원, 카테고리 1 을 SubCategory 에 담고 설명과 아이콘은 비워 둔다")
	void paidProductDefaults() {
		// when
		SubCategory subCategory = SubCategoryFixture.paidProduct().build();

		// then
		assertThat(subCategory)
			.extracting(SubCategory::getId, SubCategory::getTitle, SubCategory::getDescription,
				SubCategory::getIcon, SubCategory::getPrice, SubCategory::getCategoryId)
			.containsExactly(1L, "인생 총운", null, null, 10000, 1L);
	}

	@Test
	@DisplayName("빌더에서 바꾼 id·제목·설명·아이콘·가격·카테고리가 SubCategory 에 담긴다")
	void changedValuesReachTheEntity() {
		// when
		SubCategory subCategory = SubCategoryFixture.paidProduct()
			.id(19L)
			.title("궁합")
			.description("두 사람의 궁합을 풀이합니다")
			.icon("heart.png")
			.price(5000)
			.categoryId(4L)
			.build();

		// then
		assertThat(subCategory)
			.extracting(SubCategory::getId, SubCategory::getTitle, SubCategory::getDescription,
				SubCategory::getIcon, SubCategory::getPrice, SubCategory::getCategoryId)
			.containsExactly(19L, "궁합", "두 사람의 궁합을 풀이합니다", "heart.png", 5000, 4L);
	}

	@Test
	@DisplayName("무료 상품은 가격이 0원이고, withoutId 로 만들면 DB 가 채우도록 id 를 비운다")
	void freeProductAndWithoutId() {
		assertThat(SubCategoryFixture.freeProduct().build().getPrice()).isZero();
		assertThat(SubCategoryFixture.paidProduct().withoutId().build().getId()).isNull();
	}
}
