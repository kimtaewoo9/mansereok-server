package com.mansereok.server.domain.user.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.scheduler.RefreshTokenCleanupScheduler;
import com.mansereok.server.global.exception.InvalidRefreshTokenException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.LocalMySqlTest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리프레시 토큰의 발급·재발급·로그아웃·정리가 겹칠 때 DB 에 남는 결과를 실제 MySQL 로 확인한다.
 *
 * <ol>
 *   <li>같은 토큰으로 재발급이 한꺼번에 와도 조건부 UPDATE 로 "썼음" 표시는 한 번만 되고, 나머지는 잠가 다시 읽어 유예 시간 안이면 새
 *   토큰을 받는다. 유예 시간이 0 이면 한 요청만 성공한다. 트랜잭션이 끝난 뒤에도 돌려받은 회원의 값을 지연 로딩 없이 읽는다.
 *   쓰지 않은 토큰이라도 만료됐으면 조건부 UPDATE 가 표시하지 않아 만료 사유로 거절되고 새 토큰이 생기지 않는다.</li>
 *   <li>로그아웃과 재발급이 겹쳐도 500 이 되는 예외가 없고, 끝난 뒤 그 회원에게 쓸 수 있는 토큰이 남지 않는다. 유예 시간 안의 재발급이
 *   토큰을 잠근 동안 온 로그아웃은 그 재발급의 커밋을 기다렸다가 새 토큰까지 폐기한다. 커넥션의 기본 격리 수준이 READ COMMITTED 여도
 *   같다. 두 기기가 쓴 토큰으로 동시에 로그아웃해도 교착이 나지 않는다. 리프레시 토큰 리포지토리를 스파이로 바꿔 한쪽을 잠금을 쥔 채
 *   멈춰 세운다.</li>
 *   <li>재발급으로 받은 새 토큰으로 로그아웃하면 그 새 토큰을 낸 옛 토큰과, 옛 토큰으로 유예 시간 안에 받은 다른 새 토큰도 폐기된다.
 *   옛 쿠키를 싣고 늦게 온 재발급이 옛 토큰을 잠근 동안 로그아웃하면, 로그아웃은 그 재발급의 커밋을 기다렸다가 그 새 토큰까지
 *   폐기한다.</li>
 *   <li>한 회원의 두 기기 토큰은 서로를 지우지 않는다.</li>
 *   <li>토큰이 없는 신규 회원들이 동시에 로그인해도 교착이 나지 않는다. 예전 발급 방식(user_id 로 지운 뒤 넣기)은 같은 상황에서 교착이
 *   났다는 것도 재현해 둔다.</li>
 *   <li>정리 작업은 만료된 토큰과 쓴 지 오래된 토큰만 지운다.</li>
 * </ol>
 *
 * <p>모든 회원은 이번 실행의 runId 를 넣은 이메일로 만들고, 뒤 정리에서 그 회원들의 행만 지운다. 정리 작업 테스트만은 표 전체에서
 * 만료·오래 쓴 토큰을 지운다. 그런 토큰은 어느 테스트에서도 더 쓰지 않는 행이다.
 */
class RefreshTokenMySqlTest extends LocalMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int SAME_TOKEN_REQUEST_COUNT = 10;
	private static final int NEW_MEMBER_COUNT = 10;
	// MySQL 이 교착을 알아채고 한쪽을 롤백할 때 주는 오류 번호
	private static final int MYSQL_DEADLOCK = 1213;
	// 확실히 지난 시각. 서버와 JVM 의 시간대가 달라도 지난 시각이다.
	private static final LocalDateTime LONG_AGO = LocalDateTime.of(2000, 1, 1, 0, 0);

	@Autowired
	private RefreshTokenService refreshTokenService;
	// 재발급·로그아웃을 잠금을 쥔 채 멈춰 세우려고 스파이로 둔다. 멈추게 하지 않은 호출은 실제 리포지토리로 간다.
	// PasswordResetMySqlTest 와 같은 목 조합이라 스프링 컨텍스트를 함께 쓴다.
	@MockitoSpyBean
	private RefreshTokenRepository refreshTokenRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private RefreshTokenCleanupScheduler refreshTokenCleanupScheduler;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "refresh-" + runId + "@example.com";
	// 이번 실행에서 만든 모든 회원. 뒤 정리에서 이 회원들의 행만 지운다.
	private final List<Long> memberIdsOfThisRun = new ArrayList<>();

	private User member;

	@BeforeEach
	void saveMember() {
		member = saveMember(email);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		// users 를 외래 키로 가리키므로 users 행보다 먼저 지운다
		for (Long memberId : memberIdsOfThisRun) {
			jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", memberId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
		}
	}

	@Test
	@DisplayName("같은 토큰으로 재발급이 동시에 10번 와도 유예 시간(기본 10초) 안이라 모두 성공하고, 받은 새 토큰 10개는 모두 DB 에서 쓸 수 있는 상태다")
	void sameTokenRotatedAtTheSameTimeAllSucceedWithinGrace() {
		// given
		String presented = refreshTokenService.issue(member);

		// when
		List<CallResult<RotatedRefreshToken>> results = ConcurrentCalls.runAtTheSameTime(SAME_TOKEN_REQUEST_COUNT,
			() -> refreshTokenService.rotate(presented));

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).hasSize(SAME_TOKEN_REQUEST_COUNT)
			.allSatisfy(result -> assertThat(result.error()).as("요청이 예외 없이 끝나야 한다").isNull());
		List<String> newTokens = results.stream().map(result -> result.value().token()).toList();
		assertThat(newTokens).doesNotHaveDuplicates().doesNotContain(presented);
		// then: 트랜잭션이 끝난 뒤 이 스레드에서 회원 값을 꺼낸다. 지연 로딩이 남아 있으면 LazyInitializationException 이 난다.
		assertThat(results).extracting(result -> result.value().user().getEmail()).containsOnly(email);

		// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 본다
		assertThat(countUsableTokens(newTokens)).as("쓸 수 있는 새 토큰").isEqualTo(SAME_TOKEN_REQUEST_COUNT);
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND used_at IS NOT NULL"))
			.as("쓴 토큰(처음 낸 토큰)").isEqualTo(1);
	}

	@Nested
	@TestPropertySource(properties = "app.auth.refresh-token.reuse-grace=0s")
	@DisplayName("유예 시간이 0초면")
	class WhenReuseGraceIsZero {

		// 이 중첩 클래스의 스프링 컨텍스트(유예 0초)에서 받은 서비스. 바깥 클래스의 refreshTokenService 는 유예 10초다.
		@Autowired
		private RefreshTokenService refreshTokenServiceWithoutGrace;

		@Test
		@DisplayName("같은 토큰으로 재발급이 동시에 10번 와도 1번만 성공하고 9번은 이미 사용한 토큰으로 거절되며, 쓴 표시와 새 토큰은 한 번씩만 생긴다")
		void onlyOneOfSameTokenRotationsSucceeds() {
			// given
			String presented = refreshTokenService.issue(member);

			// when
			List<CallResult<RotatedRefreshToken>> results = ConcurrentCalls.runAtTheSameTime(
				SAME_TOKEN_REQUEST_COUNT, () -> refreshTokenServiceWithoutGrace.rotate(presented));

			// then
			assertThat(results).filteredOn(CallResult::succeeded).as("새 토큰을 받은 요청").hasSize(1);
			assertThat(results).filteredOn(result -> !result.succeeded()).as("거절된 요청")
				.hasSize(SAME_TOKEN_REQUEST_COUNT - 1)
				.allSatisfy(result -> assertThat(result.error())
					.as("500 이 되는 잠금·행 수 예외가 아니라 401 이 되는 InvalidRefreshTokenException 이어야 한다")
					.isInstanceOf(InvalidRefreshTokenException.class)
					.hasMessage("이미 사용한 리프레시 토큰입니다. 다시 로그인해주세요."));
			assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?"))
				.as("처음 낸 토큰과 새 토큰 하나").isEqualTo(2);
			assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND used_at IS NULL"))
				.as("쓰지 않은 토큰(새 토큰)").isEqualTo(1);
		}
	}

	// 쓰지 않고 폐기되지 않은 토큰의 만료는 조건부 UPDATE(markUsedIfUsable)의 만료 조건만 거른다. 이 조건이 빠지면 UPDATE 가 1 을
	// 돌려줘 잠가 다시 읽는 만료 검사까지 가지 않고 새 토큰이 나간다. 단위 테스트는 UPDATE 결과를 0 으로 정해 두므로 이 조건을 보지 못한다.
	@Test
	@DisplayName("쓰지 않은 토큰이라도 만료 시각이 지났으면 재발급을 만료 사유로 거절하고, 새 토큰을 넣지 않으며 그 토큰을 쓴 것으로 표시하지 않는다")
	void expiredUnusedTokenIsRejectedWithoutNewToken() {
		// given: 로그인으로 받은 토큰의 만료 시각만 지난 시각으로 옮긴다
		String expired = refreshTokenService.issue(member);
		jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE token = ?", LONG_AGO, expired);

		// when & then
		assertThatThrownBy(() -> refreshTokenService.rotate(expired))
			.isInstanceOf(InvalidRefreshTokenException.class)
			.hasMessage("만료된 리프레시 토큰입니다. 다시 로그인해주세요.");
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?"))
			.as("만료된 토큰 하나뿐이고 새 토큰은 없다").isEqualTo(1);
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND used_at IS NULL"))
			.as("쓴 것으로 표시되지 않은 토큰(만료된 토큰)").isEqualTo(1);
	}

	// 두 스레드를 한 순간에 출발시켜도 어느 쪽이 먼저 잠그는지는 스레드를 만든 순서에 크게 치우친다. 로그아웃을 먼저 만든 20회에서는
	// 재발급이 한 번도 먼저 커밋하지 않아, 로그아웃이 재발급의 새 토큰까지 폐기하는 순서를 보지 못했다. 그래서 두 순서를 10번씩 돌린다.
	// 새 토큰 폐기를 지웠을 때 재발급을 먼저 만든 10회 중 2회가 실패했다. 재발급이 먼저 커밋한 뒤의 로그아웃을 늘 재현하는 확인은 아래
	// 잠금 대기 테스트와 두 기기 로그아웃 테스트가 맡는다.
	@RepeatedTest(value = 10, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 토큰으로 로그아웃과 재발급이 동시에 와도(로그아웃 스레드를 먼저 만듦) 500 이 되는 예외 없이 끝나고, 끝난 뒤 그 회원에게 쓸 수 있는 토큰이 남지 않는다")
	void logoutStartedFirstAndRotationLeaveNoUsableToken() {
		// given
		String presented = refreshTokenService.issue(member);

		// when
		List<CallResult<Object>> results = ConcurrentCalls.runAtTheSameTime(2,
			List.of(logoutOf(presented), rotationOf(presented))::get);

		// then
		assertLogoutAndRotationEndedWithoutServerError(results.get(0), results.get(1));
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@RepeatedTest(value = 10, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 토큰으로 재발급과 로그아웃이 동시에 와도(재발급 스레드를 먼저 만듦) 500 이 되는 예외 없이 끝나고, 재발급이 먼저 커밋했다면 그 새 토큰까지 폐기되어 쓸 수 있는 토큰이 남지 않는다")
	void rotationStartedFirstAndLogoutLeaveNoUsableToken() {
		// given
		String presented = refreshTokenService.issue(member);

		// when
		List<CallResult<Object>> results = ConcurrentCalls.runAtTheSameTime(2,
			List.of(rotationOf(presented), logoutOf(presented))::get);

		// then
		assertLogoutAndRotationEndedWithoutServerError(results.get(1), results.get(0));
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Test
	@DisplayName("유예 시간 안의 재발급이 쓴 토큰을 잠가 읽은 뒤 새 토큰을 넣기 전에 같은 토큰으로 로그아웃하면, 로그아웃은 재발급의 커밋을 기다렸다가 그 새 토큰까지 폐기한다")
	void logoutWaitsForRotationWithinGraceAndRevokesItsNewToken() throws Exception {
		// given: 한 번 재발급해 이미 쓴 토큰
		String presented = refreshTokenService.issue(member);
		refreshTokenService.rotate(presented);

		// when
		String newTokenOfPausedRotation = rotateAgainWhileLogoutArrives(refreshTokenService, refreshTokenRepository,
			presented, presented);

		// then
		assertThat(isRevoked(newTokenOfPausedRotation)).as("로그아웃 전에 커밋된 두 번째 재발급의 새 토큰").isTrue();
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Nested
	@TestPropertySource(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED")
	@DisplayName("커넥션의 기본 격리 수준이 READ COMMITTED 면")
	class WhenDefaultIsolationIsReadCommitted {

		// 이 중첩 클래스의 스프링 컨텍스트(커넥션 기본 격리 수준 READ COMMITTED)에서 받은 서비스와 리포지토리 스파이.
		// READ COMMITTED 에서는 0 행을 고친 조건부 UPDATE 가 토큰 행의 잠금을 바로 풀어, 재발급이 FOR UPDATE 로 다시 잠가야만
		// 로그아웃이 재발급의 커밋을 기다린다. 바깥 클래스(REPEATABLE READ)에서는 FOR UPDATE 를 빼도 조건부 UPDATE 의 잠금이 남아
		// 같은 테스트가 통과한다.
		@Autowired
		private RefreshTokenService readCommittedRefreshTokenService;
		@Autowired
		private RefreshTokenRepository readCommittedRefreshTokenRepository;

		@Test
		@DisplayName("유예 시간 안의 재발급이 쓴 토큰을 잠가 읽은 뒤 새 토큰을 넣기 전에 같은 토큰으로 로그아웃해도, 로그아웃은 재발급의 커밋을 기다렸다가 그 새 토큰까지 폐기한다")
		void logoutWaitsForRotationWithinGraceAndRevokesItsNewToken() throws Exception {
			// given: 한 번 재발급해 이미 쓴 토큰
			String presented = readCommittedRefreshTokenService.issue(member);
			readCommittedRefreshTokenService.rotate(presented);

			// when
			String newTokenOfPausedRotation = rotateAgainWhileLogoutArrives(readCommittedRefreshTokenService,
				readCommittedRefreshTokenRepository, presented, presented);

			// then
			assertThat(isRevoked(newTokenOfPausedRotation)).as("로그아웃 전에 커밋된 두 번째 재발급의 새 토큰").isTrue();
			assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
				.as("폐기되지 않은 토큰").isZero();
		}
	}

	@Test
	@DisplayName("재발급으로 받은 새 토큰으로 로그아웃하면 그 새 토큰을 낸 옛 토큰도 폐기되어, 유예 시간(10초) 안이어도 옛 토큰으로 다시 재발급하지 못한다")
	void logoutWithNewTokenAlsoRevokesTheTokenItReplaced() {
		// given: 처음 받은 토큰을 재발급해 새 토큰을 받았다. 브라우저에는 새 토큰만 있다.
		String oldToken = refreshTokenService.issue(member);
		String newToken = refreshTokenService.rotate(oldToken).token();

		// when
		refreshTokenService.revoke(newToken);

		// then: 옛 쿠키를 싣고 늦게 온 재발급은 폐기 사유로 거절된다
		assertThat(isRevoked(oldToken)).as("새 토큰을 낸 옛 토큰").isTrue();
		assertThatThrownBy(() -> refreshTokenService.rotate(oldToken))
			.isInstanceOf(InvalidRefreshTokenException.class)
			.hasMessage("로그아웃했거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요.");
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Test
	@DisplayName("같은 토큰으로 유예 시간 안에 두 번 재발급해 받은 두 새 토큰 중 브라우저에 남은 쪽으로 로그아웃하면, 다른 쪽 새 토큰도 폐기되어 그 토큰으로 재발급하지 못한다")
	void logoutWithOneOfTwoTokensFromSameTokenAlsoRevokesTheOther() {
		// given: 같은 쿠키로 거의 동시에 온 두 재발급이 새 토큰을 하나씩 받았고, 브라우저에는 뒤에 도착한 응답의 토큰만 남았다
		String oldToken = refreshTokenService.issue(member);
		String overwrittenToken = refreshTokenService.rotate(oldToken).token();
		String keptToken = refreshTokenService.rotate(oldToken).token();

		// when
		refreshTokenService.revoke(keptToken);

		// then
		assertThat(isRevoked(overwrittenToken)).as("브라우저에 남지 않은 다른 새 토큰").isTrue();
		assertThatThrownBy(() -> refreshTokenService.rotate(overwrittenToken))
			.isInstanceOf(InvalidRefreshTokenException.class)
			.hasMessage("로그아웃했거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요.");
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Test
	@DisplayName("옛 토큰으로 유예 시간 안에 재발급하는 요청이 옛 토큰을 잠가 읽은 뒤 새 토큰을 넣기 전에, 먼저 받은 새 토큰으로 로그아웃하면 로그아웃은 그 재발급의 커밋을 기다렸다가 그 새 토큰까지 폐기한다")
	void logoutWithNewTokenWaitsForLateRotationOfOldTokenAndRevokesItsNewToken() throws Exception {
		// given: 처음 받은 토큰을 재발급해 새 토큰을 받았다. 옛 쿠키를 실은 재발급이 하나 더 늦게 온다.
		String oldToken = refreshTokenService.issue(member);
		String newToken = refreshTokenService.rotate(oldToken).token();

		// when
		String newTokenOfLateRotation = rotateAgainWhileLogoutArrives(refreshTokenService, refreshTokenRepository,
			oldToken, newToken);

		// then
		assertThat(isRevoked(newTokenOfLateRotation)).as("로그아웃이 기다리는 동안 커밋된 늦은 재발급의 새 토큰").isTrue();
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Test
	@DisplayName("한 회원의 두 기기가 이미 새 토큰으로 바꾼 옛 토큰으로 동시에 로그아웃해도 교착 없이 둘 다 끝나고, 그 회원의 토큰은 모두 폐기된다")
	void twoDevicesLogOutWithUsedTokensAtTheSameTimeWithoutDeadlock() {
		// given: 두 기기가 각자 받은 토큰을 한 번씩 재발급했다. 옛 토큰 두 개는 쓴 토큰이다.
		String phoneToken = refreshTokenService.issue(member);
		String pcToken = refreshTokenService.issue(member);
		refreshTokenService.rotate(phoneToken);
		refreshTokenService.rotate(pcToken);
		CountDownLatch bothRevokedOwnToken = new CountDownLatch(2);
		// 두 로그아웃 모두 자기 토큰을 폐기한 뒤(그 행을 잠근 채) 멈췄다가, 둘 다 그 자리에 오면 뒤에 만든 토큰을 폐기하러 간다
		willAnswer(invocation -> {
			Object result = callRealRepository(invocation);
			bothRevokedOwnToken.countDown();
			waitUpToTenSeconds(bothRevokedOwnToken);
			return result;
		}).given(refreshTokenRepository).revokeByToken(anyString());
		List<Callable<String>> logouts = List.of(
			() -> {
				refreshTokenService.revoke(phoneToken);
				return "휴대폰";
			},
			() -> {
				refreshTokenService.revoke(pcToken);
				return "PC";
			});

		// when
		List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(2, logouts::get);

		// then
		assertThat(results).allSatisfy(result -> assertThat(result.error())
			.as("503 이 되는 교착(1213) 없이 끝나야 한다").isNull());
		assertThat(countRowsOfMember("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = false"))
			.as("폐기되지 않은 토큰").isZero();
	}

	@Test
	@DisplayName("한 회원이 두 기기에서 받은 토큰을 동시에 재발급해도 둘 다 성공하고, 두 기기의 새 토큰이 모두 쓸 수 있는 상태로 남는다")
	void twoDevicesOfSameMemberRotateAtTheSameTime() {
		// given
		String phoneToken = refreshTokenService.issue(member);
		String pcToken = refreshTokenService.issue(member);
		List<Callable<RotatedRefreshToken>> rotations = List.of(
			() -> refreshTokenService.rotate(phoneToken),
			() -> refreshTokenService.rotate(pcToken));

		// when
		List<CallResult<RotatedRefreshToken>> results = ConcurrentCalls.runAtTheSameTime(2, rotations::get);

		// then
		assertThat(results).allSatisfy(result -> assertThat(result.error()).as("재발급").isNull());
		assertThat(countUsableTokens(results.stream().map(result -> result.value().token()).toList()))
			.as("두 기기의 새 토큰").isEqualTo(2);
		assertThat(jdbcTemplate.queryForList("SELECT token FROM refresh_tokens WHERE user_id = ? AND used_at IS NOT NULL",
			String.class, member.getId())).as("쓴 토큰").containsExactlyInAnyOrder(phoneToken, pcToken);
	}

	@RepeatedTest(value = 20, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("리프레시 토큰이 없는 신규 회원 10명이 동시에 로그인해도 교착 없이 모두 토큰을 받는다")
	void newMembersLogInAtTheSameTimeWithoutDeadlock() {
		// given
		List<User> newMembers = saveNewMembers(NEW_MEMBER_COUNT);

		// when
		List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(NEW_MEMBER_COUNT,
			index -> () -> refreshTokenService.issue(newMembers.get(index)));

		// then
		assertThat(results).allSatisfy(result -> assertThat(result.error()).as("로그인").isNull());
		assertThat(countUsableTokens(results.stream().map(CallResult::value).toList()))
			.as("신규 회원들이 받은 토큰").isEqualTo(NEW_MEMBER_COUNT);
	}

	@Test
	@DisplayName("예전 발급 방식(회원의 토큰을 user_id 로 지운 뒤 넣기)은 토큰이 없는 신규 회원 둘이 지우기를 모두 마친 뒤 넣으면 한쪽이 교착으로 롤백된다")
	void oldDeleteThenInsertDeadlocksForNewMembers() {
		// given: 예전 generateRefreshToken 과 같은 모양의 트랜잭션. 두 회원 모두 지우기를 마친 뒤에 넣도록 래치로 맞춘다.
		List<User> newMembers = saveNewMembers(2);
		CountDownLatch bothDeleted = new CountDownLatch(2);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		// when
		List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(2, index -> () -> transaction.execute(
			status -> {
				User newMember = newMembers.get(index);
				refreshTokenRepository.deleteByUser(newMember);
				bothDeleted.countDown();
				waitUpToTenSeconds(bothDeleted);
				return refreshTokenRepository.save(RefreshToken.issue("old-way-" + runId + "-" + index, newMember,
					LocalDateTime.now(), Duration.ofDays(30))).getToken();
			}));

		// then: 지운 행이 없어도 DELETE 가 건 틈 잠금끼리 서로의 INSERT 를 막아, MySQL 이 한쪽을 교착으로 롤백한다
		assertThat(results).filteredOn(CallResult::succeeded).as("넣은 쪽").hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded()).as("교착으로 롤백된 쪽").singleElement()
			.satisfies(result -> assertThat(result.error())
				.isInstanceOfSatisfying(PessimisticLockingFailureException.class,
					lockFailure -> assertThat(lockFailure.getMostSpecificCause())
						.isInstanceOfSatisfying(SQLException.class,
							sqlException -> assertThat(sqlException.getErrorCode()).isEqualTo(MYSQL_DEADLOCK))));
	}

	@Test
	@DisplayName("정리 작업은 만료된 토큰과 쓴 지 하루가 지난 토큰만 지우고, 쓸 수 있는 토큰·방금 쓴 토큰·만료 전에 폐기된 토큰은 남긴다")
	void cleanupDeletesOnlyExpiredAndLongUsedTokens() {
		// given
		String expired = refreshTokenService.issue(member);
		String usedLongAgo = refreshTokenService.issue(member);
		String justUsed = refreshTokenService.issue(member);
		String usable = refreshTokenService.issue(member);
		String revoked = refreshTokenService.issue(member);
		jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE token = ?", LONG_AGO, expired);
		jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE token = ?", LONG_AGO, usedLongAgo);
		jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE token = ?", LocalDateTime.now(), justUsed);
		refreshTokenService.revoke(revoked);

		// when
		refreshTokenCleanupScheduler.deleteUnusableTokens();

		// then
		assertThat(jdbcTemplate.queryForList("SELECT token FROM refresh_tokens WHERE user_id = ?", String.class,
			member.getId())).containsExactlyInAnyOrder(justUsed, usable, revoked);
	}

	private User saveMember(String memberEmail) {
		User saved = userRepository.save(User.create(memberEmail, "토큰회원", "encoded-password", memberEmail,
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false));
		memberIdsOfThisRun.add(saved.getId());
		return saved;
	}

	/**
	 * 리프레시 토큰이 없는 신규 회원 count 명을 만든다. 가장 큰 users.id 를 받으므로 refresh_tokens.user_id 인덱스의 맨 끝 틈에 모인다.
	 */
	private List<User> saveNewMembers(int count) {
		return IntStream.range(0, count)
			.mapToObj(index -> saveMember("refresh-new-" + index + "-" + runId + "@example.com"))
			.toList();
	}

	/**
	 * tokens 중 쓸 수 있는(폐기되지 않았고, 쓰지 않았고, 만료 전인) 토큰의 수. 만료 시각은 애플리케이션 시계로 적으므로, DB 의 NOW()
	 * (컨테이너 시간대) 대신 이 JVM 의 지금 시각과 비교한다.
	 */
	private int countUsableTokens(List<String> tokens) {
		Integer count = new NamedParameterJdbcTemplate(jdbcTemplate).queryForObject(
			"SELECT COUNT(*) FROM refresh_tokens WHERE token IN (:tokens) AND revoked = false AND used_at IS NULL "
				+ "AND expires_at > :now", Map.of("tokens", tokens, "now", LocalDateTime.now()), Integer.class);
		return count == null ? 0 : count;
	}

	private int countRowsOfMember(String sql) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, member.getId());
		return count == null ? 0 : count;
	}

	private boolean isRevoked(String token) {
		return Boolean.TRUE.equals(
			jdbcTemplate.queryForObject("SELECT revoked FROM refresh_tokens WHERE token = ?", Boolean.class, token));
	}

	/**
	 * rotatedToken 으로 유예 시간 안의 재발급을 부르되, 토큰 행을 잠가 읽은 직후(새 토큰을 넣기 전) 멈춰 둔다. 그동안 logoutToken 으로
	 * 로그아웃하고, 로그아웃이 행 잠금을 기다리기 시작하면(또는 끝나면) 재발급을 풀어 준다. 둘 다 끝나면 멈췄던 재발급이 받은 새 토큰을
	 * 돌려준다.
	 *
	 * @param service 재발급과 로그아웃을 부를 서비스. 설정이 다른 중첩 클래스의 스프링 컨텍스트에서 받은 것을 넘길 수 있다.
	 * @param spyRepository service 와 같은 컨텍스트의 리프레시 토큰 리포지토리 스파이
	 */
	private String rotateAgainWhileLogoutArrives(RefreshTokenService service, RefreshTokenRepository spyRepository,
		String rotatedToken, String logoutToken) throws Exception {
		CountDownLatch lockedRead = new CountDownLatch(1);
		CountDownLatch releaseRotation = new CountDownLatch(1);
		willAnswer(invocation -> {
			Object result = callRealRepository(invocation);
			lockedRead.countDown();
			waitUpToTenSeconds(releaseRotation);
			return result;
		}).given(spyRepository).findByTokenForUpdate(rotatedToken);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<RotatedRefreshToken> rotation = executor.submit(() -> service.rotate(rotatedToken));
			assertThat(lockedRead.await(10, SECONDS)).as("재발급이 쓴 토큰을 잠가 읽고 멈췄다").isTrue();

			Future<?> logout = executor.submit(() -> service.revoke(logoutToken));
			await().atMost(Duration.ofSeconds(10)).until(() -> logout.isDone() || rowLockWaitsInThisSchema() > 0);
			releaseRotation.countDown();
			String newTokenOfPausedRotation = rotation.get(10, SECONDS).token();
			logout.get(10, SECONDS);
			return newTokenOfPausedRotation;
		} finally {
			releaseRotation.countDown();
			executor.shutdownNow();
		}
	}

	private Callable<Object> logoutOf(String token) {
		return () -> {
			refreshTokenService.revoke(token);
			return "로그아웃";
		};
	}

	private Callable<Object> rotationOf(String token) {
		return () -> refreshTokenService.rotate(token);
	}

	/**
	 * 로그아웃은 늘 성공한다. 재발급은 먼저 잠그면 성공하고, 로그아웃이 먼저 잠그면 폐기된 토큰으로 거절(401)된다. 어느 쪽도 500 이 되는
	 * 잠금·행 수 예외가 아니다.
	 */
	private static void assertLogoutAndRotationEndedWithoutServerError(CallResult<Object> logout,
		CallResult<Object> rotation) {
		assertThat(logout.error()).as("로그아웃").isNull();
		assertThat(rotation).as("재발급").satisfiesAnyOf(
			result -> assertThat(result.error()).isNull(),
			result -> assertThat(result.error())
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage("로그아웃했거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요."));
	}

	/**
	 * 스파이로 바꾼 리포지토리의 실제 메서드를 부른다. 스프링 데이터 리포지토리는 JDK 프록시라, @MockitoSpyBean 이 실제 객체에 호출을
	 * 넘기는 목(AdditionalAnswers.delegatesTo)으로 만든다. 그 목의 기본 응답에 넘기면 실제 리포지토리가 불린다.
	 */
	private static Object callRealRepository(InvocationOnMock invocation) throws Throwable {
		return Mockito.mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer()
			.answer(invocation);
	}

	/**
	 * 이 테스트 스키마의 행 잠금을 기다리는 요청 수. 같은 MySQL 서버의 다른 스키마(다른 스택의 테스트 DB)에서 기다리는 요청은 세지 않는다.
	 */
	private int rowLockWaitsInThisSchema() {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
			+ "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
			+ "WHERE l.OBJECT_SCHEMA = DATABASE()", Integer.class);
		return count == null ? 0 : count;
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
