package com.mansereok.server.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.review.service.ReviewService;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 리뷰 API 가 실제로 내보내는 HTTP 응답을 확인한다.
 *
 * <ul>
 *   <li>목록 API 의 네 경로(전체, 상품별, 전체 페이지, 상품별 페이지)가 내보내는 JSON 에 작성자 이메일이 없고 이름이 가려져
 *   있다.</li>
 *   <li>관리자가 아닌 회원의 리뷰 삭제 요청은 {@link GlobalExceptionHandler} 를 거쳐 403, errorCode FORBIDDEN 으로 끝난다.</li>
 * </ul>
 *
 * <p>컨트롤러, 서비스, 예외 처리기는 진짜를 쓰고 저장소는 돌려줄 값만 정한다. 보안 필터는 넣지 않으므로, 로그인 없이 부를 수
 * 있는지 같은 경로별 공개 규칙은 여기서 확인하지 않는다. 로그인한 요청은 JWT 필터처럼 SecurityContextHolder 에 회원 아이디를
 * principal 로 넣어 흉내 내고, {@code @AuthenticationPrincipal} 은 스프링 시큐리티의 AuthenticationPrincipalArgumentResolver
 * 가 채운다. standaloneSetup 은 스프링 기본 메시지 변환기를 쓰므로 spring.jackson.* 설정은 반영되지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewControllerResponseTest {

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;

	// 생성자 주입. 이 테스트가 목으로 두지 않은 UserService 의 협력 객체는 null 로 들어가며, 회원 조회에서는 쓰이지 않는다.
	@InjectMocks
	private UserService userService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ReviewService reviewService = new ReviewService(reviewRepository, orderRepository, userService);
		mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
	}

	@AfterEach
	void clearLogin() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("전체 리뷰 목록에는 작성자 이메일이 없고 이름은 가려져 있다")
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

	@Test
	@DisplayName("상품별 페이지 단위 리뷰 목록에도 작성자 이메일이 없고 이름은 가려져 있다")
	void pagedReviewsOfProductHideAuthorEmailAndName() throws Exception {
		// given: 1페이지 5건이면 offset 0, limit 5 로 조회한다
		given(reviewRepository.findReviewsBySubCategoryWithPagination(3L, 0L, 5)).willReturn(List.of(
			ReviewFixture.review().subCategoryId(3L).userName("최지우").userEmail("choi@example.com")
				.build()));
		given(reviewRepository.countBySubCategory(3L)).willReturn(1L);

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/reviews/pagination")
			.param("subCategoryId", "3").param("page", "1").param("size", "5"));

		// then
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].email").doesNotExist())
			.andExpect(jsonPath("$.content[0].userName").value("최*우"));
	}

	@Test
	@DisplayName("관리자가 아닌 회원이 리뷰를 지우려 하면 403 과 errorCode FORBIDDEN 으로 답한다")
	void memberDeleteIsAnsweredWithForbidden() throws Exception {
		// given
		givenLoggedIn("member", Role.USER);

		// when
		ResultActions result = mockMvc.perform(delete("/api/v1/reviews/7"));

		// then
		result.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
	}

	/**
	 * JWT 필터가 하는 것처럼 회원 아이디를 principal 로 넣고, 그 회원을 DB 에서 찾으면 주어진 역할이 나오게 한다.
	 */
	private void givenLoggedIn(String username, Role role) {
		User user = User.create(username, "요청자", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
		user.setRole(role);
		given(userRepository.findByUsername(username)).willReturn(Optional.of(user));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
			username, null, List.of(new SimpleGrantedAuthority(role.getAuthority()))));
	}
}
