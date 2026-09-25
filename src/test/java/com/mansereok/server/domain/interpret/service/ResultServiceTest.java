package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResultServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long PAYMENT_PK_ID = 100L;

	@InjectMocks
	private ResultService resultService;

	@Mock
	private ResultRepository resultRepository;
	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;

	private Result result(ResultStatus status) {
		Result result = Result.createInitial(USER_ID, PAYMENT_PK_ID, "인생 총운");
		result.setStatus(status);
		return result;
	}

	private CompatibilityResult compatibilityResult(ResultStatus status) {
		CompatibilityResult result = CompatibilityResult.createInitial(USER_ID, PAYMENT_PK_ID,
			"궁합");
		if (status == ResultStatus.COMPLETED) {
			result.completeInterpretation("해석", 80, "요약");
		} else if (status == ResultStatus.PROCESSING) {
			result.setStatus(ResultStatus.PROCESSING);
		}
		return result;
	}

	// ===== findStatusByPaymentId =====

	@Test
	@DisplayName("findStatusByPaymentId: Result 가 있으면 잠금 조회로 읽은 그 상태를 돌려주고 CompatibilityResult 는 조회하지 않는다")
	void findStatusByPaymentId_resultExists_returnsResultStatus() {
		// given
		given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
			Optional.of(result(ResultStatus.PROCESSING)));

		// when
		Optional<ResultStatus> status = resultService.findStatusByPaymentId(PAYMENT_PK_ID);

		// then
		assertThat(status).contains(ResultStatus.PROCESSING);
		verify(compatibilityResultRepository, never()).findByPaymentIdForUpdate(any());
	}

	@Test
	@DisplayName("findStatusByPaymentId: Result 가 없고 CompatibilityResult 만 있으면 궁합 쪽 상태를 돌려준다")
	void findStatusByPaymentId_onlyCompatibility_returnsCompatibilityStatus() {
		// given
		given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
			Optional.of(compatibilityResult(ResultStatus.INPUT_REQUIRED)));

		// when
		Optional<ResultStatus> status = resultService.findStatusByPaymentId(PAYMENT_PK_ID);

		// then
		assertThat(status).contains(ResultStatus.INPUT_REQUIRED);
	}

	@Test
	@DisplayName("findStatusByPaymentId: 둘 다 없으면 빈 Optional 을 돌려준다")
	void findStatusByPaymentId_neither_returnsEmpty() {
		// given
		given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
			Optional.empty());

		// when & then
		assertThat(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).isEmpty();
	}

	// ===== deleteInitialResult =====

	@Nested
	@DisplayName("deleteInitialResult: INPUT_REQUIRED 일 때만 지우는 조건부 DELETE 가")
	class DeleteInitialResult {

		@Test
		@DisplayName("Result 를 한 행 지우면 끝나고 궁합 결과는 지우지 않는다")
		void deletesInputRequiredResult() {
			// given
			given(resultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID, ResultStatus.INPUT_REQUIRED))
				.willReturn(1);

			// when
			resultService.deleteInitialResult(PAYMENT_PK_ID);

			// then
			verify(compatibilityResultRepository, never()).deleteByPaymentIdAndStatus(any(), any());
		}

		@Test
		@DisplayName("Result 는 0 행이고 궁합 결과를 한 행 지우면 예외 없이 끝난다")
		void deletesInputRequiredCompatibilityResult() {
			// given
			given(resultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID, ResultStatus.INPUT_REQUIRED))
				.willReturn(0);
			given(compatibilityResultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID,
				ResultStatus.INPUT_REQUIRED)).willReturn(1);

			// when & then
			assertThatCode(() -> resultService.deleteInitialResult(PAYMENT_PK_ID)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("둘 다 0 행이고 Result 가 남아 있으면 해석이 이미 진행됐다는 IllegalStateException 을 던진다")
		void throwsWhenResultIsNoLongerInputRequired() {
			// given: 스텁한 엔티티의 상태는 INPUT_REQUIRED 다. open-in-view 로 남은 낡은 엔티티처럼, 상태가 아니라 지운 행 수로
			//        판단하는지 본다.
			givenNothingDeleted();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
				Optional.of(result(ResultStatus.INPUT_REQUIRED)));

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 Result 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. "
					+ "paymentId(PK)=100");
		}

		@Test
		@DisplayName("둘 다 0 행이고 궁합 결과만 남아 있으면 궁합 결과의 해석이 이미 진행됐다는 IllegalStateException 을 던진다")
		void throwsWhenCompatibilityResultIsNoLongerInputRequired() {
			// given
			givenNothingDeleted();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(Optional.empty());
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
				Optional.of(compatibilityResult(ResultStatus.COMPLETED)));

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 CompatibilityResult 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. "
					+ "paymentId(PK)=100");
		}

		@Test
		@DisplayName("둘 다 0 행이고 결과가 하나도 없으면 삭제할 초기 결과가 없다는 IllegalStateException 을 던진다")
		void throwsWhenNoResult() {
			// given
			givenNothingDeleted();
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(Optional.empty());
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
				Optional.empty());

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("삭제할 초기 결과가 없습니다. paymentId(PK)=100");
		}

		private void givenNothingDeleted() {
			given(resultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID, ResultStatus.INPUT_REQUIRED))
				.willReturn(0);
			given(compatibilityResultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID,
				ResultStatus.INPUT_REQUIRED)).willReturn(0);
		}
	}
}
