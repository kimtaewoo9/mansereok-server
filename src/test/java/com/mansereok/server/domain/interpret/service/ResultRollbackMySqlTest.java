package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 해석 상태를 올리고 되돌린 결과가 실제 MySQL 행에 남는지 확인한다.
 *
 * <p>서비스는 트랜잭션 안에서 읽은 엔티티의 상태만 바꾸고 save 를 부르지 않는다. 바뀐 상태가 커밋 때 DB 에 반영되는지는 목
 * 저장소로 알 수 없어, 저장한 행을 서비스로 바꾼 뒤 JPA 캐시를 거치지 않고 JdbcTemplate 로 다시 읽는다.
 *
 * <p>해석 중 행은 검증 대상인 markProcessing 으로 만들어 저장하므로, 되돌리기 테스트는 저장한 행이 DB 에서 실제로
 * PROCESSING 인지 given 끝에서 먼저 확인한다. 그렇지 않으면 markProcessing 이 상태를 바꾸지 못할 때 행이 처음부터
 * INPUT_REQUIRED 라 아무것도 되돌리지 않고 통과한다.
 *
 * <p>행은 이번 실행의 결제 ID 로 만들고 뒤 정리에서 그 결제 ID 로만 지운다. 결과 표는 결제 표를 참조하지 않으므로 결제 행은
 * 만들지 않는다.
 */
class ResultRollbackMySqlTest extends InterpretationMySqlTest {

	@Autowired
	private ResultService resultService;

	@Autowired
	private SajuResultService sajuResultService;

	@Autowired
	private ResultRepository resultRepository;

	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 결제 ID(UNIQUE)가 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final Long paymentId = Long.parseLong(runId, 16);

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id = ?", paymentId);
		jdbcTemplate.update("DELETE FROM results WHERE payment_id = ?", paymentId);
	}

	@Nested
	@DisplayName("궁합 결과는")
	class Compatibility {

		@Test
		@DisplayName("제출이 거부돼 결제 ID 로 되돌리면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 가 된다")
		void rollbackByPaymentIdIsSaved() {
			// given
			saveCompatibility(processingCompatibility());
			assertThat(compatibilityStatusInDatabase()).as("준비: DB 에 해석 중으로 저장").isEqualTo("PROCESSING");

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(paymentId);

			// then
			assertThat(compatibilityStatusInDatabase()).isEqualTo("INPUT_REQUIRED");
		}

		@Test
		@DisplayName("해석이 실패해 결과 ID 로 되돌리면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 가 된다")
		void rollbackByResultIdIsSaved() {
			// given
			Long resultId = saveCompatibility(processingCompatibility());
			assertThat(compatibilityStatusInDatabase()).as("준비: DB 에 해석 중으로 저장").isEqualTo("PROCESSING");

			// when
			sajuResultService.rollbackCompatibilityStatus(resultId);

			// then
			assertThat(compatibilityStatusInDatabase()).isEqualTo("INPUT_REQUIRED");
		}

		@Test
		@DisplayName("해석을 시작하면 정보 입력 대기이던 행이 DB 에서 PROCESSING 이 된다")
		void markProcessingIsSaved() {
			// given
			saveCompatibility(CompatibilityResult.createInitial(1L, paymentId, "궁합 " + runId));

			// when
			resultService.updateCompatibilityStatusToProcessing(paymentId);

			// then
			assertThat(compatibilityStatusInDatabase()).isEqualTo("PROCESSING");
		}
	}

	@Nested
	@DisplayName("사주 결과는")
	class Saju {

		@Test
		@DisplayName("제출이 거부돼 결제 ID 로 되돌리면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 가 된다")
		void rollbackByPaymentIdIsSaved() {
			// given
			saveSaju(processingSaju());
			assertThat(sajuStatusInDatabase()).as("준비: DB 에 해석 중으로 저장").isEqualTo("PROCESSING");

			// when
			resultService.rollbackStatusByPaymentId(paymentId);

			// then
			assertThat(sajuStatusInDatabase()).isEqualTo("INPUT_REQUIRED");
		}

		@Test
		@DisplayName("해석이 실패해 결과 ID 로 되돌리면 해석 중이던 행이 DB 에서 INPUT_REQUIRED 가 된다")
		void rollbackByResultIdIsSaved() {
			// given
			Long resultId = saveSaju(processingSaju());
			assertThat(sajuStatusInDatabase()).as("준비: DB 에 해석 중으로 저장").isEqualTo("PROCESSING");

			// when
			sajuResultService.rollbackStatus(resultId);

			// then
			assertThat(sajuStatusInDatabase()).isEqualTo("INPUT_REQUIRED");
		}

		@Test
		@DisplayName("해석을 시작하면 정보 입력 대기이던 행이 DB 에서 PROCESSING 이 된다")
		void markProcessingIsSaved() {
			// given
			saveSaju(Result.createInitial(1L, paymentId, "사주 " + runId));

			// when
			resultService.updateStatusToProcessing(paymentId);

			// then
			assertThat(sajuStatusInDatabase()).isEqualTo("PROCESSING");
		}
	}

	private CompatibilityResult processingCompatibility() {
		CompatibilityResult result = CompatibilityResult.createInitial(1L, paymentId, "궁합 " + runId);
		result.markProcessing();
		return result;
	}

	private Result processingSaju() {
		Result result = Result.createInitial(1L, paymentId, "사주 " + runId);
		result.markProcessing();
		return result;
	}

	private Long saveCompatibility(CompatibilityResult result) {
		return compatibilityResultRepository.save(result).getId();
	}

	private Long saveSaju(Result result) {
		return resultRepository.save(result).getId();
	}

	private String compatibilityStatusInDatabase() {
		return jdbcTemplate.queryForObject("SELECT status FROM compatibility_results WHERE payment_id = ?",
			String.class, paymentId);
	}

	private String sajuStatusInDatabase() {
		return jdbcTemplate.queryForObject("SELECT status FROM results WHERE payment_id = ?", String.class,
			paymentId);
	}
}
