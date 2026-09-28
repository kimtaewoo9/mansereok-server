package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
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
 * <p>네 갈래 모두 해석 중(되돌린다), 완료(그대로 둔다), 행 없음(예외 없이 끝난다)을 같은 순서로 두고, 결과 ID 갈래에는 ID 가
 * null 인 경우를 더한다. 빠진 경우가 있으면 갈래끼리 견줘 바로 보이게 하려는 것이다. 해석 중 결과는 검증 대상인 markProcessing
 * 으로 만들므로, 그 준비가 실제로 해석 중이 됐는지 given 끝에서 먼저 확인한다. 그렇지 않으면 markProcessing 이 상태를 바꾸지
 * 못할 때 결과가 처음부터 정보 입력 대기라 되돌리기 테스트가 아무것도 되돌리지 않고 통과한다.
 *
 * <p>save 호출 여부는 보지 않는다. 트랜잭션 안에서 읽은 엔티티는 커밋 때 반영되며, 그 사실은 ResultRollbackMySqlTest 가 실제
 * MySQL 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ResultStatusRollbackTest {

	private static final Long PAYMENT_ID = 10L;
	private static final Long RESULT_ID = 20L;

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
		resultService = new ResultService(resultRepository, compatibilityResultRepository,
			subCategoryRepository);
		sajuResultService = new SajuResultService(resultRepository, compatibilityResultRepository);
	}

	@Nested
	@DisplayName("제출이 거부돼 결제 ID 로 궁합 결과를 되돌리면")
	class CompatibilityByPaymentId {

		@Test
		@DisplayName("해석 중이던 궁합 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			CompatibilityResult result = processingCompatibility();
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("이미 완료된 궁합 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			CompatibilityResult result = completedCompatibility();
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 궁합 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> resultService.rollbackCompatibilityStatusByPaymentId(PAYMENT_ID))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("해석이 실패해 결과 ID 로 궁합 결과를 되돌리면")
	class CompatibilityByResultId {

		@Test
		@DisplayName("해석 중이던 궁합 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			CompatibilityResult result = processingCompatibility();
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			sajuResultService.rollbackCompatibilityStatus(RESULT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("이미 완료된 궁합 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			CompatibilityResult result = completedCompatibility();
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackCompatibilityStatus(RESULT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 궁합 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> sajuResultService.rollbackCompatibilityStatus(RESULT_ID))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("결과 ID 를 받기 전에 실패해 ID 가 null 이면 예외 없이 끝난다")
		void ignoresNullResultId() {
			// given
			// 실제 저장소(SimpleJpaRepository.findById)는 null ID 를 받으면 이 예외를 던진다. 서비스가 null 을 먼저 거르면
			// 불리지 않는 스텁이라 lenient 로 둔다. 거르지 않으면 이 예외가 그대로 나와 테스트가 실패한다.
			lenient().when(compatibilityResultRepository.findById(null))
				.thenThrow(new IllegalArgumentException("The given id must not be null"));

			// when & then
			assertThatCode(() -> sajuResultService.rollbackCompatibilityStatus(null))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("제출이 거부돼 결제 ID 로 사주 결과를 되돌리면")
	class SajuByPaymentId {

		@Test
		@DisplayName("해석 중이던 사주 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			Result result = processingSaju();
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			resultService.rollbackStatusByPaymentId(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("이미 완료된 사주 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			Result result = completedSaju();
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.rollbackStatusByPaymentId(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 사주 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> resultService.rollbackStatusByPaymentId(PAYMENT_ID))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("해석이 실패해 결과 ID 로 사주 결과를 되돌리면")
	class SajuByResultId {

		@Test
		@DisplayName("해석 중이던 사주 결과는 정보 입력 대기로 돌아간다")
		void revertsProcessing() {
			// given
			Result result = processingSaju();
			given(resultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));
			assertThat(result.getStatus()).as("준비: 해석 중").isEqualTo(ResultStatus.PROCESSING);

			// when
			sajuResultService.rollbackStatus(RESULT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("이미 완료된 사주 결과는 완료 그대로 둔다")
		void keepsCompleted() {
			// given
			Result result = completedSaju();
			given(resultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));

			// when
			sajuResultService.rollbackStatus(RESULT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.COMPLETED);
		}

		@Test
		@DisplayName("결과 ID 에 해당하는 사주 결과가 없으면 예외 없이 끝난다")
		void ignoresMissingResult() {
			// given
			given(resultRepository.findById(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatCode(() -> sajuResultService.rollbackStatus(RESULT_ID))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("결과 ID 를 받기 전에 실패해 ID 가 null 이면 예외 없이 끝난다")
		void ignoresNullResultId() {
			// given
			// 실제 저장소(SimpleJpaRepository.findById)는 null ID 를 받으면 이 예외를 던진다. 서비스가 null 을 먼저 거르면
			// 불리지 않는 스텁이라 lenient 로 둔다. 거르지 않으면 이 예외가 그대로 나와 테스트가 실패한다.
			lenient().when(resultRepository.findById(null))
				.thenThrow(new IllegalArgumentException("The given id must not be null"));

			// when & then
			assertThatCode(() -> sajuResultService.rollbackStatus(null))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("해석을 시작하며 결제 ID 로 상태를 해석 중으로 올리면")
	class MarkProcessingByPaymentId {

		@Test
		@DisplayName("정보 입력 대기인 궁합 결과는 해석 중이 된다")
		void marksCompatibility() {
			// given
			CompatibilityResult result = CompatibilityResult.createInitial(1L, PAYMENT_ID, "연인 궁합");
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.updateCompatibilityStatusToProcessing(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("정보 입력 대기인 사주 결과는 해석 중이 된다")
		void marksSaju() {
			// given
			Result result = Result.createInitial(1L, PAYMENT_ID, "인생 총운");
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(result));

			// when
			resultService.updateStatusToProcessing(PAYMENT_ID);

			// then
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@Test
		@DisplayName("결제 ID 에 해당하는 궁합 결과가 없으면 EntityNotFoundException 을 던진다")
		void failsWhenCompatibilityMissing() {
			// given
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> resultService.updateCompatibilityStatusToProcessing(PAYMENT_ID))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("CompatibilityResult not found");
		}
	}

	private static CompatibilityResult processingCompatibility() {
		CompatibilityResult result = CompatibilityResult.createInitial(1L, PAYMENT_ID, "연인 궁합");
		result.markProcessing();
		return result;
	}

	private static CompatibilityResult completedCompatibility() {
		CompatibilityResult result = CompatibilityResult.createInitial(1L, PAYMENT_ID, "연인 궁합");
		result.completeInterpretation("궁합 본문", 80, "궁합 요약");
		return result;
	}

	private static Result processingSaju() {
		Result result = Result.createInitial(1L, PAYMENT_ID, "인생 총운");
		result.markProcessing();
		return result;
	}

	private static Result completedSaju() {
		Result result = Result.createInitial(1L, PAYMENT_ID, "인생 총운");
		result.completeInterpretation("사주 본문", "사주 요약");
		return result;
	}
}
