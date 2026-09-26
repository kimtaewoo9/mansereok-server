package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.ReviewFixture;
import com.mansereok.server.support.fixture.UserFixture;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/**
 * 리뷰 삭제가 실제 DB 의 is_deleted 에 반영되는지 확인한다.
 *
 * <p>deleteReview 는 save 를 따로 부르지 않고 트랜잭션 안에서 조회한 엔티티의 값만 바꾼다. 그 값이 커밋 때 DB 에 쓰이는지는
 * 트랜잭션 설정(메서드의 {@code @Transactional}, 클래스의 readOnly)에 달려 있어 목으로는 확인할 수 없다.
 *
 * <p>모든 행은 이번 실행의 runId 로 만든 아이디, 주문 번호, 상품 번호로 만들고, 뒤 정리에서 그 행만 지운다. 목록 확인도 이번
 * 실행의 상품 번호로 좁혀서, 같은 DB 에 있는 다른 리뷰를 읽지 않는다.
 */
class ReviewDeleteMySqlTest extends LocalMySqlTest {

	@Autowired
	private ReviewService reviewService;
	@Autowired
	private ReviewRepository reviewRepository;
	@Autowired
	private UserRepository userRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String adminUsername = "review_admin_" + runId;
	private final String memberUsername = "review_member_" + runId;
	// reviews.order_id 는 UNIQUE 라 실행마다 다른 번호를 쓴다. 실제 주문 번호와 겹치지 않게 큰 수에서 시작한다.
	private final long orderId = 9_000_000_000L + Long.parseLong(runId, 16);
	// 상품별 목록에 이번 실행의 리뷰만 나오게 상품 번호도 실행마다 다르게 쓴다. 주문 번호와 다른 수에서 시작해서, 쿼리가 두
	// 컬럼을 바꿔 써도 알아챌 수 있게 한다. reviews.sub_category_id 에는 외래 키가 없다.
	private final long subCategoryId = 8_000_000_000L + Long.parseLong(runId, 16);

	private Long reviewId;

	@BeforeEach
	void saveUsersAndReview() {
		saveUser(adminUsername, Role.ADMIN);
		User member = saveUser(memberUsername, Role.USER);
		reviewId = reviewRepository.save(ReviewFixture.review().forSaving()
			.userId(member.getId())
			.orderId(orderId)
			.subCategoryId(subCategoryId)
			.build()).getId();
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM reviews WHERE order_id = ?", orderId);
		jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", adminUsername, memberUsername);
	}

	@Test
	@DisplayName("관리자가 지우면 DB 의 is_deleted 가 true 로 바뀌고 상품별 리뷰 목록에서 빠진다")
	void adminDeleteIsWrittenToDatabase() {
		// given: 지우기 전에는 상품별 목록에 이 리뷰가 나온다
		assertThat(reviewService.getReviewsBySubCategory(subCategoryId))
			.as("삭제 전 상품별 리뷰 목록")
			.extracting(ReviewResponse::reviewId)
			.containsExactly(reviewId);

		// when
		reviewService.deleteReview(reviewId, adminUsername);

		// then
		assertThat(isDeletedInDatabase()).isTrue();
		assertThat(reviewService.getReviewsBySubCategory(subCategoryId))
			.as("삭제 후 상품별 리뷰 목록")
			.isEmpty();
	}

	@Test
	@DisplayName("관리자가 아닌 회원이 지우려 하면 AccessDeniedException 을 내고 DB 의 is_deleted 는 false 로 남는다")
	void memberDeleteLeavesDatabaseUnchanged() {
		// when & then
		assertThatThrownBy(() -> reviewService.deleteReview(reviewId, memberUsername))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("리뷰는 관리자만 삭제할 수 있습니다.");
		assertThat(isDeletedInDatabase()).isFalse();
	}

	private User saveUser(String username, Role role) {
		User user = User.create(username, "리뷰 테스트", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
		UserFixture.withRole(user, role);
		return userRepository.save(user);
	}

	private Boolean isDeletedInDatabase() {
		return jdbcTemplate.queryForObject("SELECT is_deleted FROM reviews WHERE id = ?", Boolean.class,
			reviewId);
	}
}
