package com.mansereok.server.domain.interpret.service;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.scheduler.StaleProcessingProperties;
import com.mansereok.server.domain.interpret.scheduler.StaleProcessingResultScheduler;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import com.mansereok.server.support.fixture.ResultFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 한 건의 해석이 한 번만 시작되고, 오래 멈춘 해석 중 결과만 되돌아가는지 실제 MySQL 로 확인한다.
 *
 * <p>해석 시작은 "정보 입력 대기일 때만 해석 중으로" 를 조건부 UPDATE 한 문장으로 한다. 같은 결제로 동시에 온 요청 중 몇 개가
 * 통과하는지, 그 UPDATE 가 다른 결제의 행까지 잠그는지는 DB 가 정하므로 목으로는 알 수 없다. 오래 멈춘 결과 되돌리기도 조건부
 * UPDATE 라, 해석 저장과 겹칠 때 완료된 결과를 건드리지 않는지 실제 행 잠금으로 본다.
 *
 * <p>되돌리기 작업은 고정 시계로 직접 만들어 부른다. 그 시계는 실제 시각보다 뒤(2100년)로 두어, 애플리케이션 안에서 실제 시계로
 * 도는 같은 작업이 이 테스트가 과거로 옮긴 행을 먼저 되돌리지 못하게 한다. 행은 이번 실행의 사용자 ID 로 만들고 뒤 정리에서 그
 * 사용자 ID 로만 지운다. 결과 표는 결제 표를 참조하지 않으므로 결제 행은 만들지 않는다.
 */
class ResultStartOnceMySqlTest extends InterpretationMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리느라 느려진다.
	private static final int REQUEST_COUNT = 10;

	// 2100-01-01 09:00 (서울). 실제 시각보다 뒤라서 실제 시계로 도는 되돌리기 작업의 기준 시각보다 늘 늦다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2100, 1, 1, 9, 0);
	private static final LocalDateTime SEVENTY_MINUTES_AGO = LocalDateTime.of(2100, 1, 1, 7, 50);
	private static final LocalDateTime FIFTY_MINUTES_AGO = LocalDateTime.of(2100, 1, 1, 8, 10);

	// 다른 행을 고치는 일은 잠금에 걸리지 않으면 수 밀리초에 끝난다. 걸리면 InnoDB 잠금 대기 기본값(50초)까지 멈춘다.
	private static final Duration OTHER_ROW_TIMEOUT = Duration.ofSeconds(2);
	private static final String LOCK_SCOPE_FIX = "결제 1 의 해석 시작 트랜잭션이 끝날 때까지 결제 2 가 기다렸다. 조건부 UPDATE 가 "
		+ "payment_id 의 UNIQUE 인덱스를 타지 못하고 표를 훑으며 다른 행까지 잠근 것이다. 엔티티의 uk_*_payment_id 선언과 로컬 표의 "
		+ "인덱스를 확인한다.";

	@Autowired
	private ResultService resultService;

	@Autowired
	private SajuResultService sajuResultService;

	@Autowired
	private ResultRepository resultRepository;

	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 사용자 ID·결제 ID(UNIQUE)가 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runKey = Long.parseLong(runId, 16) * 100;
	private final Long userId = runKey;

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id = ?", userId);
	}

	@Nested
	@DisplayName("같은 결제로 해석 시작 요청 10개가 동시에 오면")
	class SamePaymentAtOnce {

		@Test
		@DisplayName("사주 결과는 한 요청만 통과하고 나머지 9개는 InterpretationAlreadyStartedException 으로 끝나며 행은 PROCESSING 이다")
		void onlyOneSajuRequestStarts() {
			// given
			Long paymentId = runKey + 1;
			saveSaju(paymentId, ResultStatus.INPUT_REQUIRED);

			// when
			List<CallResult<Void>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT, () -> {
				resultService.startProcessing(paymentId);
				return null;
			});

			// then
			assertThat(calls).filteredOn(CallResult::succeeded).as("통과한 요청").hasSize(1);
			assertThat(calls).filteredOn(call -> !call.succeeded()).as("거절된 요청").hasSize(9)
				.allSatisfy(call -> assertThat(call.error()).isInstanceOf(InterpretationAlreadyStartedException.class));
			assertThat(statusOf("results", paymentId)).isEqualTo("PROCESSING");
		}

		@Test
		@DisplayName("궁합 결과는 한 요청만 통과하고 나머지 9개는 InterpretationAlreadyStartedException 으로 끝나며 행은 PROCESSING 이다")
		void onlyOneCompatibilityRequestStarts() {
			// given
			Long paymentId = runKey + 1;
			saveCompatibility(paymentId, ResultStatus.INPUT_REQUIRED);

			// when
			List<CallResult<Void>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT, () -> {
				resultService.startCompatibilityProcessing(paymentId);
				return null;
			});

			// then
			assertThat(calls).filteredOn(CallResult::succeeded).as("통과한 요청").hasSize(1);
			assertThat(calls).filteredOn(call -> !call.succeeded()).as("거절된 요청").hasSize(9)
				.allSatisfy(call -> assertThat(call.error()).isInstanceOf(InterpretationAlreadyStartedException.class));
			assertThat(statusOf("compatibility_results", paymentId)).isEqualTo("PROCESSING");
		}
	}

	@Nested
	@DisplayName("이미 해석 중이거나 완료된 결과로 해석을 시작하면")
	class AlreadyStarted {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(value = ResultStatus.class, names = {"PROCESSING", "COMPLETED"})
		@DisplayName("사주 결과는 InterpretationAlreadyStartedException 을 던지고 행의 어떤 칸도 바꾸지 않는다")
		void sajuRowStaysAsItWas(ResultStatus current) {
			// given: 바뀌었는지 알아볼 수 있게 이름과 과거의 변경 시각을 넣어 둔다
			Long paymentId = runKey + 1;
			saveSaju(paymentId, current);
			jdbcTemplate.update("UPDATE results SET name = '철수', updated_at = ? WHERE payment_id = ?",
				SEVENTY_MINUTES_AGO, paymentId);
			Map<String, Object> before = rowOf("results", paymentId);

			// when & then
			assertThatThrownBy(() -> resultService.startProcessing(paymentId))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			assertThat(rowOf("results", paymentId)).isEqualTo(before);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(value = ResultStatus.class, names = {"PROCESSING", "COMPLETED"})
		@DisplayName("궁합 결과는 InterpretationAlreadyStartedException 을 던지고 행의 어떤 칸도 바꾸지 않는다")
		void compatibilityRowStaysAsItWas(ResultStatus current) {
			// given: 바뀌었는지 알아볼 수 있게 이름과 과거의 변경 시각을 넣어 둔다
			Long paymentId = runKey + 1;
			saveCompatibility(paymentId, current);
			jdbcTemplate.update("UPDATE compatibility_results SET person1_name = '철수', updated_at = ? "
				+ "WHERE payment_id = ?", SEVENTY_MINUTES_AGO, paymentId);
			Map<String, Object> before = rowOf("compatibility_results", paymentId);

			// when & then
			assertThatThrownBy(() -> resultService.startCompatibilityProcessing(paymentId))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			assertThat(rowOf("compatibility_results", paymentId)).isEqualTo(before);
		}
	}

	@Nested
	@DisplayName("결제 1 의 해석 시작 트랜잭션이 끝나지 않은 동안")
	class WhileAnotherPaymentStarts {

		@Test
		@DisplayName("다른 결제 2 의 사주 해석 시작은 기다리지 않고 2초 안에 끝난다")
		void sajuStartLocksOnlyItsRow() throws Exception {
			// given
			Long first = runKey + 1;
			Long second = runKey + 2;
			saveSaju(first, ResultStatus.INPUT_REQUIRED);
			saveSaju(second, ResultStatus.INPUT_REQUIRED);

			// when
			boolean secondFinishedInTime = secondStartsWhileFirstHoldsLock(resultService::startProcessing, first,
				second);

			// then
			assertThat(secondFinishedInTime).as(LOCK_SCOPE_FIX).isTrue();
			assertThat(statusOf("results", first)).as("결제 1").isEqualTo("PROCESSING");
			assertThat(statusOf("results", second)).as("결제 2").isEqualTo("PROCESSING");
		}

		@Test
		@DisplayName("다른 결제 2 의 궁합 해석 시작은 기다리지 않고 2초 안에 끝난다")
		void compatibilityStartLocksOnlyItsRow() throws Exception {
			// given
			Long first = runKey + 1;
			Long second = runKey + 2;
			saveCompatibility(first, ResultStatus.INPUT_REQUIRED);
			saveCompatibility(second, ResultStatus.INPUT_REQUIRED);

			// when
			boolean secondFinishedInTime = secondStartsWhileFirstHoldsLock(
				resultService::startCompatibilityProcessing, first, second);

			// then
			assertThat(secondFinishedInTime).as(LOCK_SCOPE_FIX).isTrue();
			assertThat(statusOf("compatibility_results", first)).as("결제 1").isEqualTo("PROCESSING");
			assertThat(statusOf("compatibility_results", second)).as("결제 2").isEqualTo("PROCESSING");
		}
	}

	@Nested
	@DisplayName("오래 멈춘 결과 되돌리기를 돌리면")
	class RevertStaleProcessing {

		@Test
		@DisplayName("두 표 모두 70분 전에 마지막으로 바뀐 해석 중 결과만 지금 시각으로 되돌리고, 50분 전 해석 중·70분 전 완료·70분 전 입력 대기는 그대로 둔다")
		void revertsOnlyProcessingOlderThanStaleAfter() {
			// given
			saveSajuUpdatedAt(runKey + 1, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			saveSajuUpdatedAt(runKey + 2, ResultStatus.PROCESSING, FIFTY_MINUTES_AGO);
			saveSajuUpdatedAt(runKey + 3, ResultStatus.COMPLETED, SEVENTY_MINUTES_AGO);
			saveSajuUpdatedAt(runKey + 4, ResultStatus.INPUT_REQUIRED, SEVENTY_MINUTES_AGO);
			saveCompatibilityUpdatedAt(runKey + 1, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			saveCompatibilityUpdatedAt(runKey + 2, ResultStatus.PROCESSING, FIFTY_MINUTES_AGO);
			saveCompatibilityUpdatedAt(runKey + 3, ResultStatus.COMPLETED, SEVENTY_MINUTES_AGO);
			saveCompatibilityUpdatedAt(runKey + 4, ResultStatus.INPUT_REQUIRED, SEVENTY_MINUTES_AGO);

			// when
			staleProcessingScheduler().revertStaleProcessingResults();

			// then
			List<StatusRow> expected = List.of(
				new StatusRow(runKey + 1, "INPUT_REQUIRED", NOW),
				new StatusRow(runKey + 2, "PROCESSING", FIFTY_MINUTES_AGO),
				new StatusRow(runKey + 3, "COMPLETED", SEVENTY_MINUTES_AGO),
				new StatusRow(runKey + 4, "INPUT_REQUIRED", SEVENTY_MINUTES_AGO));
			assertThat(statusRowsOf("results")).as("사주 결과").containsExactlyElementsOf(expected);
			assertThat(statusRowsOf("compatibility_results")).as("궁합 결과").containsExactlyElementsOf(expected);
		}

		@Test
		@DisplayName("해석 저장 트랜잭션이 행을 잡고 있을 때 겹친 되돌리기는 저장이 끝나기를 기다렸다가 완료된 결과를 그대로 둔다")
		void doesNotRevertResultCompletedMeanwhile() throws Exception {
			// given: 되돌리기 기준을 넘길 만큼 오래된 해석 중 결과
			Long paymentId = runKey + 1;
			Long resultId = saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			CountDownLatch savedAndHoldingRow = new CountDownLatch(1);
			CountDownLatch releaseSave = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<?> save = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					sajuResultService.saveFinalResult(resultId, "늦게 끝난 본문", "늦게 끝난 요약");
					resultRepository.flush();
					savedAndHoldingRow.countDown();
					awaitLatch(releaseSave);
				}));
				assertThat(savedAndHoldingRow.await(10, SECONDS)).as("저장이 UPDATE 를 보내고 행을 잡았다").isTrue();

				// when: 저장 트랜잭션이 끝나기 전에 되돌리기를 시작하고, 행 잠금에 걸려 기다리는지 본 뒤 저장을 끝낸다
				Future<?> revert = executor.submit(() -> staleProcessingScheduler().revertStaleProcessingResults());
				assertThatThrownBy(() -> revert.get(1, SECONDS)).as("되돌리기가 저장 트랜잭션의 행 잠금을 기다린다")
					.isInstanceOf(TimeoutException.class);
				releaseSave.countDown();
				save.get(10, SECONDS);
				revert.get(10, SECONDS);

				// then
				assertThat(jdbcTemplate.queryForMap("SELECT status, interpretation FROM results WHERE payment_id = ?",
					paymentId)).isEqualTo(Map.of("status", "COMPLETED", "interpretation", "늦게 끝난 본문"));
			} finally {
				releaseSave.countDown();
				executor.shutdown();
				assertThat(executor.awaitTermination(60, SECONDS)).as("저장과 되돌리기가 끝났다").isTrue();
			}
		}

		@Test
		@DisplayName("되돌린 뒤에 늦게 끝난 해석을 저장하면 IllegalStateException 으로 거부되고 행은 정보 입력 대기 그대로다")
		void rejectsLateSaveAfterRevert() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			staleProcessingScheduler().revertStaleProcessingResults();

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(resultId, "늦게 끝난 본문", "늦게 끝난 요약"))
				.isInstanceOf(IllegalStateException.class);
			Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, interpretation FROM results WHERE payment_id = ?", paymentId);
			assertThat(row.get("status")).isEqualTo("INPUT_REQUIRED");
			assertThat(row.get("interpretation")).isNull();
		}
	}

	@Nested
	@DisplayName("서로 다른 결제 20건을 10개 스레드가 나눠 동시에 시작하면")
	class ManyPaymentsAtOnce {

		@Test
		@DisplayName("잠금 오류 없이 모든 요청이 통과하고 사주 10건·궁합 10건이 모두 PROCESSING 이 된다")
		void startsAllWithoutLockErrors() {
			// given: 스레드 i 가 사주 결제 runKey+10+i 와 궁합 결제 runKey+30+i 를 차례로 시작한다
			for (int index = 0; index < REQUEST_COUNT; index++) {
				saveSaju(runKey + 10 + index, ResultStatus.INPUT_REQUIRED);
				saveCompatibility(runKey + 30 + index, ResultStatus.INPUT_REQUIRED);
			}

			// when
			List<CallResult<Void>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT, index -> () -> {
				resultService.startProcessing(runKey + 10 + index);
				resultService.startCompatibilityProcessing(runKey + 30 + index);
				return null;
			});

			// then
			assertThat(calls).as("요청마다 예외 없이 끝났다")
				.allSatisfy(call -> assertThat(call.error()).isNull());
			assertThat(countOfStatus("results", "PROCESSING")).as("PROCESSING 사주 결과").isEqualTo(10);
			assertThat(countOfStatus("compatibility_results", "PROCESSING")).as("PROCESSING 궁합 결과").isEqualTo(10);
		}
	}

	/**
	 * 결제 1 의 해석 시작을 트랜잭션 안에서 부른 뒤 커밋하지 않고 멈춰 둔 채, 다른 스레드에서 결제 2 의 해석 시작을 부른다. 결제 2 가
	 * {@link #OTHER_ROW_TIMEOUT} 안에 끝나면 true 다. 돌려주기 전에 결제 1 을 풀어 주고 두 작업이 모두 끝나기를 기다려, 뒤 정리보다
	 * 늦게 커밋하는 일이 없게 한다.
	 */
	private boolean secondStartsWhileFirstHoldsLock(Consumer<Long> start, Long first, Long second)
		throws Exception {
		CountDownLatch firstHoldsLock = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> firstStart = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				start.accept(first);
				firstHoldsLock.countDown();
				awaitLatch(releaseFirst);
			}));
			assertThat(firstHoldsLock.await(10, SECONDS)).as("결제 1 이 조건부 UPDATE 로 행을 잡았다").isTrue();
			Future<?> secondStart = executor.submit(() -> start.accept(second));
			boolean finishedInTime = finishesWithin(secondStart, OTHER_ROW_TIMEOUT);
			releaseFirst.countDown();
			firstStart.get(10, SECONDS);
			secondStart.get(60, SECONDS);
			return finishedInTime;
		} finally {
			releaseFirst.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(60, SECONDS)).as("두 해석 시작이 끝났다").isTrue();
		}
	}

	private static boolean finishesWithin(Future<?> future, Duration timeout) throws Exception {
		try {
			future.get(timeout.toMillis(), MILLISECONDS);
			return true;
		} catch (TimeoutException e) {
			return false;
		} catch (ExecutionException e) {
			throw new IllegalStateException("결제 2 의 해석 시작이 실패했다.", e.getCause());
		}
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(30, SECONDS)).as("테스트 스레드가 멈춘 트랜잭션을 풀어 줬다").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("멈춘 트랜잭션을 기다리던 스레드가 중단됐다.", e);
		}
	}

	/** 기본 설정(60분)과 {@link #FIXED_CLOCK} 으로 되돌리기 작업을 만든다. */
	private StaleProcessingResultScheduler staleProcessingScheduler() {
		return new StaleProcessingResultScheduler(resultRepository, compatibilityResultRepository,
			new StaleProcessingProperties(null, null), FIXED_CLOCK);
	}

	private Long saveSaju(Long paymentId, ResultStatus status) {
		return resultRepository.save(ResultFixture.saju(userId, paymentId, status)).getId();
	}

	private void saveCompatibility(Long paymentId, ResultStatus status) {
		compatibilityResultRepository.save(ResultFixture.compatibility(userId, paymentId, status));
	}

	/** 저장한 뒤 updated_at 을 JPA 를 거치지 않고 정해 둔다. 저장 콜백(@PrePersist)이 넣는 실제 시각을 덮어쓴다. */
	private Long saveSajuUpdatedAt(Long paymentId, ResultStatus status, LocalDateTime updatedAt) {
		Long resultId = saveSaju(paymentId, status);
		jdbcTemplate.update("UPDATE results SET updated_at = ? WHERE payment_id = ?", updatedAt, paymentId);
		return resultId;
	}

	private void saveCompatibilityUpdatedAt(Long paymentId, ResultStatus status, LocalDateTime updatedAt) {
		saveCompatibility(paymentId, status);
		jdbcTemplate.update("UPDATE compatibility_results SET updated_at = ? WHERE payment_id = ?", updatedAt,
			paymentId);
	}

	/** 결과 행 하나의 상태와 마지막 변경 시각. */
	private record StatusRow(Long paymentId, String status, LocalDateTime updatedAt) {

	}

	private List<StatusRow> statusRowsOf(String table) {
		return jdbcTemplate.query("SELECT payment_id, status, updated_at FROM " + table
				+ " WHERE user_id = ? ORDER BY payment_id",
			(rs, rowNum) -> new StatusRow(rs.getLong("payment_id"), rs.getString("status"),
				rs.getObject("updated_at", LocalDateTime.class)),
			userId);
	}

	private String statusOf(String table, Long paymentId) {
		return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE payment_id = ?", String.class,
			paymentId);
	}

	private Map<String, Object> rowOf(String table, Long paymentId) {
		return jdbcTemplate.queryForMap("SELECT * FROM " + table + " WHERE payment_id = ?", paymentId);
	}

	private int countOfStatus(String table, String status) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table
			+ " WHERE user_id = ? AND status = ?", Integer.class, userId, status);
		return count == null ? 0 : count;
	}
}
