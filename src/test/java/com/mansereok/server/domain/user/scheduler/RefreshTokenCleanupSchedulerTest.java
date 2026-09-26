package com.mansereok.server.domain.user.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * 정리 작업이 만료 토큰과 쓴 지 오래된 토큰을 1000 행씩, 지운 행이 0 이 될 때까지 되풀이해 지우는지 확인한다. 삭제 SQL 이 맞는 행만
 * 지우는지는 RefreshTokenMySqlTest 가 본다.
 *
 * <p>삭제 기준 시각을 정확한 값으로 스텁해 두므로, 다른 시각으로 지우면 strict stubs 가 테스트를 실패시킨다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupSchedulerTest {

	// 2026-09-26 04:30 (서울)
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-25T19:30:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 4, 30);
	private static final LocalDateTime ONE_DAY_AGO = LocalDateTime.of(2026, 9, 25, 4, 30);

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	// 스케줄러가 이 목으로 진짜 TransactionTemplate 을 만든다. 트랜잭션을 열면 null 을 돌려주고 커밋은 아무것도 하지 않아, 배치는
	// 트랜잭션 안에서처럼 그대로 돈다.
	@Mock
	private PlatformTransactionManager transactionManager;

	private RefreshTokenCleanupScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new RefreshTokenCleanupScheduler(refreshTokenRepository, transactionManager, FIXED_CLOCK);
	}

	@Test
	@DisplayName("만료 토큰은 지운 행이 0 이 될 때까지 1000 행씩 되풀이해 지운다")
	void deletesExpiredTokensUntilNothingIsLeft() {
		// given
		given(refreshTokenRepository.deleteExpiredBefore(NOW, 1000)).willReturn(1000, 250, 0);
		given(refreshTokenRepository.deleteUsedBefore(ONE_DAY_AGO, 1000)).willReturn(0);

		// when
		scheduler.deleteUnusableTokens();

		// then
		then(refreshTokenRepository).should(times(3)).deleteExpiredBefore(NOW, 1000);
	}

	@Test
	@DisplayName("쓴 지 하루가 지난 토큰도 지운 행이 0 이 될 때까지 1000 행씩 되풀이해 지운다")
	void deletesLongUsedTokensUntilNothingIsLeft() {
		// given
		given(refreshTokenRepository.deleteExpiredBefore(NOW, 1000)).willReturn(0);
		given(refreshTokenRepository.deleteUsedBefore(ONE_DAY_AGO, 1000)).willReturn(1000, 1000, 0);

		// when
		scheduler.deleteUnusableTokens();

		// then
		then(refreshTokenRepository).should(times(3)).deleteUsedBefore(ONE_DAY_AGO, 1000);
	}

	@Test
	@DisplayName("배치마다 READ COMMITTED 트랜잭션을 따로 연다")
	void opensReadCommittedTransactionPerBatch() {
		// given: 만료 토큰 배치 2번(1000, 0), 쓴 토큰 배치 1번(0)
		given(refreshTokenRepository.deleteExpiredBefore(NOW, 1000)).willReturn(1000, 0);
		given(refreshTokenRepository.deleteUsedBefore(ONE_DAY_AGO, 1000)).willReturn(0);

		// when
		scheduler.deleteUnusableTokens();

		// then
		ArgumentCaptor<TransactionDefinition> transactions = ArgumentCaptor.forClass(TransactionDefinition.class);
		then(transactionManager).should(times(3)).getTransaction(transactions.capture());
		assertThat(transactions.getAllValues()).extracting(TransactionDefinition::getIsolationLevel)
			.containsOnly(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}
}
