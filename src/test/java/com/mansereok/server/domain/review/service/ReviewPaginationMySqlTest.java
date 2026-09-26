package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

/**
 * 리뷰 목록을 실제 MySQL 에서 읽어, 최신순이 지켜지고 페이지를 넘겨도 같은 리뷰가 두 번 나오거나 빠지지 않는지 확인한다.
 *
 * <p>정렬과 LIMIT·OFFSET 은 SQL 이 정하는 일이라 목으로는 확인할 수 없다. 이번 실행의 상품 번호로 리뷰 15건을 넣는다.
 * <ul>
 *   <li>14건은 목록에 나올 리뷰다. 5건씩 읽으면 1~3페이지(5건, 5건, 4건)가 된다.</li>
 *   <li>그중 두 건(sep10SmallerId, sep10LargerId)은 created_at 이 똑같고 5번째·6번째, 곧 1페이지와 2페이지의 경계에 걸린다.
 *   같은 시각이면 id 가 작은 쪽이 앞이다.</li>
 *   <li>나머지 한 건은 가장 최근에 쓴 삭제된 리뷰라 어느 목록에도 나오면 안 된다.</li>
 *   <li>넣는 순서(곧 id 순서)를 created_at 순서와 다르게 해서, created_at 이 아니라 id 로 정렬하면 통과하지 못한다.</li>
 * </ul>
 *
 * <p>전체 리뷰 목록은 DB 에 있는 다른 리뷰도 함께 읽는다. 그래서 이번 실행의 리뷰는 먼 미래(2099년) 시각으로 넣어 맨 앞에 오게 하고,
 * 전체 건수는 확인하지 않는다. 뒤 정리에서 이번 실행의 주문 번호로 넣은 행만 지운다. reviews 의 user_id·sub_category_id·order_id
 * 에는 외래 키가 없다.
 *
 * <p>인덱스 순서대로 읽어 따로 정렬하지 않는지(filesort 없음)는 같은 쿼리를 EXPLAIN 하는 ReviewIndexUsageMySqlTest 가 본다.
 */
class ReviewPaginationMySqlTest extends LocalMySqlTest {

	private static final int PAGE_SIZE = 5;
	private static final String CONTENT = "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.";

	@Autowired
	private ReviewService reviewService;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runNumber = Long.parseLong(runId, 16);
	// 실제 데이터와 겹치지 않게 큰 수에서 시작하고, 컬럼마다 다른 수에서 시작해 쿼리가 컬럼을 바꿔 써도 알아챌 수 있게 한다.
	private final long subCategoryId = 7_200_000_000L + runNumber;
	private final long userId = 6_200_000_000L + runNumber;
	private final long firstOrderId = 5_200_000_000L + runNumber * 100;

	// 이름의 날짜가 created_at(2099년 9월 그날 10시)이다.
	private long sep02;
	private long sep03;
	private long sep04;
	private long sep05;
	private long sep06;
	private long sep07;
	private long sep08;
	private long sep09;
	private long sep10SmallerId;
	private long sep10LargerId;
	private long sep11;
	private long sep12;
	private long sep13;
	private long sep14;

	@BeforeEach
	void saveReviewsInShuffledOrder() {
		sep02 = saveReview(0, september(2), false);
		sep10SmallerId = saveReview(1, september(10), false);
		sep14 = saveReview(2, september(14), false);
		sep05 = saveReview(3, september(5), false);
		sep10LargerId = saveReview(4, september(10), false);
		sep13 = saveReview(5, september(13), false);
		sep03 = saveReview(6, september(3), false);
		saveReview(7, september(15), true);
		sep11 = saveReview(8, september(11), false);
		sep07 = saveReview(9, september(7), false);
		sep04 = saveReview(10, september(4), false);
		sep12 = saveReview(11, september(12), false);
		sep08 = saveReview(12, september(8), false);
		sep06 = saveReview(13, september(6), false);
		sep09 = saveReview(14, september(9), false);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM reviews WHERE order_id BETWEEN ? AND ?", firstOrderId, firstOrderId + 14);
	}

	@Test
	@DisplayName("상품별 리뷰를 5건씩 1~3페이지로 넘기면 최신순으로 14건이 한 번씩만 나오고, 같은 시각인 두 건은 id 가 작은 쪽이 1페이지 끝에 온다")
	void reviewsOfProductPagesAreInLatestOrderWithoutRepeats() {
		// when
		Page<ReviewResponse> first = reviewService.getReviewsBySubCategory(subCategoryId, 1, PAGE_SIZE);
		Page<ReviewResponse> second = reviewService.getReviewsBySubCategory(subCategoryId, 2, PAGE_SIZE);
		Page<ReviewResponse> third = reviewService.getReviewsBySubCategory(subCategoryId, 3, PAGE_SIZE);

		// then: 세 페이지의 id 를 순서까지 정확히 적는다. 그러면 페이지 사이의 중복과 누락도 함께 드러난다
		assertThat(first.getContent()).as("1페이지").extracting(ReviewResponse::reviewId)
			.containsExactly(sep14, sep13, sep12, sep11, sep10SmallerId);
		assertThat(second.getContent()).as("2페이지").extracting(ReviewResponse::reviewId)
			.containsExactly(sep10LargerId, sep09, sep08, sep07, sep06);
		assertThat(third.getContent()).as("3페이지").extracting(ReviewResponse::reviewId)
			.containsExactly(sep05, sep04, sep03, sep02);
		assertThat(List.of(first, second, third)).as("페이지마다 세는 전체 건수와 페이지 수")
			.allSatisfy(page -> {
				assertThat(page.getTotalElements()).as("삭제되지 않은 리뷰 수").isEqualTo(14L);
				assertThat(page.getTotalPages()).isEqualTo(3);
			});
	}

	@Test
	@DisplayName("전체 리뷰를 5건씩 1~3페이지로 넘겨도 최신순으로 14건이 한 번씩만 나오고, 같은 시각인 두 건은 id 가 작은 쪽이 앞이다")
	void allReviewsPagesAreInLatestOrderWithoutRepeats() {
		// when
		Page<ReviewResponse> first = reviewService.getAllReviewsSortedByLatest(1, PAGE_SIZE);
		Page<ReviewResponse> second = reviewService.getAllReviewsSortedByLatest(2, PAGE_SIZE);
		Page<ReviewResponse> third = reviewService.getAllReviewsSortedByLatest(3, PAGE_SIZE);

		// then: 3페이지는 5칸이 다 찰 수 있다. 6번째 칸부터는 이번 실행보다 오래된 다른 리뷰다
		assertThat(first.getContent()).as("1페이지").extracting(ReviewResponse::reviewId)
			.containsExactly(sep14, sep13, sep12, sep11, sep10SmallerId);
		assertThat(second.getContent()).as("2페이지").extracting(ReviewResponse::reviewId)
			.containsExactly(sep10LargerId, sep09, sep08, sep07, sep06);
		assertThat(third.getContent()).as("3페이지").extracting(ReviewResponse::reviewId)
			.startsWith(sep05, sep04, sep03, sep02);
	}

	@Test
	@DisplayName("로그인 없이 보는 상품별 목록은 삭제되지 않은 14건을 페이지 목록과 같은 최신순으로 돌려준다")
	void publicListOfProductIsInLatestOrder() {
		// when
		List<ReviewResponse> reviews = reviewService.getReviewsBySubCategory(subCategoryId);

		// then
		assertThat(reviews).extracting(ReviewResponse::reviewId)
			.containsExactly(sep14, sep13, sep12, sep11, sep10SmallerId, sep10LargerId, sep09, sep08, sep07, sep06,
				sep05, sep04, sep03, sep02);
	}

	@Test
	@DisplayName("로그인 없이 보는 전체 목록도 같은 최신순이라 이번 실행의 14건이 맨 앞에 차례로 온다")
	void publicListOfAllReviewsIsInLatestOrder() {
		// when
		List<ReviewResponse> reviews = reviewService.getAllReviewsSortedByLatest();

		// then
		assertThat(reviews).extracting(ReviewResponse::reviewId)
			.startsWith(sep14, sep13, sep12, sep11, sep10SmallerId, sep10LargerId, sep09, sep08, sep07, sep06,
				sep05, sep04, sep03, sep02);
	}

	private static LocalDateTime september(int day) {
		return LocalDateTime.of(2099, 9, day, 10, 0);
	}

	/** 리뷰 한 건을 SQL 로 넣고 DB 가 매긴 id 를 돌려준다. 먼저 넣은 리뷰의 id 가 더 작다. */
	private long saveReview(int orderNumber, LocalDateTime createdAt, boolean deleted) {
		long orderId = firstOrderId + orderNumber;
		jdbcTemplate.update(
			"INSERT INTO reviews (user_id, sub_category_id, order_id, content, is_deleted, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)",
			userId, subCategoryId, orderId, CONTENT, deleted, createdAt, createdAt);
		return jdbcTemplate.queryForObject("SELECT id FROM reviews WHERE order_id = ?", Long.class, orderId);
	}
}
