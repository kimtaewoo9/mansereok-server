package com.mansereok.server.domain.user.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.auth.PasswordResetTokenRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 재설정의 요청·확인이 겹칠 때 DB 에 남는 결과를 실제 MySQL 로 확인한다.
 *
 * <ol>
 *   <li>같은 회원의 재설정 요청이 한꺼번에 와도 users 행 잠금으로 한 줄로 서서 예외 없이 끝나고, 토큰 행은 하나다. 잠금과
 *   password_reset_tokens.user_id 의 UNIQUE, 격리 수준(READ COMMITTED)이 함께 만드는 결과라 목으로는 볼 수 없다. 토큰이 남은
 *   회원이 다시 요청하면 같은 행의 값만 바꿔 UNIQUE 에 걸리지 않는다.</li>
 *   <li>재설정 메일은 토큰이 커밋된 뒤에 나간다.</li>
 *   <li>같은 토큰으로 확인이 한꺼번에 와도 조건부 DELETE 가 1 인 한 요청만 비밀번호를 바꾸고, 그 회원의 리프레시 토큰은 모두
 *   폐기된다. 확인이 옛 토큰을 읽은 뒤 재요청이 토큰 값을 바꾸면 옛 토큰으로는 바꾸지 못한다.</li>
 *   <li>사용자 행을 읽고 이름만 바꾼 트랜잭션이 재설정보다 늦게 커밋해도 새 비밀번호가 되돌아가지 않는다(User 의 바뀐 컬럼만 쓰는
 *   UPDATE).</li>
 * </ol>
 *
 * <p>모든 행은 이번 실행의 runId 를 넣은 이메일의 회원으로 만들고, 뒤 정리에서 그 회원의 행만 지운다.
 */
class PasswordResetMySqlTest extends LocalMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int SAME_MEMBER_REQUEST_COUNT = 5;

	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private RefreshTokenRepository refreshTokenRepository;
	@Autowired
	private PasswordResetTokenRepository passwordResetTokenRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "reset-" + runId + "@example.com";

	private Long userId;

	@BeforeEach
	void saveEmailSignupMember() {
		userId = userRepository.save(User.create(email, "재설정회원", passwordEncoder.encode("old-password"), email,
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false)).getId();
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		// users 를 외래 키로 가리키므로 users 행보다 먼저 지운다
		jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
	}

	@Test
	@DisplayName("토큰이 없는 회원의 재설정 요청이 동시에 5번 와도 모두 예외 없이 끝나고, 토큰 행은 하나이며, 메일로 나간 토큰 5개는 서로 다르고 그중 DB 에 남은 하나만 쓸 수 있다")
	void sameMemberRequestsAtTheSameTime() {
		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(SAME_MEMBER_REQUEST_COUNT, () -> {
			userService.requestPasswordReset(email);
			return null;
		});

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).hasSize(SAME_MEMBER_REQUEST_COUNT)
			.allSatisfy(result -> assertThat(result.error()).as("요청이 예외 없이 끝나야 한다").isNull());

		// then: DB 에 남은 행은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?"))
			.as("재설정 토큰 행").isEqualTo(1);

		// then: 요청마다 메일이 한 통씩 커밋 뒤에 나간다. 앞 요청의 토큰은 뒤 요청이 새 값으로 바꿨으므로 DB 에 없다.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(emailService).should(times(SAME_MEMBER_REQUEST_COUNT)).sendPasswordResetEmail(eq(email), anyString()));
		ArgumentCaptor<String> mailedTokens = ArgumentCaptor.forClass(String.class);
		then(emailService).should(times(SAME_MEMBER_REQUEST_COUNT))
			.sendPasswordResetEmail(eq(email), mailedTokens.capture());
		assertThat(mailedTokens.getAllValues())
			.as("메일로 나간 토큰")
			.doesNotHaveDuplicates()
			.containsOnlyOnce(storedToken());
	}

	@Test
	@DisplayName("토큰이 남은 회원이 다시 요청하면 user_id UNIQUE 에 걸리지 않고 같은 행의 토큰 값만 바뀐다")
	void requestAgainReplacesTokenInSameRow() {
		// given
		userService.requestPasswordReset(email);
		Long firstRowId = jdbcTemplate.queryForObject("SELECT id FROM password_reset_tokens WHERE user_id = ?",
			Long.class, userId);
		String firstToken = storedToken();

		// when
		userService.requestPasswordReset(email);

		// then
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?"))
			.as("재설정 토큰 행").isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM password_reset_tokens WHERE user_id = ?", Long.class,
			userId)).as("지우고 넣지 않고 같은 행을 고친다").isEqualTo(firstRowId);
		assertThat(storedToken()).as("앞서 보낸 링크의 토큰 값").isNotEqualTo(firstToken);
	}

	@Test
	@DisplayName("재설정 메일을 보내는 동안에는 토큰이 이미 커밋되어, 다른 트랜잭션이 메일에 담긴 토큰 행을 본다")
	void resetMailIsSentAfterCommit() throws Exception {
		// given: 재설정 메일을 래치에서 붙잡아, 메일을 보내는 순간에 멈춰 세운다(sleep 을 쓰지 않는다)
		CountDownLatch mailStarted = new CountDownLatch(1);
		CountDownLatch releaseMail = new CountDownLatch(1);
		AtomicReference<String> mailedToken = new AtomicReference<>();
		willAnswer(invocation -> {
			mailedToken.set(invocation.getArgument(1));
			mailStarted.countDown();
			releaseMail.await(10, SECONDS);
			return null;
		}).given(emailService).sendPasswordResetEmail(eq(email), anyString());

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> request = executor.submit(() -> userService.requestPasswordReset(email));
			assertThat(mailStarted.await(10, SECONDS)).as("재설정 메일을 보내기 시작했다").isTrue();

			// when & then: 메일이 멈춰 있는 동안 다른 커넥션에서 본다. 커밋 전이면 0 이다.
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE token = ?",
				Integer.class, mailedToken.get())).as("다른 트랜잭션이 본, 메일에 담긴 토큰 행").isEqualTo(1);

			releaseMail.countDown();
			request.get(10, SECONDS);
		} finally {
			releaseMail.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("같은 토큰으로 재설정이 동시에 2번 오면 1번만 비밀번호를 바꾸고 다른 1번은 400 이며, 회원의 리프레시 토큰은 모두 폐기되고 재설정 토큰 행은 남지 않는다")
	void sameTokenConfirmedAtTheSameTime() {
		// given: 메일로 받은 토큰과, 두 기기에서 로그인해 받은 리프레시 토큰 두 개
		userService.requestPasswordReset(email);
		String token = storedToken();
		saveRefreshToken("reset-refresh-a-" + runId);
		saveRefreshToken("reset-refresh-b-" + runId);

		// when: 요청마다 다른 새 비밀번호를 보낸다
		List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(2, index -> () -> {
			String newPassword = "new-password-" + index;
			userService.resetPassword(token, newPassword);
			return newPassword;
		});

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).filteredOn(CallResult::succeeded).as("비밀번호를 바꾼 요청").hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded()).as("거절된 요청")
			.singleElement()
			.satisfies(result -> assertThat(result.error())
				.as("500 이 되는 잠금·행 수 예외가 아니라 400 이 되는 IllegalArgumentException 이어야 한다")
				.isInstanceOf(IllegalArgumentException.class)
				// 보통은 토큰을 읽은 뒤 조건부 DELETE 가 0 이라 앞 메시지다. 뒤 요청의 스레드가 늦게 출발해 앞 요청이 커밋한 뒤에야
				// 토큰을 읽으면 토큰이 이미 없어 뒤 메시지다. 둘 다 같은 토큰을 두 번 쓰지 못하게 막은 결과다.
				.extracting(Throwable::getMessage)
				.isIn("이미 사용되었거나 만료된 토큰입니다.", "유효하지 않은 토큰입니다."));

		String winnerPassword = results.stream().filter(CallResult::succeeded).map(CallResult::value)
			.findFirst().orElseThrow();
		assertThat(passwordEncoder.matches(winnerPassword, storedPasswordHash()))
			.as("DB 의 비밀번호가 성공한 요청의 새 비밀번호다").isTrue();
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?"))
			.as("쓴 재설정 토큰 행").isZero();
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 리프레시 토큰").isZero();
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?"))
			.as("폐기는 행을 지우지 않고 표시만 한다").isEqualTo(2);
	}

	@Test
	@DisplayName("재설정이 옛 토큰을 읽은 뒤 같은 회원의 재요청이 토큰 값을 바꾸고 커밋하면, 옛 토큰으로는 비밀번호를 바꾸지 못하고 새 토큰은 남는다")
	void oldTokenReadBeforeReissueCannotResetPassword() throws Exception {
		// given
		userService.requestPasswordReset(email);
		String oldToken = storedToken();
		TransactionTemplate resetTransaction = new TransactionTemplate(transactionManager);

		// when: 재설정 트랜잭션이 옛 토큰을 읽은 상태에서, 다른 스레드의 재요청이 같은 행의 토큰 값을 바꾸고 커밋한다.
		// 그다음 같은 트랜잭션에서 재설정을 이어 간다. 재설정은 이 트랜잭션에 합류하므로 토큰을 읽을 때 재요청 전의 스냅숏을 본다.
		Throwable rejected = catchThrowable(() -> resetTransaction.executeWithoutResult(status -> {
			assertThat(passwordResetTokenRepository.findByToken(oldToken)).as("재요청 전에 읽은 옛 토큰").isPresent();
			runInOtherThread(() -> userService.requestPasswordReset(email));
			userService.resetPassword(oldToken, "new-password");
		}));

		// then: 행 id 는 같아도 토큰 값이 바뀌었으므로 조건부 DELETE 가 0 이다
		assertThat(rejected)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("이미 사용되었거나 만료된 토큰입니다.");
		assertThat(passwordEncoder.matches("old-password", storedPasswordHash()))
			.as("비밀번호는 그대로다").isTrue();
		assertThat(storedToken()).as("재요청으로 새로 보낸 링크의 토큰").isNotEqualTo(oldToken);
	}

	@Test
	@DisplayName("사용자 행을 읽고 이름만 바꾼 트랜잭션이 그사이 커밋된 재설정보다 늦게 커밋해도 새 비밀번호와 새 이름이 모두 남는다")
	void nameUpdateCommittedAfterResetKeepsNewPassword() throws Exception {
		// given
		userService.requestPasswordReset(email);
		String token = storedToken();
		CountDownLatch nameChanged = new CountDownLatch(1);
		CountDownLatch releaseNameUpdate = new CountDownLatch(1);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			// 프로필 수정(updateUserProfile)과 같은 모양의 트랜잭션: 사용자 행을 읽고 이름을 바꾼 뒤 커밋 전에 멈춘다.
			// UPDATE 는 커밋할 때 나가므로, 멈춰 있는 동안에는 사용자 행을 잠그지 않는다.
			Future<?> nameUpdate = executor.submit(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					User member = userRepository.findById(userId).orElseThrow();
					member.setName("새이름");
					nameChanged.countDown();
					waitUpToTenSeconds(releaseNameUpdate);
				}));
			assertThat(nameChanged.await(10, SECONDS)).as("이름 수정이 사용자 행을 읽고 이름을 바꿨다").isTrue();

			// when: 이름 수정이 멈춰 있는 동안 재설정을 끝까지 커밋하고, 그다음 이름 수정을 커밋한다
			userService.resetPassword(token, "new-password");
			releaseNameUpdate.countDown();
			nameUpdate.get(10, SECONDS);
		} finally {
			releaseNameUpdate.countDown();
			executor.shutdownNow();
		}

		// then
		assertThat(passwordEncoder.matches("new-password", storedPasswordHash()))
			.as("이름 수정이 읽어 둔 옛 비밀번호 해시로 되돌아가지 않는다").isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = ?", String.class, userId))
			.isEqualTo("새이름");
	}

	private String storedToken() {
		return jdbcTemplate.queryForObject("SELECT token FROM password_reset_tokens WHERE user_id = ?", String.class,
			userId);
	}

	private String storedPasswordHash() {
		return jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, userId);
	}

	private int countRowsOfMember(String sql) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId);
		return count == null ? 0 : count;
	}

	private void saveRefreshToken(String value) {
		User member = userRepository.findById(userId).orElseThrow();
		refreshTokenRepository.save(new RefreshToken(value, member, LocalDateTime.now().plusDays(7)));
	}

	/**
	 * 다른 스레드(다른 커넥션·트랜잭션)에서 작업을 끝까지 돌리고 기다린다. 작업이 던진 예외는 그대로 다시 던진다.
	 */
	private static void runInOtherThread(Runnable task) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			executor.submit(task).get(10, SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("다른 스레드의 작업이 실패했다", e);
		} finally {
			executor.shutdownNow();
		}
	}

	private static void waitUpToTenSeconds(CountDownLatch latch) {
		try {
			if (!latch.await(10, SECONDS)) {
				throw new IllegalStateException("10초 안에 풀리지 않았다");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
