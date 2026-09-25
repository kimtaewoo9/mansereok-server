package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.fixture.ResultFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 해석을 시작하며 올린 상태(해석 중)를, 해석이 실패하거나 제출이 거부됐을 때 정보 입력 대기로 되돌리는지 확인한다.
 *
 * <p>되돌리는 길은 넷이다. 제출이 거부되면 컨트롤러가 결제 ID 로(ResultService), 해석이 실패하면 파이프라인이 결과 ID 로
 * (SajuResultService) 되돌린다. 각각 사주 결과와 궁합 결과가 있다. 저장소는 목이지만 돌려주는 엔티티는 진짜라서 엔티티가 실제로
 * 어떤 상태가 되는지 본다. 예전 테스트는 서비스나 엔티티를 목으로 바꿔 "되돌리기를 불렀다"만 확인했고, 그 사이 궁합 쪽 되돌리기는
 * 아무것도 바꾸지 못하고 있었다.
 *
 * <p>네 갈래 모두 되돌리는 쪽이 해석을 시작한 시각(STARTED_AT)을 넘긴다. 그 시각에 시작한 해석 중(되돌린다), 되돌린 뒤 다른 요청이
 * 다시 시작한 해석 중(그대로 둔다), 완료(그대로 둔다), 행 없음(예외 없이 끝난다)을 같은 순서로 두고, 결과 ID 갈래에는 ID 가 null
 * 인 경우를 더한다. 빠진 경우가 있으면 갈래끼리 견줘 바로 보이게 하려는 것이다. 해석 중 결과는 ResultFixture 로 만들고, 그 준비가
 * 실제로 해석 중인지 given 끝에서 먼저 확인한다. 그렇지 않으면 결과가 처음부터 정보 입력 대기라 되돌리기 테스트가 아무것도 되돌리지
 * 않고 통과한다. 해석을 시작하는 쪽(startProcessing)은 ResultStartProcessingTest 가 본다.
 *
 * <p>save 호출 여부는 보지 않는다. 트랜잭션 안에서 읽은 엔티티는 커밋 때 반영되며, 그 사실은 ResultRollbackMySqlTest 가 실제
 * MySQL 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ResultStatusRollbackTest {

	private static final Long PAYMENT_ID = 10L;
	private static final Long RESULT_ID = 20L;
	// 되돌리는 쪽이 해석을 시작한 시각과, 오래 멈춰 되돌려진 뒤 다른 요청이 같은 결제로 해석을 다시 시작한 시각.
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);
	private static final LocalDateTime RESTARTED_AT = LocalDateTime.of(2026, 9, 26, 10, 0);

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Mock
	private SubCategoryRepository subCategoryRepository;

	private ResultService resultService;
	private SajuResultService sajuResultService;

	@BeforeEach
	void setUp() {
		// 되돌리기는 시각을 쓰지 않는다. 시각이 필요한 해석 시작은 ResultStartProcessingTest 가 본다.
		resultService = new ResultService(resultRepository, compatibilityResultRepository,
			subCategoryRepository, Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneId.of("Asia/Seoul")));
		sajuResultService = new SajuResultService(resultRepository, compatibilityResultRepository);
	}

	@Nested
	@DisplayName("제출이 거부돼 결제 ID 로 궁합 결과를 되돌리면")
	class CompatibilityByPaymentId {

		@Test
		@DisplayName("이 요청이 시작한 해석 중 궁합 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			CompatibilityResult result = processingCompatibility();
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("되돌린 뒤 다른 요청이 다시 시작한 해석 중 궁합 결과는 해석 중 그대로 둔다")
		void keepsProcessingStartedByAnotherRequest() {
			// given
			CompatibilityResult result = compatibilityRestartedByAnotherRequest();
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("이미 완료된 궁합 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			CompatibilityResult result = completedCompatibility();
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 궁합 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("해석이 실패해 결과 ID 로 궁합 결과를 되돌리면")
	class CompatibilityByResultId {

		@Test
		@DisplayName("이 요청이 시작한 해석 중 궁합 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			CompatibilityResult result = processingCompatibility();
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			sajuResultService.rollbackCompatibilityStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("되돌린 뒤 다른 요청이 다시 시작한 해석 중 궁합 결과는 해석 중 그대로 둔다")
		void keepsProcessingStartedByAnotherRequest() {
			// given
			CompatibilityResult result = compatibilityRestartedByAnotherRequest();
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackCompatibilityStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("이미 완료된 궁합 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			CompatibilityResult result = completedCompatibility();
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackCompatibilityStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 궁합 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> sajuResultService.rollbackCompatibilityStatus(RESULT_ID, STARTED_AT))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("결과 ID 를 받기 전에 실패해 ID 가 null 이면 예외 없이 끝난다")
		void ignoresNullResultId() {
			// given
			// 서비스가 null 을 먼저 거르는지 보려고, 저장소를 null 로 부르면 예외를 던지게 스텁한다. 거르면 불리지 않는 스텁이라
			// lenient 로 둔다. 거르지 않으면 이 예외가 그대로 나와 테스트가 실패한다.
			lenient().when(compatibilityResultRepository.findByIdForUpdate(null))
				.thenThrow(new IllegalArgumentException("The given id must not be null"));

			// when & then
			assertThatCode(() -> sajuResultService.rollbackCompatibilityStatus(null, STARTED_AT))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("제출이 거부돼 결제 ID 로 사주 결과를 되돌리면")
	class SajuByPaymentId {

		@Test
		@DisplayName("이 요청이 시작한 해석 중 사주 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			Result result = processingSaju();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			resultService.rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("되돌린 뒤 다른 요청이 다시 시작한 해석 중 사주 결과는 해석 중 그대로 둔다")
		void keepsProcessingStartedByAnotherRequest() {
			// given
			Result result = sajuRestartedByAnotherRequest();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("이미 완료된 사주 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			Result result = completedSaju();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 사주 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> resultService.rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("해석이 실패해 결과 ID 로 사주 결과를 되돌리면")
	class SajuByResultId {

		@Test
		@DisplayName("이 요청이 시작한 해석 중 사주 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			Result result = processingSaju();
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			sajuResultService.rollbackStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("되돌린 뒤 다른 요청이 다시 시작한 해석 중 사주 결과는 해석 중 그대로 둔다")
		void keepsProcessingStartedByAnotherRequest() {
			// given
			Result result = sajuRestartedByAnotherRequest();
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("이미 완료된 사주 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			Result result = completedSaju();
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackStatus(RESULT_ID, STARTED_AT);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 사주 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> sajuResultService.rollbackStatus(RESULT_ID, STARTED_AT))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("결과 ID 를 받기 전에 실패해 ID 가 null 이면 예외 없이 끝난다")
		void ignoresNullResultId() {
			// given
			// 서비스가 null 을 먼저 거르는지 보려고, 저장소를 null 로 부르면 예외를 던지게 스텁한다. 거르면 불리지 않는 스텁이라
			// lenient 로 둔다. 거르지 않으면 이 예외가 그대로 나와 테스트가 실패한다.
			lenient().when(resultRepository.findByIdForUpdate(null))
				.thenThrow(new IllegalArgumentException("The given id must not be null"));

			// when & then
			assertThatCode(() -> sajuResultService.rollbackStatus(null, STARTED_AT))
				.doesNotThrowAnyException();
		}
	}

	private static CompatibilityResult processingCompatibility() {
		return ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
	}

	private static CompatibilityResult compatibilityRestartedByAnotherRequest() {
		return ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.PROCESSING, RESTARTED_AT);
	}

	private static CompatibilityResult completedCompatibility() {
		return ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.COMPLETED);
	}

	private static Result processingSaju() {
		return ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT);
	}

	private static Result sajuRestartedByAnotherRequest() {
		return ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING, RESTARTED_AT);
	}

	private static Result completedSaju() {
		return ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.COMPLETED);
	}
}
