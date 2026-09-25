package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.exception.InterpretationRunOutdatedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.support.fixture.ResultFixture;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 해석 실행이 결과를 쓸 때(입력 정보 채우기, 결과 저장) 자기가 해석을 시작한 결과에만 쓰는지 확인한다.
 *
 * <p>해석 실행은 컨트롤러가 해석을 시작한 시각(STARTED_AT)을 들고 다닌다. 결과가 그 시각에 시작한 해석 중이면 쓰고, 오래 멈춰
 * 되돌려졌거나(정보 입력 대기) 그 뒤 다른 요청이 다시 시작했거나(다른 시각의 해석 중) 이미 완료됐으면 InterpretationRunOutdatedException
 * 으로 멈추고 어떤 필드도 바꾸지 않는다. 네 갈래(사주·궁합 × 채우기·저장)를 같은 순서로 둔다.
 *
 * <p>저장소는 잠금 조회가 돌려줄 엔티티만 정한다. 엔티티는 진짜라서 실제로 무엇이 바뀌었는지 본다. 잠금이 확인과 쓰기 사이에 되돌리기를
 * 막는지는 ResultStartOnceMySqlTest 가 실제 MySQL 로 본다.
 */
@ExtendWith(MockitoExtension.class)
class SajuResultServiceWriteTest {

	private static final Long PAYMENT_ID = 10L;
	private static final Long RESULT_ID = 20L;
	// 이 실행이 해석을 시작한 시각.
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	private SajuResultService sajuResultService;

	@BeforeEach
	void setUp() {
		sajuResultService = new SajuResultService(resultRepository, compatibilityResultRepository);
	}

	@Nested
	@DisplayName("사주 결과에 입력 정보를 채울 때")
	class SajuFill {

		@Test
		@DisplayName("이 실행이 시작한 해석 중이면 입력 정보를 채우고 상태와 해석 시작 시각은 그대로 둔다")
		void fillsResultStartedByThisRun() {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.updateInitialStatus(PAYMENT_ID, STARTED_AT, "영희", PromptFixtures.person2(), "을목");

			// then
			assertThat(result)
				.extracting(Result::getName, Result::getIlgan, Result::getStatus, Result::getUpdatedAt)
				.containsExactly("영희", "을목", ResultStatus.PROCESSING, STARTED_AT);
		}

		@ParameterizedTest(name = "[{index}] {0}, updated_at {1}")
		@CsvSource(textBlock = """
			# 지금 상태,     결과의 updated_at
			# 오래 멈춰 되돌려졌다(되돌리기가 updated_at 을 그때로 바꿨다)
			INPUT_REQUIRED, 2026-09-26T10:00:00
			# 되돌린 뒤 다른 요청이 같은 결제로 다시 시작했다
			PROCESSING,     2026-09-26T10:00:00
			# 이미 완료됐다
			COMPLETED,      2026-09-26T10:00:00
			""")
		@DisplayName("이 실행이 시작한 해석 중이 아니면 InterpretationRunOutdatedException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsResultNotStartedByThisRun(ResultStatus status, LocalDateTime updatedAt) {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, status, updatedAt);
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when & then
			assertThatThrownBy(() -> sajuResultService.updateInitialStatus(PAYMENT_ID, STARTED_AT, "영희",
				PromptFixtures.person2(), "을목"))
				.isInstanceOf(InterpretationRunOutdatedException.class);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(ResultFixture.saju(1L, PAYMENT_ID, status, updatedAt));
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 결과가 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> sajuResultService.updateInitialStatus(PAYMENT_ID, STARTED_AT, "영희",
				PromptFixtures.person2(), "을목"))
				.isInstanceOf(EntityNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("사주 결과를 저장할 때")
	class SajuSave {

		@Test
		@DisplayName("이 실행이 시작한 해석 중이면 본문과 요약을 넣고 완료로 바꾼다")
		void completesResultStartedByThisRun() {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.saveFinalResult(RESULT_ID, STARTED_AT, "새 본문", "새 요약");

			// then
			assertThat(result)
				.extracting(Result::getInterpretation, Result::getSummary, Result::getStatus)
				.containsExactly("새 본문", "새 요약", ResultStatus.COMPLETED);
		}

		@ParameterizedTest(name = "[{index}] {0}, updated_at {1}")
		@CsvSource(textBlock = """
			# 지금 상태,     결과의 updated_at
			INPUT_REQUIRED, 2026-09-26T10:00:00
			PROCESSING,     2026-09-26T10:00:00
			COMPLETED,      2026-09-26T10:00:00
			""")
		@DisplayName("이 실행이 시작한 해석 중이 아니면 InterpretationRunOutdatedException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsResultNotStartedByThisRun(ResultStatus status, LocalDateTime updatedAt) {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, status, updatedAt);
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(RESULT_ID, STARTED_AT, "새 본문", "새 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(ResultFixture.saju(1L, PAYMENT_ID, status, updatedAt));
		}

		@Test
		@DisplayName("거부할 때 예외에 결제 ID, 이 실행의 해석 시작 시각, 결과의 지금 상태와 updated_at 을 담는다")
		void describesWhyItStopped() {
			// given: 되돌린 뒤 다른 요청이 10시에 다시 시작한 결과
			Result result = ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING,
				LocalDateTime.of(2026, 9, 26, 10, 0));
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(RESULT_ID, STARTED_AT, "새 본문", "새 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class)
				.hasMessage("해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 해석을 다시 시작해 이 해석은 결과를 쓰지 않는다. "
					+ "paymentId=10, 이 해석을 시작한 시각=2026-09-26T09:00, 지금 상태=PROCESSING, "
					+ "지금 updated_at=2026-09-26T10:00");
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 결과가 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveFinalResult(RESULT_ID, STARTED_AT, "새 본문", "새 요약"))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("Result not found: 20");
		}
	}

	@Nested
	@DisplayName("궁합 결과에 두 사람 정보를 채울 때")
	class CompatibilityFill {

		@Test
		@DisplayName("이 실행이 시작한 해석 중이면 두 사람 정보를 채우고 상태와 해석 시작 시각은 그대로 둔다")
		void fillsResultStartedByThisRun() {
			// given
			CompatibilityResult result = ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.updateCompatibilityInitialStatus(PAYMENT_ID, STARTED_AT, "영희", "을목", "민수", "정화");

			// then
			assertThat(result)
				.extracting(CompatibilityResult::getPerson1Name, CompatibilityResult::getPerson2Name,
					CompatibilityResult::getStatus, CompatibilityResult::getUpdatedAt)
				.containsExactly("영희", "민수", ResultStatus.PROCESSING, STARTED_AT);
		}

		@ParameterizedTest(name = "[{index}] {0}, updated_at {1}")
		@CsvSource(textBlock = """
			# 지금 상태,     결과의 updated_at
			INPUT_REQUIRED, 2026-09-26T10:00:00
			PROCESSING,     2026-09-26T10:00:00
			COMPLETED,      2026-09-26T10:00:00
			""")
		@DisplayName("이 실행이 시작한 해석 중이 아니면 InterpretationRunOutdatedException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsResultNotStartedByThisRun(ResultStatus status, LocalDateTime updatedAt) {
			// given
			CompatibilityResult result = ResultFixture.compatibility(1L, PAYMENT_ID, status, updatedAt);
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when & then
			assertThatThrownBy(() -> sajuResultService.updateCompatibilityInitialStatus(PAYMENT_ID, STARTED_AT, "영희",
				"을목", "민수", "정화"))
				.isInstanceOf(InterpretationRunOutdatedException.class);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(ResultFixture.compatibility(1L, PAYMENT_ID, status, updatedAt));
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 궁합 결과가 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> sajuResultService.updateCompatibilityInitialStatus(PAYMENT_ID, STARTED_AT, "영희",
				"을목", "민수", "정화"))
				.isInstanceOf(EntityNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("궁합 결과를 저장할 때")
	class CompatibilitySave {

		@Test
		@DisplayName("이 실행이 시작한 해석 중이면 본문·점수·요약을 넣고 완료로 바꾼다")
		void completesResultStartedByThisRun() {
			// given
			CompatibilityResult result = ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "새 본문", 95, "새 요약");

			// then
			assertThat(result)
				.extracting(CompatibilityResult::getInterpretation, CompatibilityResult::getCompatibilityScore,
					CompatibilityResult::getSummary, CompatibilityResult::getStatus)
				.containsExactly("새 본문", 95, "새 요약", ResultStatus.COMPLETED);
		}

		@ParameterizedTest(name = "[{index}] {0}, updated_at {1}")
		@CsvSource(textBlock = """
			# 지금 상태,     결과의 updated_at
			INPUT_REQUIRED, 2026-09-26T10:00:00
			PROCESSING,     2026-09-26T10:00:00
			COMPLETED,      2026-09-26T10:00:00
			""")
		@DisplayName("이 실행이 시작한 해석 중이 아니면 InterpretationRunOutdatedException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsResultNotStartedByThisRun(ResultStatus status, LocalDateTime updatedAt) {
			// given
			CompatibilityResult result = ResultFixture.compatibility(1L, PAYMENT_ID, status, updatedAt);
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "새 본문", 95,
				"새 요약"))
				.isInstanceOf(InterpretationRunOutdatedException.class);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(ResultFixture.compatibility(1L, PAYMENT_ID, status, updatedAt));
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 궁합 결과가 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> sajuResultService.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "새 본문", 95,
				"새 요약"))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("CompatibilityResult not found: 20");
		}
	}
}
