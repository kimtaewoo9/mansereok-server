package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.support.fixture.ResultFixture;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * 사주 결과의 상태와 내용이 정해진 길로만 바뀌는지 확인한다.
 *
 * <p>정보 입력 대기 → 해석 중은 리포지토리의 조건부 UPDATE 만 맡고 엔티티에는 그 길이 없다. 엔티티에 열린 길은 해석 중 → 정보 입력
 * 대기(revertToInputRequired), 해석 중에 입력 정보 채우기(updateInformation), 해석 중 → 완료(completeInterpretation) 셋이다.
 * 되돌리기는 해석 중이 아니면 아무것도 바꾸지 않고 false 를 돌려주고, 입력 정보 채우기와 완료는 해석 중이 아니면 예외를 던진다.
 * 완료된 결제로 다른 사람의 정보를 실어 다시 해석하는 일을 엔티티에서 한 번 더 막는다.
 *
 * <p>해석 중인 결과의 updated_at 은 해석을 시작한 시각이다. 해석 실행은 isProcessingStartedAt 으로 자기가 시작한 해석인지 가리고,
 * 저장 콜백 onUpdate 는 해석 중으로 저장할 때 이 값을 바꾸지 않는다.
 */
class ResultTest {

	private static final Long PAYMENT_ID = 10L;
	// 저장 콜백은 시스템 시계의 지금을 쓰므로, 지금보다 확실히 이른 시각을 해석 시작 시각으로 둔다.
	private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 0, 0);

	@Nested
	@DisplayName("revertToInputRequired 를 부르면")
	class RevertToInputRequired {

		@Test
		@DisplayName("해석 중인 결과는 정보 입력 대기로 돌아가고 true 를 돌려준다")
		void movesProcessingBackToInputRequired() {
			// given
			Result result = resultIn(ResultStatus.PROCESSING);

			// when
			boolean changed = result.revertToInputRequired();

			// then
			assertThat(changed).isTrue();
			assertThat(result.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@ParameterizedTest(name = "[{index}] {0} 에서 부르면 {0} 그대로")
		@EnumSource(value = ResultStatus.class, names = "PROCESSING", mode = Mode.EXCLUDE)
		@DisplayName("해석 중이 아닌 결과는 그대로 두고 false 를 돌려준다")
		void keepsOtherStatus(ResultStatus current) {
			// given
			Result result = resultIn(current);

			// when
			boolean changed = result.revertToInputRequired();

			// then
			assertThat(changed).isFalse();
			assertThat(result.getStatus()).isEqualTo(current);
		}
	}

	@Nested
	@DisplayName("updateInformation 을 부르면")
	class UpdateInformation {

		@Test
		@DisplayName("해석 중인 결과에는 입력 정보를 채우고 상태는 해석 중 그대로 둔다")
		void fillsInformationWhileProcessing() {
			// given
			Result result = resultIn(ResultStatus.PROCESSING);

			// when
			result.updateInformation("민수", LocalDate.of(1995, 5, 5), LocalTime.of(7, 30), "FEMALE", true, "을목");

			// then
			assertThat(result)
				.extracting(Result::getName, Result::getSolarDate, Result::getSolarTime, Result::getGender,
					Result::getIsLunar, Result::getIlgan, Result::getStatus)
				.containsExactly("민수", LocalDate.of(1995, 5, 5), LocalTime.of(7, 30), "FEMALE", true, "을목",
					ResultStatus.PROCESSING);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 지금 상태     | 예외 메시지
			INPUT_REQUIRED | 해석 중(PROCESSING)인 결과에만 입력 정보를 채울 수 있다. paymentId=10, 지금 상태=INPUT_REQUIRED
			COMPLETED      | 해석 중(PROCESSING)인 결과에만 입력 정보를 채울 수 있다. paymentId=10, 지금 상태=COMPLETED
			""")
		@DisplayName("해석 중이 아닌 결과면 IllegalStateException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsWhenNotProcessing(ResultStatus current, String expectedMessage) {
			// given
			Result result = resultIn(current);

			// when & then
			assertThatThrownBy(() -> result.updateInformation("민수", LocalDate.of(1995, 5, 5), LocalTime.of(7, 30),
				"FEMALE", true, "을목"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage(expectedMessage);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(resultIn(current));
		}
	}

	@Nested
	@DisplayName("completeInterpretation 을 부르면")
	class CompleteInterpretation {

		@Test
		@DisplayName("해석 중인 결과는 본문과 요약을 담고 완료가 된다")
		void completesProcessingResult() {
			// given
			Result result = resultIn(ResultStatus.PROCESSING);

			// when
			result.completeInterpretation("새 본문", "새 요약");

			// then
			assertThat(result)
				.extracting(Result::getInterpretation, Result::getSummary, Result::getStatus)
				.containsExactly("새 본문", "새 요약", ResultStatus.COMPLETED);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 지금 상태     | 예외 메시지
			INPUT_REQUIRED | 해석 중(PROCESSING)인 결과에만 해석 결과를 저장할 수 있다. paymentId=10, 지금 상태=INPUT_REQUIRED
			COMPLETED      | 해석 중(PROCESSING)인 결과에만 해석 결과를 저장할 수 있다. paymentId=10, 지금 상태=COMPLETED
			""")
		@DisplayName("해석 중이 아닌 결과면 IllegalStateException 을 던지고 본문·요약·상태를 바꾸지 않는다")
		void rejectsWhenNotProcessing(ResultStatus current, String expectedMessage) {
			// given: 오래 멈춰 정보 입력 대기로 되돌려진 결과, 또는 이미 완료된 결과
			Result result = resultIn(current);

			// when & then
			assertThatThrownBy(() -> result.completeInterpretation("새 본문", "새 요약"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage(expectedMessage);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(resultIn(current));
		}
	}

	@Nested
	@DisplayName("isProcessingStartedAt 을 부르면")
	class IsProcessingStartedAt {

		@ParameterizedTest(name = "[{index}] {0}, updated_at {1}, 넘긴 시각 {2} → {3}")
		@CsvSource(textBlock = """
			# 지금 상태,     결과의 updated_at,   넘긴 해석 시작 시각,  결과
			PROCESSING,     2026-09-26T09:00:00, 2026-09-26T09:00:00, true
			# 1초 이르거나 늦으면 다른 해석이다(되돌린 뒤 다시 시작한 해석)
			PROCESSING,     2026-09-26T09:00:00, 2026-09-26T08:59:59, false
			PROCESSING,     2026-09-26T09:00:00, 2026-09-26T09:00:01, false
			# updated_at 이 비어 있는 행(궁합 updated_at 칸을 채우기 전의 행)
			PROCESSING,     ,                    2026-09-26T09:00:00, false
			# 되돌려졌거나 이미 완료된 결과
			INPUT_REQUIRED, 2026-09-26T09:00:00, 2026-09-26T09:00:00, false
			COMPLETED,      2026-09-26T09:00:00, 2026-09-26T09:00:00, false
			""")
		@DisplayName("해석 중이고 updated_at 이 넘긴 해석 시작 시각과 같을 때만 true 다")
		void trueOnlyWhenProcessingStartedAtThatTime(ResultStatus status, LocalDateTime updatedAt,
			LocalDateTime startedAt, boolean expected) {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, status, updatedAt);

			// when
			boolean startedAtThatTime = result.isProcessingStartedAt(startedAt);

			// then
			assertThat(startedAtThatTime).isEqualTo(expected);
		}
	}

	/**
	 * JPA 가 저장 직전에 부르는 onUpdate 를 같은 패키지에서 직접 불러 확인한다. 실제 저장에서 해석 시작 시각이 지켜지는지는
	 * ResultStartOnceMySqlTest 의 되돌린 뒤 다시 시작하는 테스트가 본다(입력 정보를 채운 뒤에도 자기 결과를 저장한다).
	 */
	@Nested
	@DisplayName("저장 콜백 onUpdate 는")
	class OnUpdate {

		@Test
		@DisplayName("해석 중인 결과의 고친 시각(해석을 시작한 시각)을 바꾸지 않는다")
		void keepsStartTimeWhileProcessing() {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING, LONG_AGO);

			// when
			result.onUpdate();

			// then
			assertThat(result.getUpdatedAt()).isEqualTo(LONG_AGO);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(value = ResultStatus.class, names = "PROCESSING", mode = Mode.EXCLUDE)
		@DisplayName("해석 중이 아닌 결과는 고친 시각을 지금으로 바꾼다")
		void refreshesUpdatedAtOtherwise(ResultStatus status) {
			// given
			Result result = ResultFixture.saju(1L, PAYMENT_ID, status, LONG_AGO);

			// when
			result.onUpdate();

			// then
			assertThat(result.getUpdatedAt()).isAfter(LONG_AGO);
		}
	}

	@Test
	@DisplayName("상태를 비롯한 어떤 필드도 아무 값으로나 바꾸는 public 세터가 없다")
	void hasNoPublicSetter() {
		assertThat(Result.class.getMethods())
			.extracting(Method::getName)
			.filteredOn(name -> name.startsWith("set"))
			.isEmpty();
	}

	/**
	 * 원하는 상태의 사주 결과를 만든다. 해석 중과 완료는 입력 정보(철수)를 먼저 채워 두어, 막혀야 할 호출이 그 값을 덮어쓰는지 보이게
	 * 한다. 상태가 새로 생기면 switch 가 컴파일되지 않아 이 준비부터 고치게 된다.
	 */
	private static Result resultIn(ResultStatus status) {
		return switch (status) {
			case INPUT_REQUIRED -> ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.INPUT_REQUIRED);
			case PROCESSING -> processingWithInformation();
			case COMPLETED -> {
				Result result = processingWithInformation();
				result.completeInterpretation("사주 본문", "사주 요약");
				yield result;
			}
		};
	}

	private static Result processingWithInformation() {
		Result result = ResultFixture.saju(1L, PAYMENT_ID, ResultStatus.PROCESSING);
		result.updateInformation("철수", LocalDate.of(1990, 1, 1), LocalTime.of(12, 0), "MALE", false, "갑목");
		return result;
	}
}
