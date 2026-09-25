package com.mansereok.server.domain.user.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;

import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.ReviewFixture;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴를 실제 MySQL 로 확인한다.
 *
 * <ol>
 *   <li>재설정 토큰·리프레시 토큰·리뷰·사주 결과가 있는 회원도 탈퇴되고, 그 회원을 가리키던 행이 모두 지워진다. 다른 회원의 리뷰는
 *   남는다. password_reset_tokens·refresh_tokens 의 외래 키는 실제 DB 에만 있어 목으로는 볼 수 없다.</li>
 *   <li>탈퇴 알림은 커밋 뒤에 나간다. 알림을 보내는 동안 다른 트랜잭션이 탈퇴 결과를 보고, 탈퇴한 회원의 주문 행을 기다리지 않고
 *   잠근다.</li>
 * </ol>
 *
 * <p>모든 행은 이번 실행의 runId 로 만든 이메일, 주문 번호, 리뷰의 주문 번호로 만들고, 뒤 정리에서 그 행만 지운다.
 */
class WithdrawalMySqlTest extends LocalMySqlTest {

	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private RefreshTokenRepository refreshTokenRepository;
	@Autowired
	private ResultRepository resultRepository;
	@Autowired
	private ReviewRepository reviewRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String withdrawingEmail = "withdraw-" + runId + "@example.com";
	private final String stayingEmail = "stay-" + runId + "@example.com";
	private final String merchantUid = "order_withdraw_" + runId;
	// reviews.order_id 는 UNIQUE 라 실행마다 다른 번호를 쓴다. 실제 주문 번호와 겹치지 않게 큰 수에서 시작한다.
	private final long withdrawingReviewOrderId = 9_100_000_000L + Long.parseLong(runId, 16);
	private final long stayingReviewOrderId = 9_200_000_000L + Long.parseLong(runId, 16);

	private Long withdrawingUserId;
	private Long stayingUserId;

	@BeforeEach
	void saveMembers() {
		withdrawingUserId = saveEmailSignupMember(withdrawingEmail, "탈퇴회원").getId();
		stayingUserId = saveEmailSignupMember(stayingEmail, "남는회원").getId();
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE user_id IN (?, ?)", withdrawingUserId,
			stayingUserId);
		jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id IN (?, ?)", withdrawingUserId, stayingUserId);
		jdbcTemplate.update("DELETE FROM results WHERE user_id IN (?, ?)", withdrawingUserId, stayingUserId);
		jdbcTemplate.update("DELETE FROM reviews WHERE order_id IN (?, ?)", withdrawingReviewOrderId,
			stayingReviewOrderId);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", withdrawingUserId, stayingUserId);
	}

	@Test
	@DisplayName("재설정 토큰·리프레시 토큰·리뷰·사주 결과가 있는 회원도 탈퇴되고 그 회원을 가리키던 행은 모두 지워지며, 다른 회원의 리뷰는 남는다")
	void withdrawsMemberWithResetTokenAndDeletesRowsPointingToMember() {
		// given: 비밀번호 재설정 메일을 요청하고 링크는 쓰지 않은 회원
		userService.requestPasswordReset(withdrawingEmail);
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?",
			withdrawingUserId)).as("탈퇴 전 재설정 토큰").isEqualTo(1);
		User withdrawing = userRepository.findById(withdrawingUserId).orElseThrow();
		refreshTokenRepository.save(new RefreshToken("withdraw-refresh-" + runId, withdrawing,
			LocalDateTime.now().plusDays(7)));
		resultRepository.save(Result.createInitial(withdrawingUserId, null, "인생 총운"));
		reviewRepository.save(ReviewFixture.review().forSaving()
			.userId(withdrawingUserId).orderId(withdrawingReviewOrderId).userEmail(withdrawingEmail).build());
		reviewRepository.save(ReviewFixture.review().forSaving()
			.userId(stayingUserId).orderId(stayingReviewOrderId).userEmail(stayingEmail).build());

		// when
		userService.deleteUser(withdrawingEmail);

		// then: DB 에 남은 행은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM users WHERE id = ?", withdrawingUserId))
			.as("탈퇴 회원 행").isZero();
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?",
			withdrawingUserId)).as("재설정 토큰").isZero();
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", withdrawingUserId))
			.as("리프레시 토큰").isZero();
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM results WHERE user_id = ?", withdrawingUserId))
			.as("사주 결과").isZero();
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM reviews WHERE user_id = ?", withdrawingUserId))
			.as("탈퇴 회원의 리뷰(이름·이메일 사본)").isZero();
		assertThat(countRowsOfUser("SELECT COUNT(*) FROM reviews WHERE user_id = ?", stayingUserId))
			.as("다른 회원의 리뷰").isEqualTo(1);

		// then: 탈퇴 알림은 커밋 뒤에 한 번 간다
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(discordNotificationService).should().sendUserWithdrawnNotification("탈퇴회원", withdrawingEmail));
	}

	@Test
	@DisplayName("탈퇴 알림을 보내는 동안에는 탈퇴가 이미 커밋되어, 다른 트랜잭션이 사용자 행이 없는 것을 보고 그 회원의 주문 행을 1초 안에 잠근다")
	void withdrawalNotificationIsSentAfterCommit() throws Exception {
		// given: 탈퇴 회원의 주문. 탈퇴는 이 주문의 user_id 를 NULL 로 바꾸며 행을 잠근다.
		orderRepository.save(Order.create(merchantUid, withdrawingUserId, 1L, 10000, 10000, null, null,
			OrderStatus.PENDING, "탈퇴회원", withdrawingEmail));
		// given: 탈퇴 알림을 래치에서 붙잡아, 알림을 보내는 순간에 멈춰 세운다(sleep 을 쓰지 않는다)
		CountDownLatch notificationStarted = new CountDownLatch(1);
		CountDownLatch releaseNotification = new CountDownLatch(1);
		willAnswer(invocation -> {
			notificationStarted.countDown();
			releaseNotification.await(10, SECONDS);
			return null;
		}).given(discordNotificationService).sendUserWithdrawnNotification("탈퇴회원", withdrawingEmail);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> withdrawal = executor.submit(() -> userService.deleteUser(withdrawingEmail));
			assertThat(notificationStarted.await(10, SECONDS)).as("탈퇴 알림을 보내기 시작했다").isTrue();

			// when & then: 알림이 멈춰 있는 동안 다른 커넥션에서 본다
			assertThat(countRowsOfUser("SELECT COUNT(*) FROM users WHERE id = ?", withdrawingUserId))
				.as("다른 트랜잭션이 본 탈퇴 회원 행(커밋 전이면 1)").isZero();
			Order lockedOrder = lockOrderWaitingAtMostOneSecond();
			assertThat(lockedOrder.getUserId()).as("잠근 주문의 사용자 연결").isNull();

			releaseNotification.countDown();
			withdrawal.get(10, SECONDS);
		} finally {
			releaseNotification.countDown();
			executor.shutdownNow();
		}
	}

	private User saveEmailSignupMember(String email, String name) {
		return userRepository.save(User.create(email, name, "encoded-password", email, LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false));
	}

	private int countRowsOfUser(String sql, Long userId) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId);
		return count == null ? 0 : count;
	}

	/**
	 * 잠금 대기 한도를 1초로 줄인 트랜잭션에서 주문 행을 잠근다. 다른 트랜잭션이 이 행을 쥐고 있으면 1초 뒤
	 * PessimisticLockingFailureException 계열로 실패한다(LockTimeoutTranslationMySqlTest). 한도는 커넥션에 남으므로 같은
	 * 커넥션에서 원래 값으로 되돌린 뒤 풀에 돌려준다.
	 */
	private Order lockOrderWaitingAtMostOneSecond() {
		return new TransactionTemplate(transactionManager).execute(status -> {
			jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
			try {
				return orderRepository.findByMerchantUidWithLock(merchantUid).orElseThrow();
			} finally {
				jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
			}
		});
	}
}
