package com.mansereok.server.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.review.service.ReviewService;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 리뷰 목록 API 가 실제로 내보내는 JSON 에 작성자 이메일이 없고 이름이 가려져 있는지 확인한다.
 *
 * <p>컨트롤러와 서비스는 진짜를 쓰고, 저장소는 돌려줄 리뷰만 정한다. 목록 조회는 주문과 회원을 쓰지 않으므로 주문 저장소는
 * 목으로 두고 회원 서비스는 넣지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewControllerResponseTest {

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private OrderRepository orderRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ReviewService reviewService = new ReviewService(reviewRepository, orderRepository, null);
		mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService)).build();
	}

	@Test
	@DisplayName("로그인 없이 부르는 전체 리뷰 목록에는 작성자 이메일이 없고 이름은 가려져 있다")
	void allReviewsHideAuthorEmailAndName() throws Exception {
		// given
		given(reviewRepository.findAllLatestReviews()).willReturn(List.of(
			ReviewFixture.review().userName("홍길동").userEmail("hong@example.com").build()));

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/reviews"));

		// then
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].email").doesNotExist())
			.andExpect(jsonPath("$[0].userName").value("홍*동"));
		assertThat(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
			.doesNotContain("hong@example.com", "홍길동");
	}

	@Test
	@DisplayName("상품별 리뷰 목록에도 작성자 이메일이 없고 이름은 가려져 있다")
	void reviewsOfProductHideAuthorEmailAndName() throws Exception {
		// given
		given(reviewRepository.findReviewsBySubCategory(3L)).willReturn(List.of(
			ReviewFixture.review().subCategoryId(3L).userName("남궁민수").userEmail("namgung@example.com")
				.build()));

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/reviews").param("subCategoryId", "3"));

		// then
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].email").doesNotExist())
			.andExpect(jsonPath("$[0].userName").value("남**수"));
	}

	@Test
	@DisplayName("페이지 단위 리뷰 목록에도 작성자 이메일이 없고 이름은 가려져 있다")
	void pagedReviewsHideAuthorEmailAndName() throws Exception {
		// given: 1페이지 5건이면 offset 0, limit 5 로 조회한다
		given(reviewRepository.findAllReviewsWithPagination(0L, 5)).willReturn(List.of(
			ReviewFixture.review().userName("이준").userEmail("lee@example.com").build()));
		given(reviewRepository.countAllReviews()).willReturn(1L);

		// when
		ResultActions result = mockMvc.perform(
			get("/api/v1/reviews/pagination").param("page", "1").param("size", "5"));

		// then
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].email").doesNotExist())
			.andExpect(jsonPath("$.content[0].userName").value("이*"));
	}
}
