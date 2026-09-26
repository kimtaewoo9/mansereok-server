package com.mansereok.server.domain.user.scheduler;

import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 더는 쓸 수 없는 리프레시 토큰을 매일 새벽에 지운다. 로그인·재발급이 회원의 토큰을 지우지 않고 기기마다 쌓아 두므로 이 정리가 필요하다.
 *
 * <ul>
 *   <li>만료 시각이 지난 토큰</li>
 *   <li>새 토큰으로 바꾼(쓴) 지 {@link #USED_TOKEN_RETENTION} 이 지난 토큰. 쓴 토큰은 유예 시간 안의 재발급, 이미 쓴 토큰이라는 거절
 *   사유, 로그아웃 때 그 뒤에 만든 토큰 찾기에 쓰므로 바로 지우지 않는다.</li>
 * </ul>
 *
 * <p>한 번에 {@link #BATCH_SIZE} 행까지 지우고, 지운 행이 0 이 될 때까지 되풀이한다. 한 번에 모두 지우면 그동안 지우는 행의 잠금과
 * undo 가 쌓이고 복제가 밀린다. 배치마다 트랜잭션을 따로 열어 잠금을 짧게 쥔다.
 *
 * <p>배치는 READ COMMITTED 로 돈다. REPEATABLE READ 에서는 범위를 지우는 동안 인덱스의 틈까지 잠근다. 쓴 시각 범위를 지우면
 * used_at 인덱스에서 아직 쓰지 않은(used_at 이 NULL 인) 행 바로 뒤의 틈이 잠겨, 로그인·재발급이 새 토큰을 넣는 INSERT 가 그 배치가
 * 끝날 때까지 기다린다. READ COMMITTED 트랜잭션의 쓰기에는 UserService 클래스 설명의 binlog_format 전제가 같이 걸린다.
 *
 * <p>새벽 4시 30분(서울)에 한 번 돈다. cron 은 애플리케이션이 뜰 때 바로 돌지 않는다. 스프링은 cron 에 initialDelay 를 함께 쓰지
 * 못하게 막는다.
 */
@Component
@Slf4j
public class RefreshTokenCleanupScheduler {

	static final int BATCH_SIZE = 1000;
	static final Duration USED_TOKEN_RETENTION = Duration.ofDays(1);

	private final RefreshTokenRepository refreshTokenRepository;
	private final TransactionTemplate readCommittedTransaction;
	private final Clock clock;

	public RefreshTokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository,
		PlatformTransactionManager transactionManager, Clock clock) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.readCommittedTransaction = new TransactionTemplate(transactionManager);
		this.readCommittedTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.clock = clock;
	}

	/**
	 * 만료 토큰과 쓴 지 오래된 토큰을 차례로 지운다. 한쪽이 예외로 멈춰도(잠금 대기 시간 초과 등) 다른 쪽은 돈다. 남은 행은 다음 날
	 * 실행에서 지운다.
	 */
	@Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
	public void deleteUnusableTokens() {
		LocalDateTime now = LocalDateTime.now(clock);
		int expired = deleteInBatches("만료 토큰",
			() -> refreshTokenRepository.deleteExpiredBefore(now, BATCH_SIZE));
		int used = deleteInBatches("쓴 지 오래된 토큰",
			() -> refreshTokenRepository.deleteUsedBefore(now.minus(USED_TOKEN_RETENTION), BATCH_SIZE));
		log.info("리프레시 토큰 정리 끝: 만료 {}건, 쓴 지 오래된 토큰 {}건", expired, used);
	}

	/**
	 * 한 배치를 트랜잭션 하나로 돌리기를 지운 행이 0 이 될 때까지 되풀이하고, 지운 행 수의 합을 돌려준다. 배치가 예외로 끝나면 대상과
	 * 그때까지 지운 행 수를 로그로 남기고 그 합을 돌려준다. 예외로 끝난 배치는 롤백되므로 합에 넣지 않는다.
	 */
	private int deleteInBatches(String target, IntSupplier deleteOneBatch) {
		int total = 0;
		try {
			while (true) {
				int deleted = readCommittedTransaction.execute(status -> deleteOneBatch.getAsInt());
				if (deleted == 0) {
					return total;
				}
				total += deleted;
			}
		} catch (RuntimeException e) {
			log.error("리프레시 토큰 정리 실패({}): {}건을 지운 뒤 멈췄다. 남은 행은 다음 실행에서 지운다.", target, total, e);
			return total;
		}
	}
}
