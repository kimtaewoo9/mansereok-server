package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 해석 시작(startProcessing, startCompatibilityProcessing)이 조건부 UPDATE 의 결과에 따라 통과, 409 용 예외, 404 용 예외로 갈리는지
 * 확인한다.
 *
 * <p>조건부 UPDATE 가 동시에 온 요청 중 하나에만 1 을 돌려주는 일은 DB 가 지키므로 ResultStartOnceMySqlTest 가 실제 MySQL 로 본다.
 * 여기서는 리포지토리가 돌려줄 값만 정한다. UPDATE 에 넘기는 시각은 주입한 시계의 "지금" 을 초 단위로 자른 값이어야 하는데,
 * 정확한 시각으로 스텁해 두면 다른 시각으로 부를 때 MockitoExtension 의 strict stubs 가 PotentialStubbingProblem 으로 테스트를
 * 실패시킨다. 통과하면 같은 시각을 돌려주고, 해석은 그 시각으로 자기가 시작한 해석인지 가린다.
 */
@ExtendWith(MockitoExtension.class)
class ResultStartProcessingTest {

	// 2026-09-26 09:00 (서울). 시간대를 명시해 개발자 PC 의 시간대에 결과가 흔들리지 않게 한다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 9, 0);
	// 소수 초가 붙은 지금. 운영 updated_at 칸의 자릿수에 따라 반올림되지 않도록 해석 시작 시각은 초 단위로 잘린다.
	private static final Clock CLOCK_WITH_FRACTION = Clock.fixed(Instant.parse("2026-09-26T00:00:00.123456789Z"),
		ZoneId.of("Asia/Seoul"));
	private static final Long PAYMENT_ID = 10L;

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Mock
	private SubCategoryRepository subCategoryRepository;

	private ResultService resultService;

	@BeforeEach
	void setUp() {
		resultService = new ResultService(resultRepository, compatibilityResultRepository, subCategoryRepository,
			FIXED_CLOCK);
	}

	@Nested
	@DisplayName("사주 해석을 시작할 때")
	class Saju {

		@Test
		@DisplayName("정보 입력 대기라 조건부 UPDATE 가 한 행을 바꾸면 UPDATE 에 넣은 해석 시작 시각을 돌려준다")
		void returnsStartTimeWhenOneRowChanged() {
			// given
			given(resultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(1);

			// when
			LocalDateTime startedAt = resultService.startProcessing(PAYMENT_ID);

			// then
			assertThat(startedAt).isEqualTo(NOW);
		}

		@Test
		@DisplayName("시계에 소수 초가 있어도 해석 시작 시각은 초 단위로 잘라 UPDATE 에 넣고 돌려준다")
		void truncatesStartTimeToSeconds() {
			// given
			ResultService service = new ResultService(resultRepository, compatibilityResultRepository,
				subCategoryRepository, CLOCK_WITH_FRACTION);
			given(resultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(1);

			// when
			LocalDateTime startedAt = service.startProcessing(PAYMENT_ID);

			// then
			assertThat(startedAt).isEqualTo(NOW);
		}

		@Test
		@DisplayName("바꾼 행이 없는데 결과 행은 있으면(이미 해석 중이거나 완료) InterpretationAlreadyStartedException 을 던진다")
		void rejectsWhenAlreadyStarted() {
			// given
			given(resultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(0);
			given(resultRepository.existsByPaymentId(PAYMENT_ID)).willReturn(true);

			// when & then
			assertThatThrownBy(() -> resultService.startProcessing(PAYMENT_ID))
				.isInstanceOf(InterpretationAlreadyStartedException.class)
				.hasMessage("이미 해석 중이거나 완료된 결과라 해석을 다시 시작하지 않는다. paymentId=10")
				.satisfies(e -> assertThat(((InterpretationAlreadyStartedException) e).getPaymentId())
					.isEqualTo(PAYMENT_ID));
		}

		@Test
		@DisplayName("바꾼 행이 없고 결과 행도 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(resultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(0);
			given(resultRepository.existsByPaymentId(PAYMENT_ID)).willReturn(false);

			// when & then
			assertThatThrownBy(() -> resultService.startProcessing(PAYMENT_ID))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("Result not found");
		}
	}

	@Nested
	@DisplayName("궁합 해석을 시작할 때")
	class Compatibility {

		@Test
		@DisplayName("정보 입력 대기라 조건부 UPDATE 가 한 행을 바꾸면 UPDATE 에 넣은 해석 시작 시각을 돌려준다")
		void returnsStartTimeWhenOneRowChanged() {
			// given
			given(compatibilityResultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(1);

			// when
			LocalDateTime startedAt = resultService.startCompatibilityProcessing(PAYMENT_ID);

			// then
			assertThat(startedAt).isEqualTo(NOW);
		}

		@Test
		@DisplayName("시계에 소수 초가 있어도 해석 시작 시각은 초 단위로 잘라 UPDATE 에 넣고 돌려준다")
		void truncatesStartTimeToSeconds() {
			// given
			ResultService service = new ResultService(resultRepository, compatibilityResultRepository,
				subCategoryRepository, CLOCK_WITH_FRACTION);
			given(compatibilityResultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(1);

			// when
			LocalDateTime startedAt = service.startCompatibilityProcessing(PAYMENT_ID);

			// then
			assertThat(startedAt).isEqualTo(NOW);
		}

		@Test
		@DisplayName("바꾼 행이 없는데 결과 행은 있으면(이미 해석 중이거나 완료) InterpretationAlreadyStartedException 을 던진다")
		void rejectsWhenAlreadyStarted() {
			// given
			given(compatibilityResultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(0);
			given(compatibilityResultRepository.existsByPaymentId(PAYMENT_ID)).willReturn(true);

			// when & then
			assertThatThrownBy(() -> resultService.startCompatibilityProcessing(PAYMENT_ID))
				.isInstanceOf(InterpretationAlreadyStartedException.class)
				.hasMessage("이미 해석 중이거나 완료된 결과라 해석을 다시 시작하지 않는다. paymentId=10")
				.satisfies(e -> assertThat(((InterpretationAlreadyStartedException) e).getPaymentId())
					.isEqualTo(PAYMENT_ID));
		}

		@Test
		@DisplayName("바꾼 행이 없고 결과 행도 없으면 EntityNotFoundException 을 던진다")
		void failsWhenResultMissing() {
			// given
			given(compatibilityResultRepository.markProcessingIfInputRequired(PAYMENT_ID, NOW)).willReturn(0);
			given(compatibilityResultRepository.existsByPaymentId(PAYMENT_ID)).willReturn(false);

			// when & then
			assertThatThrownBy(() -> resultService.startCompatibilityProcessing(PAYMENT_ID))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("CompatibilityResult not found");
		}
	}
}
