package com.mansereok.server.global.exception;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.support.LocalMySqlTest;
import com.mansereok.server.support.fixture.TestOrders;
import java.sql.SQLException;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 주문 행을 다른 트랜잭션이 잠그고 있을 때 잠금 대기 시간이 넘으면, 스프링이 이를
 * {@link PessimisticLockingFailureException}(또는 그 하위 타입)으로 번역하는지 실제 MySQL 로 확인한다.
 *
 * <p>RequestErrorExceptionHandler 는 이 타입을 503 SERVER_BUSY 로 답한다. MySQL 오류 1205 가 Hibernate·스프링을 거쳐 어떤
 * 타입이 되는지는 방언과 번역기에 달린 문제라 목으로는 확인할 수 없다. 이 번역이 바뀌어 다른 타입이 되면 잠금 경합이 다시 500 이 된다.
 *
 * <p>기다리는 쪽 트랜잭션만 innodb_lock_wait_timeout 을 1초로 줄여 테스트가 기본값 50초를 기다리지 않게 한다. 이 값은 커넥션에
 * 남으므로, 같은 커넥션에서 원래 값으로 되돌린 뒤 풀에 돌려준다.
 */
class LockTimeoutTranslationMySqlTest extends LocalMySqlTest {

	private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final String merchantUid = "order_lock_timeout_" + UUID.randomUUID().toString().substring(0, 8);

	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void saveOrder() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		orderRepository.save(TestOrders.order().merchantUid(merchantUid).userId(null).pending());
	}

	@AfterEach
	void deleteOrder() {
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
	}

	@Test
	@DisplayName("다른 트랜잭션이 잠근 주문을 findByMerchantUidWithLock 으로 기다리다 1초가 넘으면 PessimisticLockingFailureException 계열이 난다")
	void lockWaitTimeoutIsTranslatedToPessimisticLockingFailure() throws Exception {
		// given: 다른 스레드의 트랜잭션이 주문 행을 잠그고 풀어 줄 때까지 붙잡고 있는다
		CountDownLatch lockHeld = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				orderRepository.findByMerchantUidWithLock(merchantUid).orElseThrow();
				lockHeld.countDown();
				awaitQuietly(releaseLock);
			}));
			assertThat(lockHeld.await(10, SECONDS)).as("다른 트랜잭션이 주문 행을 잠갔다").isTrue();

			// when
			Throwable thrown = catchThrowable(this::lockSameOrderWaitingAtMostOneSecond);

			// then
			assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
			assertThat(((PessimisticLockingFailureException) thrown).getMostSpecificCause())
				.as("MySQL 이 준 오류가 잠금 대기 초과(1205)여야 한다")
				.isInstanceOfSatisfying(SQLException.class,
					sqlException -> assertThat(sqlException.getErrorCode()).isEqualTo(MYSQL_LOCK_WAIT_TIMEOUT));

			releaseLock.countDown();
			lockHolder.get(10, SECONDS);
		} finally {
			releaseLock.countDown();
			executor.shutdownNow();
		}
	}

	/**
	 * 잠금 대기 한도를 1초로 줄인 트랜잭션에서 같은 주문 행을 잠그려 한다. 한도를 줄인 SET 과 되돌리는 SET 은 JdbcTemplate 으로
	 * 실행하지만, 트랜잭션 안이라 JPA 조회와 같은 커넥션을 쓴다.
	 */
	private void lockSameOrderWaitingAtMostOneSecond() {
		transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
			try {
				orderRepository.findByMerchantUidWithLock(merchantUid);
			} finally {
				jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
			}
		});
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
