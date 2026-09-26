package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

/**
 * 리뷰 목록 조회가 한 번에 읽는 건수와 page·size 규칙을 확인한다.
 *
 * <ul>
 *   <li>로그인 없이 보는 목록은 offset 0, limit {@value ReviewService#PUBLIC_REVIEW_LIMIT} 로만 읽는다.</li>
 *   <li>페이지 목록은 page 가 1 이상, size 가 1 이상 {@value ReviewService#MAX_PAGE_SIZE} 이하일 때만 쿼리를 보낸다.</li>
 *   <li>범위 안이면 offset = (page - 1) × size, limit = size 로 읽고, Page 번호는 0부터 센다.</li>
 * </ul>
 *
 * <p>저장소는 돌려줄 값만 정한다. 조회 인자는 정확한 값으로 스텁해 두어, 코드가 다른 offset·limit 으로 부르면 MockitoExtension 의
 * strict stubs 가 PotentialStubbingProblem 으로 테스트를 실패시킨다. 그래서 조회를 verify 로 다시 확인하지 않는다. 정렬 순서와
 * 페이지 사이의 중복·누락은 SQL 이 정하는 일이라 ReviewPaginationMySqlTest 가 실제 MySQL 로 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceListingTest {

	private static final long SUB_CATEGORY_ID = 3L;

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;

	// 생성자 주입. 목록 조회는 회원을 찾지 않으므로 UserService 의 협력 객체는 쓰이지 않는다.
	@InjectMocks
	private UserService userService;

	private ReviewService reviewService;

	@BeforeEach
	void setUp() {
		// 목록 조회는 시각을 쓰지 않는다. 시스템 시계에 기대지 않도록 고정 시계를 넣는다.
		reviewService = new ReviewService(reviewRepository, orderRepository, userService,
			Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneId.of("Asia/Seoul")));
	}

	@Nested
	@DisplayName("로그인 없이 보는 목록은")
	class PublicList {

		@Test
		@DisplayName("전체 리뷰를 최신 100건까지만 읽고, 저장소가 준 순서 그대로 돌려준다")
		void allReviewsAreReadUpToLimit() {
			// given
			given(reviewRepository.findAllReviewsWithPagination(0L, 100)).willReturn(List.of(
				ReviewFixture.review().id(21L).build(),
				ReviewFixture.review().id(20L).build()));

			// when
			List<ReviewResponse> reviews = reviewService.getAllReviewsSortedByLatest();

			// then
			assertThat(reviews).extracting(ReviewResponse::reviewId).containsExactly(21L, 20L);
		}

		@Test
		@DisplayName("상품별 리뷰도 최신 100건까지만 읽고, 저장소가 준 순서 그대로 돌려준다")
		void reviewsOfProductAreReadUpToLimit() {
			// given
			given(reviewRepository.findReviewsBySubCategoryWithPagination(SUB_CATEGORY_ID, 0L, 100))
				.willReturn(List.of(
					ReviewFixture.review().id(31L).subCategoryId(SUB_CATEGORY_ID).build(),
					ReviewFixture.review().id(30L).subCategoryId(SUB_CATEGORY_ID).build()));

			// when
			List<ReviewResponse> reviews = reviewService.getReviewsBySubCategory(SUB_CATEGORY_ID);

			// then
			assertThat(reviews).extracting(ReviewResponse::reviewId).containsExactly(31L, 30L);
		}
	}

	@Nested
	@DisplayName("페이지 목록의 page·size 가 범위를 벗어나면")
	class WhenPageOrSizeIsOutOfRange {

		@ParameterizedTest(name = "[{index}] page={0}, size={1} → \"{2}\"")
		@CsvSource(textBlock = """
			# page, size, 예외 메시지
			 0,  5, 페이지 번호(page)는 1 이상이어야 합니다.
			-1,  5, 페이지 번호(page)는 1 이상이어야 합니다.
			 1,  0, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			 1, -1, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			 1, 51, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			""")
		@DisplayName("전체 리뷰 페이지는 IllegalArgumentException 을 내고 쿼리를 하나도 보내지 않는다")
		void allReviewsPageIsRejectedBeforeQuery(int page, int size, String message) {
			// when & then
			assertThatThrownBy(() -> reviewService.getAllReviewsSortedByLatest(page, size))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(message);
			verifyNoInteractions(reviewRepository);
		}

		@ParameterizedTest(name = "[{index}] page={0}, size={1} → \"{2}\"")
		@CsvSource(textBlock = """
			# page, size, 예외 메시지
			 0,  5, 페이지 번호(page)는 1 이상이어야 합니다.
			-1,  5, 페이지 번호(page)는 1 이상이어야 합니다.
			 1,  0, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			 1, -1, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			 1, 51, 페이지 크기(size)는 1 이상 50 이하여야 합니다.
			""")
		@DisplayName("상품별 리뷰 페이지는 IllegalArgumentException 을 내고 쿼리를 하나도 보내지 않는다")
		void reviewsOfProductPageIsRejectedBeforeQuery(int page, int size, String message) {
			// when & then
			assertThatThrownBy(() -> reviewService.getReviewsBySubCategory(SUB_CATEGORY_ID, page, size))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(message);
			verifyNoInteractions(reviewRepository);
		}
	}

	@Nested
	@DisplayName("페이지 목록의 page·size 가 범위 안이면")
	class WhenPageAndSizeAreInRange {

		// 스텁한 offset·limit 과 다르게 부르면 strict stubs 가 실패시킨다. 마지막 줄은 page 가 int 최댓값이어도 offset 이
		// 넘치지 않고 long 으로 계산되는지 본다.
		@ParameterizedTest(name = "[{index}] page={0}, size={1} → offset {2}, limit {1}, Page 번호 {3}")
		@CsvSource(textBlock = """
			# page, size, offset, Page 번호
			         1,  1,            0,          0
			         1, 50,            0,          0
			         2,  5,            5,          1
			         3, 50,          100,          2
			2147483647, 50, 107374182300, 2147483646
			""")
		@DisplayName("전체 리뷰를 offset = (page - 1) × size, limit = size 로 읽고 Page 번호는 page - 1 이다")
		void allReviewsPageIsReadWithOffsetAndLimit(int page, int size, long offset, int pageNumber) {
			// given
			given(reviewRepository.findAllReviewsWithPagination(offset, size)).willReturn(List.of());
			given(reviewRepository.countAllReviews()).willReturn(0L);

			// when
			Page<ReviewResponse> result = reviewService.getAllReviewsSortedByLatest(page, size);

			// then
			assertThat(result.getNumber()).as("Page 번호").isEqualTo(pageNumber);
			assertThat(result.getSize()).as("Page 크기").isEqualTo(size);
		}

		@Test
		@DisplayName("상품별 리뷰 page=2, size=5 이면 offset 5, limit 5 로 읽은 5건과 전체 건수로 두 번째 Page 를 만든다")
		void reviewsOfProductSecondPage() {
			// given
			given(reviewRepository.findReviewsBySubCategoryWithPagination(SUB_CATEGORY_ID, 5L, 5)).willReturn(List.of(
				ReviewFixture.review().id(16L).build(),
				ReviewFixture.review().id(15L).build(),
				ReviewFixture.review().id(14L).build(),
				ReviewFixture.review().id(13L).build(),
				ReviewFixture.review().id(12L).build()));
			given(reviewRepository.countBySubCategory(SUB_CATEGORY_ID)).willReturn(12L);

			// when
			Page<ReviewResponse> result = reviewService.getReviewsBySubCategory(SUB_CATEGORY_ID, 2, 5);

			// then
			assertThat(result.getContent()).extracting(ReviewResponse::reviewId)
				.containsExactly(16L, 15L, 14L, 13L, 12L);
			assertThat(result.getNumber()).as("Page 번호(0부터)").isEqualTo(1);
			assertThat(result.getSize()).as("Page 크기").isEqualTo(5);
			assertThat(result.getTotalElements()).as("전체 건수").isEqualTo(12L);
			assertThat(result.getTotalPages()).as("전체 페이지 수").isEqualTo(3);
		}
	}
}
