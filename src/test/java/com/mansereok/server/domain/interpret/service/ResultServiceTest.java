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

	// 행이 없는 표를 잠그지 않는지(REPEATABLE READ 의 간격 잠금)는 목으로 볼 수 없어 실제 MySQL 테스트
	// (RefundAndInterpretationStartMySqlTest)가 본다. 여기서는 어느 표에 행이 있느냐에 따라 무엇을 돌려주고 던지는지만 본다.

	// ===== findStatusByPaymentId =====

	@Test
	@DisplayName("findStatusByPaymentId: Result 행이 있으면 그 행을 잠가 읽은 상태를 돌려준다")
	void findStatusByPaymentId_resultExists_returnsResultStatus() {
		// given
		given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
		given(resultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
			Optional.of(result(ResultStatus.PROCESSING)));

		// when
		Optional<ResultStatus> status = resultService.findStatusByPaymentId(PAYMENT_PK_ID);

		// then
		assertThat(status).contains(ResultStatus.PROCESSING);
	}

	@Test
	@DisplayName("findStatusByPaymentId: Result 행이 없고 CompatibilityResult 행만 있으면 궁합 쪽을 잠가 읽은 상태를 돌려준다")
	void findStatusByPaymentId_onlyCompatibility_returnsCompatibilityStatus() {
		// given
		given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(false);
		given(compatibilityResultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
		given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_PK_ID)).willReturn(
			Optional.of(CompatibilityResult.createInitial(USER_ID, PAYMENT_PK_ID, "궁합")));

		// when
		Optional<ResultStatus> status = resultService.findStatusByPaymentId(PAYMENT_PK_ID);

		// then
		assertThat(status).contains(ResultStatus.INPUT_REQUIRED);
	}

	@Test
	@DisplayName("findStatusByPaymentId: 둘 다 없으면 빈 Optional 을 돌려준다")
	void findStatusByPaymentId_neither_returnsEmpty() {
		// given
		givenNoResultRow();

		// when & then
		assertThat(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).isEmpty();
	}

	// ===== deleteInitialResult =====

	@Nested
	@DisplayName("deleteInitialResult: 결과 행이 있는 표에만 INPUT_REQUIRED 일 때 지우는 조건부 DELETE 를 보내")
	class DeleteInitialResult {

		@Test
		@DisplayName("Result 를 한 행 지우면 끝나고 궁합 결과는 지우지 않는다")
		void deletesInputRequiredResult() {
			// given
			given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
			given(resultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID, ResultStatus.INPUT_REQUIRED))
				.willReturn(1);

			// when
			resultService.deleteInitialResult(PAYMENT_PK_ID);

			// then
			verify(compatibilityResultRepository, never()).deleteByPaymentIdAndStatus(any(), any());
		}

		@Test
		@DisplayName("Result 행이 없고 궁합 결과를 한 행 지우면 예외 없이 끝난다")
		void deletesInputRequiredCompatibilityResult() {
			// given
			given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(false);
			given(compatibilityResultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
			given(compatibilityResultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID,
				ResultStatus.INPUT_REQUIRED)).willReturn(1);

			// when & then
			assertThatCode(() -> resultService.deleteInitialResult(PAYMENT_PK_ID)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("Result 행이 있는데 0 행을 지우면(INPUT_REQUIRED 가 아니면) 해석이 이미 진행됐다는 IllegalStateException 을 던진다")
		void throwsWhenResultIsNoLongerInputRequired() {
			// given
			given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
			given(resultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID, ResultStatus.INPUT_REQUIRED))
				.willReturn(0);

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 Result 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. "
					+ "paymentId(PK)=100");
		}

		@Test
		@DisplayName("궁합 결과 행만 있는데 0 행을 지우면 궁합 결과의 해석이 이미 진행됐다는 IllegalStateException 을 던진다")
		void throwsWhenCompatibilityResultIsNoLongerInputRequired() {
			// given
			given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(false);
			given(compatibilityResultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(true);
			given(compatibilityResultRepository.deleteByPaymentIdAndStatus(PAYMENT_PK_ID,
				ResultStatus.INPUT_REQUIRED)).willReturn(0);

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("정보 입력 전(INPUT_REQUIRED)의 CompatibilityResult 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. "
					+ "paymentId(PK)=100");
		}

		@Test
		@DisplayName("결과 행이 어느 표에도 없으면 삭제할 초기 결과가 없다는 IllegalStateException 을 던진다")
		void throwsWhenNoResult() {
			// given
			givenNoResultRow();

			// when & then
			assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("삭제할 초기 결과가 없습니다. paymentId(PK)=100");
		}
	}

	private void givenNoResultRow() {
		given(resultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(false);
		given(compatibilityResultRepository.existsByPaymentId(PAYMENT_PK_ID)).willReturn(false);
	}
}
