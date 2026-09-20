package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
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
	@DisplayName("findStatusByPaymentId: Result 가 있으면 그 상태를 돌려주고 CompatibilityResult 는 조회하지 않는다")
	void findStatusByPaymentId_resultExists_returnsResultStatus() {
		// given
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(result(ResultStatus.PROCESSING)));

		// when
		Optional<ResultStatus> status = resultService.findStatusByPaymentId(PAYMENT_PK_ID);

		// then
		assertThat(status).contains(ResultStatus.PROCESSING);
		verify(compatibilityResultRepository, never()).findByPaymentId(any());
	}

	@Test
	@DisplayName("findStatusByPaymentId: Result 가 없고 CompatibilityResult 만 있으면 궁합 쪽 상태를 돌려준다")
	void findStatusByPaymentId_onlyCompatibility_returnsCompatibilityStatus() {
		// given
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
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
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.empty());

		// when & then
		assertThat(resultService.findStatusByPaymentId(PAYMENT_PK_ID)).isEmpty();
	}

	// ===== deleteInitialResult =====

	@Test
	@DisplayName("deleteInitialResult: INPUT_REQUIRED 인 Result 를 삭제한다")
	void deleteInitialResult_inputRequiredResult_deletes() {
		// given
		Result result = result(ResultStatus.INPUT_REQUIRED);
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.of(result));

		// when
		resultService.deleteInitialResult(PAYMENT_PK_ID);

		// then
		verify(resultRepository).delete(result);
		verify(compatibilityResultRepository, never()).delete(any());
	}

	@Test
	@DisplayName("deleteInitialResult: Result 가 없고 INPUT_REQUIRED 인 CompatibilityResult 가 있으면 궁합 결과를 삭제한다")
	void deleteInitialResult_inputRequiredCompatibility_deletes() {
		// given
		CompatibilityResult result = compatibilityResult(ResultStatus.INPUT_REQUIRED);
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(result));

		// when
		resultService.deleteInitialResult(PAYMENT_PK_ID);

		// then
		verify(compatibilityResultRepository).delete(result);
		verify(resultRepository, never()).delete(any());
	}

	@Test
	@DisplayName("deleteInitialResult: Result 가 INPUT_REQUIRED 가 아니면 IllegalStateException 을 던지고 삭제하지 않는다")
	void deleteInitialResult_resultNotInputRequired_throws() {
		// given
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(result(ResultStatus.PROCESSING)));

		// when & then
		assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PROCESSING");

		verify(resultRepository, never()).delete(any());
		verify(compatibilityResultRepository, never()).delete(any());
	}

	@Test
	@DisplayName("deleteInitialResult: CompatibilityResult 가 COMPLETED 면 IllegalStateException 을 던지고 삭제하지 않는다")
	void deleteInitialResult_compatibilityNotInputRequired_throws() {
		// given
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.of(compatibilityResult(ResultStatus.COMPLETED)));

		// when & then
		assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("COMPLETED");

		verify(compatibilityResultRepository, never()).delete(any());
	}

	@Test
	@DisplayName("deleteInitialResult: 둘 다 없으면 IllegalStateException 을 던진다")
	void deleteInitialResult_neither_throws() {
		// given
		given(resultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(Optional.empty());
		given(compatibilityResultRepository.findByPaymentId(PAYMENT_PK_ID)).willReturn(
			Optional.empty());

		// when & then
		assertThatThrownBy(() -> resultService.deleteInitialResult(PAYMENT_PK_ID))
			.isInstanceOf(IllegalStateException.class);
	}
}
