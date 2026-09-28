package com.mansereok.server.domain.product.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 상품 응답이 엔티티의 값을 빠짐없이 옮기는지와 응답 JSON 의 키를 고정한다.
 *
 * <p>예전 가변 클래스는 categoryId 를 채우는 줄이 빠져 응답에 늘 null 이 나갔다. 값이 서로 자리를 바꿔 들어가도 드러나도록
 * 여섯 값을 모두 다르게 둔다.
 */
class SubCategoryDtoTest {

	// 스프링 기본 빌더(Jackson2ObjectMapperBuilder)로 만든 ObjectMapper. 지금은 application*.yml 에 spring.jackson 설정이 없고
	// ObjectMapper 를 바꾸는 코드도 없어서, 키 이름과 null 값 포함 여부가 운영 응답과 같다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	@DisplayName("상품의 id·제목·설명·아이콘·가격·categoryId 를 그대로 옮긴다")
	void copiesEveryValue() {
		// given
		SubCategory subCategory = SubCategoryFixture.paidProduct()
			.id(5L)
			.title("궁합")
			.description("두 사람의 궁합을 풀이합니다")
			.icon("heart.png")
			.price(5000)
			.categoryId(7L)
			.build();

		// when
		SubCategoryDto dto = SubCategoryDto.from(subCategory);

		// then
		assertThat(dto).usingRecursiveComparison()
			.isEqualTo(new SubCategoryDto(5L, "궁합", "두 사람의 궁합을 풀이합니다", "heart.png", 5000, 7L));
	}

	@Test
	@DisplayName("응답 JSON 의 키는 id·title·description·icon·price·categoryId 여섯 개이고 categoryId 에 상품의 카테고리가 담긴다")
	void jsonKeysAndCategoryId() throws Exception {
		// given
		SubCategory subCategory = SubCategoryFixture.paidProduct().id(5L).categoryId(7L).build();

		// when
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(SubCategoryDto.from(subCategory)));

		// then
		assertThat(json.fieldNames()).toIterable()
			.containsExactlyInAnyOrder("id", "title", "description", "icon", "price", "categoryId");
		assertThat(json.get("categoryId").asLong()).isEqualTo(7L);
	}
}
