package com.mansereok.server.domain.review.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.ReviewNotAllowedException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 주문으로 리뷰 작성이 한꺼번에 들어와도 리뷰가 한 건만 저장되고, 나머지 요청은 500 이 아니라 ALREADY_WRITTEN(409) 으로
 * 거절되는지 실제 MySQL 로 확인한다.
 *
 * <p>이미 쓴 리뷰가 있는지는 잠금 없이 확인하므로 동시에 온 요청은 둘 다 그 확인을 지나칠 수 있다. 그 뒤 한 건만 저장되게 막는 것은
 * reviews 의 UNIQUE(uk_reviews_order_id)이고, 그 위반이 Hibernate·스프링을 거쳐 어떤 예외가 되는지도 DB 와 방언에 달린 일이라
 * 목으로는 확인할 수 없다. 테스트 DB 는 ddl-auto: update 로 엔티티의 UNIQUE 가 걸려 있다. 없으면 테스트가 그 이유를 적고 실패한다.
 *
 * <p>모든 행은 이번 실행의 runId 를 넣은 아이디·주문 번호와 상품 번호로 만들고, 뒤 정리에서 그 행만 지운다.
 */
class ReviewConcurrencyMySqlTest extends LocalMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int SAME_ORDER_REQUEST_COUNT = 10;
	private static final String CONTENT = "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.";

	@Autowired
	private ReviewService reviewService;
	@Autowired
	private ReviewRepository reviewRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private Clock clock;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "review_concurrency_" + runId;
	private final String merchantUid = "order_review_concurrency_" + runId;
	// 실제 상품 번호와 겹치지 않게 큰 수에서 시작한다. orders·reviews 의 sub_category_id 에는 외래 키가 없다.
	private final long subCategoryId = 8_200_000_000L + Long.parseLong(runId, 16);

	private Long userId;
	private Long orderId;

	@BeforeEach
	void savePaidOrderOfMember() {
		userId = userRepository.save(User.create(username, "동시작성", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false)).getId();
		orderId = orderRepository.save(Order.create(merchantUid, userId, subCategoryId, 10000, 10000, null, null,
			OrderStatus.PAID, "동시작성", username + "@example.com")).getId();
		// 결제 시각은 결제 처리 코드만 채우므로 SQL 로 넣는다. 어제 결제해 리뷰 기한 안이다.
		jdbcTemplate.update("UPDATE orders SET paid_at = ? WHERE id = ?", LocalDateTime.now(clock).minusDays(1),
			orderId);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM reviews WHERE order_id = ?", orderId);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
	}

	@Test
	@DisplayName("같은 주문으로 리뷰 작성이 동시에 10번 오면 1번만 저장되고 9번은 ALREADY_WRITTEN 이며, DataIntegrityViolationException 은 새지 않고 reviews 에 한 행이 남는다")
	void sameOrderReviewsAtTheSameTime() {
		// given
		checkOrderIdIsUniqueInDatabase();

		// when
		List<CallResult<ReviewResponse>> results = ConcurrentCalls.runAtTheSameTime(SAME_ORDER_REQUEST_COUNT,
			() -> reviewService.createReview(username, request()));

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).filteredOn(CallResult::succeeded).as("저장된 요청").hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded()).as("거절된 요청")
			.hasSize(9)
			.allSatisfy(result -> assertThat(result.error())
				.as("거절은 모두 409 가 되는 ALREADY_WRITTEN 이어야 한다. DataIntegrityViolationException 이 새면 500 이 된다")
				.isInstanceOfSatisfying(ReviewNotAllowedException.class,
					e -> assertThat(e.getReason()).isEqualTo(RejectionReason.ALREADY_WRITTEN))
				.hasMessage("이미 해당 주문에 대한 리뷰를 작성하셨습니다."));

		// then: DB 에 남은 행은 JPA 캐시를 거치지 않고 SQL 로 본다
		assertThat(countReviewsOfOrder()).as("reviews 행").isEqualTo(1);
		Map<String, Object> saved = jdbcTemplate.queryForMap(
			"SELECT user_id, sub_category_id, user_email FROM reviews WHERE order_id = ?", orderId);
		assertThat(saved)
			.as("작성자 id 는 회원에서, 상품 id 는 주문에서 가져오고 이메일은 저장하지 않는다")
			.containsEntry("user_id", userId)
			.containsEntry("sub_category_id", subCategoryId)
			.containsEntry("user_email", null);
	}

	@Test
	@DisplayName("같은 주문의 리뷰를 다른 트랜잭션이 INSERT 하고 아직 커밋하지 않았으면, 이 요청은 쓴 리뷰가 없다고 보고 INSERT 에서 기다렸다가 그 트랜잭션이 커밋되면 UNIQUE 위반을 ALREADY_WRITTEN 으로 바꿔 거절한다")
	void requestWaitingOnUncommittedReviewIsRejectedAsAlreadyWritten() throws Exception {
		// given
		checkOrderIdIsUniqueInDatabase();
		CountDownLatch firstInserted = new CountDownLatch(1);
		CountDownLatch commitFirst = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// given: 먼저 온 요청처럼 같은 주문의 리뷰를 INSERT 하고, 래치를 풀 때까지 커밋하지 않는다(sleep 을 쓰지 않는다)
			Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					reviewRepository.saveAndFlush(ReviewFixture.review().forSaving()
						.userId(userId).orderId(orderId).subCategoryId(subCategoryId).build());
					firstInserted.countDown();
					awaitRelease(commitFirst);
				}));
			assertThat(firstInserted.await(10, SECONDS)).as("먼저 온 트랜잭션이 INSERT 했다").isTrue();

			// when: 이 요청이 reviews 의 잠금을 기다리기 시작한 것을 본 뒤에 먼저 온 트랜잭션을 커밋한다
			Future<ReviewResponse> second = executor.submit(() -> reviewService.createReview(username, request()));
			await().atMost(Duration.ofSeconds(10)).until(this::someoneWaitsForReviewsLock);
			commitFirst.countDown();
			first.get(10, SECONDS);
			Throwable thrown = catchThrowable(() -> second.get(10, SECONDS));

			// then
			assertThat(thrown).isInstanceOf(ExecutionException.class);
			assertThat(thrown.getCause())
				.as("UNIQUE 위반을 이미 쓴 리뷰로 알아보고 409 가 되는 ALREADY_WRITTEN 으로 바꿔야 한다")
				.isInstanceOfSatisfying(ReviewNotAllowedException.class,
					e -> assertThat(e.getReason()).isEqualTo(RejectionReason.ALREADY_WRITTEN))
				.hasMessage("이미 해당 주문에 대한 리뷰를 작성하셨습니다.")
				.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(countReviewsOfOrder()).as("reviews 행").isEqualTo(1);
		} finally {
			commitFirst.countDown();
			executor.shutdownNow();
		}
	}

	/**
	 * 이 테스트 DB 의 reviews 에서 잠금을 기다리는 요청이 있는지 본다. 같은 MySQL 서버의 다른 스키마에서 도는 테스트와 섞이지 않도록
	 * 스키마를 지금 연결된 것으로 좁힌다.
	 */
	private boolean someoneWaitsForReviewsLock() {
		Integer waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks"
			+ " WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'reviews' AND LOCK_STATUS = 'WAITING'",
			Integer.class);
		return waiting != null && waiting > 0;
	}

	/**
	 * 같은 주문의 두 번째 INSERT 를 막는 것은 이 UNIQUE 하나뿐이다. 없으면 동시 제출이 둘 다 저장되어 테스트가 엉뚱한 곳에서 실패하므로,
	 * 먼저 확인하고 이유를 적어 실패한다.
	 */
	private void checkOrderIdIsUniqueInDatabase() {
		Integer uniqueIndexes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS"
			+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'uk_reviews_order_id'"
			+ " AND COLUMN_NAME = 'order_id' AND NON_UNIQUE = 0", Integer.class);
		assertThat(uniqueIndexes)
			.as("테스트 DB 의 reviews.order_id 에 UNIQUE(uk_reviews_order_id)가 없다. 없으면 같은 주문으로 동시에 온 리뷰가 모두"
				+ " 저장된다. Review 의 @Table 선언을 확인하고, 이름이 다른 옛 인덱스가 있으면 reviews 표를 지운 뒤 다시 돌린다")
			.isEqualTo(1);
	}

	private int countReviewsOfOrder() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reviews WHERE order_id = ?",
			Integer.class, orderId);
		return count == null ? 0 : count;
	}

	private ReviewCreateRequest request() {
		ReviewCreateRequest request = new ReviewCreateRequest();
		request.setOrderId(orderId);
		request.setSubCategoryId(subCategoryId);
		request.setContent(CONTENT);
		return request;
	}

	private static void awaitRelease(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, SECONDS)).as("커밋 신호를 10초 안에 받았다").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("커밋 신호를 기다리다 중단됐다.", e);
		}
	}
}
