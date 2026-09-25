package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 비동기 해석의 첫 DB 단계(입력 정보 채우기)가 실패해도, 컨트롤러가 해석 중으로 바꿔 둔 결과가 실제 MySQL 에서 정보 입력 대기로
 * 돌아오는지 확인한다.
 *
 * <p>예전에는 되돌리기가 첫 DB 단계가 돌려주는 결과 ID 로만 결과를 찾아서, 그 단계가 실패하면 되돌릴 결과가 없어 행이 PROCESSING
 * 에 남았다. 이제는 결제 ID 로 되돌린다. 해석은 @Async 스레드(gptTaskExecutor)에서 돌므로 끝나기를 sleep 없이 Awaitility 로
 * 기다린다. 해석 중 행은 실제 해석 시작(startProcessing)으로 만들고, 그 시작이 돌려준 시각을 해석에 넘긴다.
 *
 * <p>SajuResultService 는 {@link MockitoSpyBean} 이다. 이번 실행의 결제 ID 로 부른 입력 정보 채우기만 DB 오류를 던지고, 나머지는
 * 진짜로 넘긴다. 스파이 때문에 이 클래스는 다른 해석 MySQL 테스트와 스프링 컨텍스트를 함께 쓰지 않는다.
 *
 * <p>행은 이번 실행의 결제 ID 로 만들고 뒤 정리에서 그 결제 ID 로만 지운다. 결과 표는 결제 표를 참조하지 않으므로 결제 행은
 * 만들지 않는다.
 */
class PipelineRollbackMySqlTest extends InterpretationMySqlTest {

	// 되돌리기는 제출한 해석이 대기열 없이 바로 돌면 수백 밀리초 안에 끝난다. 되돌리지 못하면 행은 끝까지 PROCESSING 이다.
	private static final Duration ROLLBACK_WAIT = Duration.ofSeconds(10);

	@MockitoSpyBean
	private SajuResultService sajuResultService;

	@Autowired
	private ManseInterpretationService manseInterpretationService;

	@Autowired
	private ResultService resultService;

	@Autowired
	private ResultRepository resultRepository;

	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 결제 ID(UNIQUE)가 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final Long paymentId = Long.parseLong(runId, 16);

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE payment_id = ?", paymentId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id = ?", paymentId);
	}

	@Test
	@DisplayName("사주 해석의 입력 정보 채우기가 DB 오류로 실패하면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 로 돌아오고 GPT 는 부르지 않는다")
	void sajuRowReturnsToInputRequiredWhenFillFails() {
		// given
		resultRepository.save(Result.createInitial(1L, paymentId, "사주 " + runId));
		LocalDateTime startedAt = resultService.startProcessing(paymentId);
		willThrow(new CannotAcquireLockException("잠금 대기 초과"))
			.given(sajuResultService).updateInitialStatus(eq(paymentId), eq(startedAt), any(), any(), anyString());
		assertThat(statusInDatabase("results")).as("준비: DB 에 해석 중으로 저장").isEqualTo("PROCESSING");

		// when
		manseInterpretationService.interpret("홍길동", PromptFixtures.person1(), "user-" + runId, 1L, paymentId,
			startedAt, null);

		// then
		await().atMost(ROLLBACK_WAIT).untilAsserted(() ->
			assertThat(statusInDatabase("results")).isEqualTo("INPUT_REQUIRED"));
		verify(openAiResponsesClient, never()).createResponse(any());
	}

	@Test
	@DisplayName("궁합 해석의 입력 정보 채우기가 DB 오류로 실패하면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 로 돌아오고 GPT 는 부르지 않는다")
	void compatibilityRowReturnsToInputRequiredWhenFillFails() {
		// given
		compatibilityResultRepository.save(CompatibilityResult.createInitial(1L, paymentId, "궁합 " + runId));
		LocalDateTime startedAt = resultService.startCompatibilityProcessing(paymentId);
		willThrow(new CannotAcquireLockException("잠금 대기 초과"))
			.given(sajuResultService).updateCompatibilityInitialStatus(eq(paymentId), eq(startedAt), any(), any(),
				any(), any());
		assertThat(statusInDatabase("compatibility_results")).as("준비: DB 에 해석 중으로 저장")
			.isEqualTo("PROCESSING");

		// when
		manseInterpretationService.analyzeCompatibilityWithSubcategory("홍길동", PromptFixtures.person1(), "김영희",
			PromptFixtures.person2(), 4L, paymentId, startedAt, "user-" + runId, null, null);

		// then
		await().atMost(ROLLBACK_WAIT).untilAsserted(() ->
			assertThat(statusInDatabase("compatibility_results")).isEqualTo("INPUT_REQUIRED"));
		verify(openAiResponsesClient, never()).createResponse(any());
	}

	private String statusInDatabase(String table) {
		return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE payment_id = ?", String.class,
			paymentId);
	}
}
