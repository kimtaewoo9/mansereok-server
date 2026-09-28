package com.mansereok.server.domain.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 애플리케이션 전체를 띄워, 로그인하지 않은 요청으로 상품 API 를 불렀을 때 실제 MySQL 에 저장된 categoryId 가 응답에 나가고 지운
 * 상품은 404 로 답하는지 확인한다. 보안 규칙, 트랜잭션, 예외 처리기가 운영과 같이 모두 걸린 상태에서 본다.
 *
 * <p>{@code @AutoConfigureMockMvc} 는 붙이지 않는다. 붙이면 다른 MySQL 테스트와 설정이 달라져, 이 클래스 하나 때문에 스프링
 * 컨텍스트와 커넥션 풀을 하나 더 띄운다. 대신 이미 뜬 컨텍스트로 MockMvc 를 만들고 보안 필터(springSecurityFilterChain)를 직접
 * 건다.
 *
 * <p>상품은 실행마다 UUID 앞 8자리로 만든 카테고리 번호에 저장하고, 뒤 정리에서 그 카테고리의 행만 지운다.
 */
class ProductApiMySqlTest extends LocalMySqlTest {

	@Autowired
	private WebApplicationContext webApplicationContext;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private ObjectMapper objectMapper;

	// 실행마다 다른 카테고리 번호라 다른 테스트나 이전 실행이 남긴 상품과 섞이지 않는다.
	private final long categoryId = 10_000_000_000L + Long.parseLong(UUID.randomUUID().toString().substring(0, 8), 16);

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {
		// 보안 필터를 걸지 않으면 로그인 없이 부를 수 있는지(SecurityConfig 의 공개 경로)를 확인하지 못한다.
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
			.addFilters(webApplicationContext.getBean("springSecurityFilterChain", Filter.class))
			.build();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM subcategories WHERE category_id = ?", categoryId);
	}

	@Test
	@DisplayName("상품 상세를 부르면 200 과 DB 에 저장된 categoryId 를 받는다")
	void detailHasStoredCategoryId() throws Exception {
		// given
		Long productId = saveProduct("궁합");

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/products/{productId}", productId));

		// then
		JsonNode body = readBody(result.andExpect(status().isOk()));
		assertThat(body.get("id").asLong()).isEqualTo(productId);
		assertThat(body.get("title").asText()).isEqualTo("궁합");
		assertThat(body.get("categoryId").asLong()).as("DB 에 저장된 카테고리").isEqualTo(categoryId);
	}

	@Test
	@DisplayName("카테고리의 상품 목록을 부르면 그 카테고리 상품만 받고 모두 categoryId 가 채워져 있다")
	void listHasStoredCategoryId() throws Exception {
		// given
		Long firstId = saveProduct("인생 총운");
		Long secondId = saveProduct("궁합");

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(categoryId)));

		// then: 목록 쿼리에 ORDER BY 가 없어 순서는 보지 않는다
		JsonNode body = readBody(result.andExpect(status().isOk()));
		assertThat(body.findValues("id")).extracting(JsonNode::asLong).containsExactlyInAnyOrder(firstId, secondId);
		assertThat(body.findValues("categoryId")).extracting(JsonNode::asLong).containsExactly(categoryId, categoryId);
	}

	@Test
	@DisplayName("지운 상품의 주소로 부르면 500 이 아니라 404 와 NOT_FOUND 를 받는다")
	void deletedProductIsNotFound() throws Exception {
		// given: IDENTITY 는 지운 번호를 다시 쓰지 않으므로 이 번호의 상품은 없다
		Long deletedId = saveProduct("지운 상품");
		subCategoryRepository.deleteById(deletedId);

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/products/{productId}", deletedId));

		// then
		result.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("요청하신 리소스를 찾을 수 없습니다."));
	}

	private Long saveProduct(String title) {
		return subCategoryRepository.save(
			SubCategoryFixture.paidProduct().withoutId().title(title).categoryId(categoryId).build()).getId();
	}

	private JsonNode readBody(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}
}
