package com.mansereok.server.domain.interpret.service;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.exception.InterpretationRunOutdatedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
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
import org.junit.jupiter.params.provider.CsvSource;
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
 * <p>되돌린 뒤 사용자가 같은 결제로 해석을 다시 시작하면, 먼저 시작한 해석이 늦게 끝나 결과를 쓰려 해도 해석을 시작한 시각이
 * 달라 거부되는지 본다. 다시 시작한 해석의 입력 정보와 본문이 짝지어 남아야 한다.
 *
 * <p>되돌리기 작업은 고정 시계로 직접 만들어 부른다. 그 시계는 실제 시각보다 뒤(2100년)로 두어, 애플리케이션 안에서 실제 시계로
 * 도는 같은 작업이 이 테스트가 과거로 옮긴 행을 먼저 되돌리지 못하게 한다. 대신 이 되돌리기는 같은 테스트 DB 에 있는 모든 해석 중
 * 행을 되돌리고 updated_at 을 2100년으로 찍는다. 그래서 돌리기 전에 이번 실행이 만들지 않은 해석 중 행이 있는지 보고, 있으면 남의
 * 행을 바꾸지 않도록 그 테스트를 건너뛴다({@link #assumeNoProcessingRowsOfOtherRuns()}).
 *
 * <p>행은 이번 실행의 사용자 ID 로 만들고 뒤 정리에서 그 사용자 ID 로만 지운다. 결과 표는 결제 표를 참조하지 않으므로 결제 행은
 * 만들지 않는다.
 */
class ResultStartOnceMySqlTest extends InterpretationMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리느라 느려진다.
	private static final int REQUEST_COUNT = 10;

	// 2100-01-01 09:00 (서울). 실제 시각보다 뒤라서 실제 시계로 도는 되돌리기 작업의 기준 시각보다 늘 늦다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2100, 1, 1, 9, 0);
	private static final LocalDateTime SEVENTY_MINUTES_AGO = LocalDateTime.of(2100, 1, 1, 7, 50);
	// 되돌린 뒤 다시 시작하는 순서에서, 먼저 시작한 해석(A)이 해석을 시작한 시각. 되돌리기 기준(60분)을 넘긴다.
	private static final LocalDateTime FIRST_STARTED_AT = SEVENTY_MINUTES_AGO;

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

		@ParameterizedTest(name = "[{index}] {1} 을 {0} 에 시작 → {2}, updated_at {3}")
		@CsvSource(textBlock = """
			# 해석 시작(updated_at), 지금 상태,     되돌리기 뒤 상태, 되돌리기 뒤 updated_at
			# 60분이 넘으면 되돌린다. 1초 넘침과 넉넉히 넘침
			2100-01-01T07:59:59,   PROCESSING,     INPUT_REQUIRED,  2100-01-01T09:00:00
			2100-01-01T07:50:00,   PROCESSING,     INPUT_REQUIRED,  2100-01-01T09:00:00
			# 정확히 60분, 1초 모자람, 넉넉히 모자람은 그대로 둔다
			2100-01-01T08:00:00,   PROCESSING,     PROCESSING,      2100-01-01T08:00:00
			2100-01-01T08:00:01,   PROCESSING,     PROCESSING,      2100-01-01T08:00:01
			2100-01-01T08:10:00,   PROCESSING,     PROCESSING,      2100-01-01T08:10:00
			# 해석 중이 아니면 오래됐어도 그대로 둔다
			2100-01-01T07:50:00,   COMPLETED,      COMPLETED,       2100-01-01T07:50:00
			2100-01-01T07:50:00,   INPUT_REQUIRED, INPUT_REQUIRED,  2100-01-01T07:50:00
			""")
		@DisplayName("두 표 모두 해석을 시작한 지 60분이 넘은 해석 중 결과만 지금 시각으로 되돌리고 나머지는 그대로 둔다")
		void revertsOnlyProcessingStartedMoreThanStaleAfterAgo(LocalDateTime updatedAt, ResultStatus current,
			String expectedStatus, LocalDateTime expectedUpdatedAt) {
			// given
			saveSajuUpdatedAt(runKey + 1, current, updatedAt);
			saveCompatibilityUpdatedAt(runKey + 1, current, updatedAt);
			assumeNoProcessingRowsOfOtherRuns();

			// when
			staleProcessingScheduler().revertStaleProcessingResults();

			// then
			StatusRow expected = new StatusRow(runKey + 1, expectedStatus, expectedUpdatedAt);
			assertThat(statusRowsOf("results")).as("사주 결과").containsExactly(expected);
			assertThat(statusRowsOf("compatibility_results")).as("궁합 결과").containsExactly(expected);
		}

		@Test
		@DisplayName("해석 저장 트랜잭션이 행을 잡고 있을 때 겹친 되돌리기는 저장이 끝나기를 기다렸다가 완료된 결과를 그대로 둔다")
		void doesNotRevertResultCompletedMeanwhile() throws Exception {
			// given: 되돌리기 기준을 넘길 만큼 오래전에 시작한 해석 중 결과
			Long paymentId = runKey + 1;
			Long resultId = saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			assumeNoProcessingRowsOfOtherRuns();
			CountDownLatch savedAndHoldingRow = new CountDownLatch(1);
			CountDownLatch releaseSave = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<?> save = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					sajuResultService.saveFinalResult(resultId, SEVENTY_MINUTES_AGO, "늦게 끝난 본문", "늦게 끝난 요약");
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
		@DisplayName("되돌린 뒤에 늦게 끝난 해석을 저장하면 InterpretationRunOutdatedException 으로 거부되고 행은 정보 입력 대기 그대로다")
		void rejectsLateSaveAfterRevert() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, SEVENTY_MINUTES_AGO);
			assumeNoProcessingRowsOfOtherRuns();
			staleProcessingScheduler().revertStaleProcessingResults();

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(resultId, SEVENTY_MINUTES_AGO, "늦게 끝난 본문",
				"늦게 끝난 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class)
				.hasMessageContaining("이 해석을 시작한 시각=2100-01-01T07:50, 지금 상태=INPUT_REQUIRED");
			Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, interpretation FROM results WHERE payment_id = ?", paymentId);
			assertThat(row.get("status")).isEqualTo("INPUT_REQUIRED");
			assertThat(row.get("interpretation")).isNull();
		}
	}

	/**
	 * 먼저 시작한 해석(A)이 70분 전에 시작해 입력 정보(철수)를 채운 채 멈춰 있다. 되돌리기가 돌고, 사용자가 같은 결제로 해석(B)을
	 * 다시 시작해 입력 정보(영희)를 채운다. 그 뒤 A 가 늦게 깨어나 결과를 쓰려는 순서다. A 가 쓸 수 있다면 영희의 입력 정보에 A 의
	 * 본문이 붙어 완료되고, B 의 저장은 완료된 결과라 거부된다.
	 */
	@Nested
	@DisplayName("오래 멈춰 되돌린 결과를 사용자가 같은 결제로 다시 시작한 뒤")
	class RestartAfterRevert {

		@Test
		@DisplayName("먼저 시작한 해석의 늦은 저장은 InterpretationRunOutdatedException 으로 거부되고 다시 시작한 해석 중 행은 그대로다")
		void rejectsLateSaveOfFirstRun() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveSajuStartedByFirstRun(paymentId);
			restartSajuBySecondRun(paymentId);
			Map<String, Object> before = rowOf("results", paymentId);

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(resultId, FIRST_STARTED_AT, "A 본문", "A 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class)
				.hasMessageContaining("이 해석을 시작한 시각=2100-01-01T07:50, 지금 상태=PROCESSING");
			assertThat(rowOf("results", paymentId)).isEqualTo(before);
		}

		@Test
		@DisplayName("먼저 시작한 해석의 늦은 저장이 거부된 뒤 다시 시작한 해석이 저장하면 그 해석의 입력 정보와 본문이 짝지어 완료로 남는다")
		void keepsSecondRunInformationWithItsInterpretation() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveSajuStartedByFirstRun(paymentId);
			LocalDateTime secondStartedAt = restartSajuBySecondRun(paymentId);
			Throwable lateSave = catchThrowable(
				() -> sajuResultService.saveFinalResult(resultId, FIRST_STARTED_AT, "A 본문", "A 요약"));
			assertThat(lateSave).as("준비: 먼저 시작한 해석의 늦은 저장이 거부됐다")
				.isInstanceOf(InterpretationRunOutdatedException.class);

			// when
			sajuResultService.saveFinalResult(resultId, secondStartedAt, "B 본문", "B 요약");

			// then
			assertThat(jdbcTemplate.queryForMap("SELECT status, name, interpretation FROM results WHERE payment_id = ?",
				paymentId)).isEqualTo(Map.of("status", "COMPLETED", "name", "영희", "interpretation", "B 본문"));
		}

		@Test
		@DisplayName("대기열에 있던 먼저 시작한 해석이 늦게 입력 정보를 채우려 하면 거부되고 다시 시작한 해석의 입력 정보는 그대로다")
		void rejectsLateFillOfFirstRun() {
			// given: A 는 해석을 시작만 하고 대기열에서 기다리다 되돌려졌다
			Long paymentId = runKey + 1;
			saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, FIRST_STARTED_AT);
			restartSajuBySecondRun(paymentId);
			Map<String, Object> before = rowOf("results", paymentId);

			// when & then
			assertThatThrownBy(() -> sajuResultService.updateInitialStatus(paymentId, FIRST_STARTED_AT, "철수",
				PromptFixtures.person1(), "갑목"))
				.isInstanceOf(InterpretationRunOutdatedException.class);
			assertThat(rowOf("results", paymentId)).isEqualTo(before);
		}

		@Test
		@DisplayName("먼저 시작한 해석이 실패해 되돌리기를 불러도 다시 시작한 해석 중 상태를 되돌리지 않는다")
		void doesNotRevertSecondRunWhenFirstRunFails() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveSajuStartedByFirstRun(paymentId);
			LocalDateTime secondStartedAt = restartSajuBySecondRun(paymentId);

			// when
			sajuResultService.rollbackStatus(resultId, FIRST_STARTED_AT);

			// then
			assertThat(statusRowsOf("results")).containsExactly(new StatusRow(paymentId, "PROCESSING", secondStartedAt));
		}

		@Test
		@DisplayName("궁합 결과도 먼저 시작한 해석의 늦은 저장은 InterpretationRunOutdatedException 으로 거부되고 다시 시작한 해석 중 행은 그대로다")
		void rejectsLateCompatibilitySaveOfFirstRun() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveCompatibilityStartedByFirstRun(paymentId);
			restartCompatibilityBySecondRun(paymentId);
			Map<String, Object> before = rowOf("compatibility_results", paymentId);

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveCompatibilityFinalResult(resultId, FIRST_STARTED_AT, "A 본문",
				70, "A 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class)
				.hasMessageContaining("이 해석을 시작한 시각=2100-01-01T07:50, 지금 상태=PROCESSING");
			assertThat(rowOf("compatibility_results", paymentId)).isEqualTo(before);
		}

		@Test
		@DisplayName("궁합 결과도 다시 시작한 해석이 저장하면 그 해석의 두 사람 정보와 본문이 짝지어 완료로 남는다")
		void keepsSecondRunPersonsWithItsInterpretation() {
			// given
			Long paymentId = runKey + 1;
			Long resultId = saveCompatibilityStartedByFirstRun(paymentId);
			LocalDateTime secondStartedAt = restartCompatibilityBySecondRun(paymentId);
			Throwable lateSave = catchThrowable(() -> sajuResultService.saveCompatibilityFinalResult(resultId,
				FIRST_STARTED_AT, "A 본문", 70, "A 요약"));
			assertThat(lateSave).as("준비: 먼저 시작한 해석의 늦은 저장이 거부됐다")
				.isInstanceOf(InterpretationRunOutdatedException.class);

			// when
			sajuResultService.saveCompatibilityFinalResult(resultId, secondStartedAt, "B 본문", 90, "B 요약");

			// then
			assertThat(jdbcTemplate.queryForMap("SELECT status, person1_name, person2_name, interpretation "
				+ "FROM compatibility_results WHERE payment_id = ?", paymentId))
				.isEqualTo(Map.of("status", "COMPLETED", "person1_name", "영희", "person2_name", "민수",
					"interpretation", "B 본문"));
		}

		/** A 가 FIRST_STARTED_AT 에 해석을 시작하고 입력 정보(철수)를 채운 사주 결과를 만들고 결과 ID 를 돌려준다. */
		private Long saveSajuStartedByFirstRun(Long paymentId) {
			Long resultId = saveSajuUpdatedAt(paymentId, ResultStatus.PROCESSING, FIRST_STARTED_AT);
			sajuResultService.updateInitialStatus(paymentId, FIRST_STARTED_AT, "철수", PromptFixtures.person1(), "갑목");
			return resultId;
		}

		/** 되돌리기를 돌린 뒤 B 가 같은 결제로 해석을 다시 시작하고 입력 정보(영희)를 채운다. B 가 해석을 시작한 시각을 돌려준다. */
		private LocalDateTime restartSajuBySecondRun(Long paymentId) {
			assumeNoProcessingRowsOfOtherRuns();
			staleProcessingScheduler().revertStaleProcessingResults();
			LocalDateTime secondStartedAt = resultService.startProcessing(paymentId);
			sajuResultService.updateInitialStatus(paymentId, secondStartedAt, "영희", PromptFixtures.person2(), "을목");
			return secondStartedAt;
		}

		/** A 가 FIRST_STARTED_AT 에 궁합 해석을 시작하고 두 사람(철수·영수)을 채운 궁합 결과를 만들고 결과 ID 를 돌려준다. */
		private Long saveCompatibilityStartedByFirstRun(Long paymentId) {
			Long resultId = saveCompatibilityUpdatedAt(paymentId, ResultStatus.PROCESSING, FIRST_STARTED_AT);
			sajuResultService.updateCompatibilityInitialStatus(paymentId, FIRST_STARTED_AT, "철수", "갑목", "영수", "병화");
			return resultId;
		}

		/** 되돌리기를 돌린 뒤 B 가 같은 결제로 궁합 해석을 다시 시작하고 두 사람(영희·민수)을 채운다. B 의 시작 시각을 돌려준다. */
		private LocalDateTime restartCompatibilityBySecondRun(Long paymentId) {
			assumeNoProcessingRowsOfOtherRuns();
			staleProcessingScheduler().revertStaleProcessingResults();
			LocalDateTime secondStartedAt = resultService.startCompatibilityProcessing(paymentId);
			sajuResultService.updateCompatibilityInitialStatus(paymentId, secondStartedAt, "영희", "을목", "민수", "정화");
			return secondStartedAt;
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

	private Long saveCompatibility(Long paymentId, ResultStatus status) {
		return compatibilityResultRepository.save(ResultFixture.compatibility(userId, paymentId, status)).getId();
	}

	/** 저장한 뒤 updated_at 을 JPA 를 거치지 않고 정해 둔다. 저장 콜백(@PrePersist)이 넣는 실제 시각을 덮어쓴다. */
	private Long saveSajuUpdatedAt(Long paymentId, ResultStatus status, LocalDateTime updatedAt) {
		Long resultId = saveSaju(paymentId, status);
		jdbcTemplate.update("UPDATE results SET updated_at = ? WHERE payment_id = ?", updatedAt, paymentId);
		return resultId;
	}

	private Long saveCompatibilityUpdatedAt(Long paymentId, ResultStatus status, LocalDateTime updatedAt) {
		Long resultId = saveCompatibility(paymentId, status);
		jdbcTemplate.update("UPDATE compatibility_results SET updated_at = ? WHERE payment_id = ?", updatedAt,
			paymentId);
		return resultId;
	}

	/**
	 * 2100년 시계의 되돌리기는 이번 실행의 행뿐 아니라 같은 DB 의 모든 해석 중 행을 되돌린다. 이번 실행이 만들지 않은 해석 중 행이
	 * 있으면 남의 행을 바꾸지 않도록 테스트를 건너뛴다. 건너뛰었다면 그 행을 지우고 다시 돌린다.
	 */
	private void assumeNoProcessingRowsOfOtherRuns() {
		assumeThat(processingRowsOfOtherRuns("results") + processingRowsOfOtherRuns("compatibility_results"))
			.as("이번 실행이 만들지 않은 해석 중 행 수(되돌리기가 그 행을 바꾸므로 건너뛴다)")
			.isZero();
	}

	private int processingRowsOfOtherRuns(String table) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table
			+ " WHERE status = 'PROCESSING' AND (user_id IS NULL OR user_id <> ?)", Integer.class, userId);
		return count == null ? 0 : count;
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
