package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.support.fixture.ResultFixture;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * 궁합 결과의 상태와 내용이 정해진 길로만 바뀌는지 확인한다.
 *
 * <p>정보 입력 대기 → 해석 중은 리포지토리의 조건부 UPDATE 만 맡고 엔티티에는 그 길이 없다. 엔티티에 열린 길은 해석 중 → 정보 입력
 * 대기(revertToInputRequired), 해석 중에 두 사람 정보 채우기(updatePersonsInformation), 해석 중 → 완료(completeInterpretation)
 * 셋이다. 되돌리기는 해석 중이 아니면 아무것도 바꾸지 않고 false 를 돌려주고, 정보 채우기와 완료는 해석 중이 아니면 예외를 던진다.
 * 예전에는 setStatus 가 받은 값을 버리고 늘 해석 중을 넣어, 실패한 궁합이 입력 대기로 돌아가지 못했다.
 */
class CompatibilityResultTest {

	private static final Long PAYMENT_ID = 10L;

	@Nested
	@DisplayName("revertToInputRequired 를 부르면")
	class RevertToInputRequired {

		@Test
		@DisplayName("해석 중인 결과는 정보 입력 대기로 돌아가고 true 를 돌려준다")
		void movesProcessingBackToInputRequired() {
			// given
			CompatibilityResult result = resultIn(ResultStatus.PROCESSING);

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
			CompatibilityResult result = resultIn(current);

			// when
			boolean changed = result.revertToInputRequired();

			// then
			assertThat(changed).isFalse();
			assertThat(result.getStatus()).isEqualTo(current);
		}
	}

	@Nested
	@DisplayName("updatePersonsInformation 을 부르면")
	class UpdatePersonsInformation {

		@Test
		@DisplayName("해석 중인 결과에는 두 사람의 이름과 일간을 채우고 상태는 해석 중 그대로 둔다")
		void fillsBothPersonsWhileProcessing() {
			// given
			CompatibilityResult result = resultIn(ResultStatus.PROCESSING);

			// when
			result.updatePersonsInformation("민수", "갑목", "지영", "을목");

			// then
			assertThat(result)
				.extracting(CompatibilityResult::getPerson1Name, CompatibilityResult::getPerson1Ilgan,
					CompatibilityResult::getPerson2Name, CompatibilityResult::getPerson2Ilgan,
					CompatibilityResult::getStatus)
				.containsExactly("민수", "갑목", "지영", "을목", ResultStatus.PROCESSING);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 지금 상태     | 예외 메시지
			INPUT_REQUIRED | 해석 중(PROCESSING)인 궁합 결과에만 두 사람의 정보를 채울 수 있다. paymentId=10, 지금 상태=INPUT_REQUIRED
			COMPLETED      | 해석 중(PROCESSING)인 궁합 결과에만 두 사람의 정보를 채울 수 있다. paymentId=10, 지금 상태=COMPLETED
			""")
		@DisplayName("해석 중이 아닌 결과면 IllegalStateException 을 던지고 어떤 필드도 바꾸지 않는다")
		void rejectsWhenNotProcessing(ResultStatus current, String expectedMessage) {
			// given
			CompatibilityResult result = resultIn(current);

			// when & then
			assertThatThrownBy(() -> result.updatePersonsInformation("민수", "갑목", "지영", "을목"))
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
		@DisplayName("해석 중인 결과는 본문·점수·요약을 담고 완료가 된다")
		void completesProcessingResult() {
			// given
			CompatibilityResult result = resultIn(ResultStatus.PROCESSING);

			// when
			result.completeInterpretation("새 본문", 95, "새 요약");

			// then
			assertThat(result)
				.extracting(CompatibilityResult::getInterpretation, CompatibilityResult::getCompatibilityScore,
					CompatibilityResult::getSummary, CompatibilityResult::getStatus)
				.containsExactly("새 본문", 95, "새 요약", ResultStatus.COMPLETED);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 지금 상태     | 예외 메시지
			INPUT_REQUIRED | 해석 중(PROCESSING)인 궁합 결과에만 해석 결과를 저장할 수 있다. paymentId=10, 지금 상태=INPUT_REQUIRED
			COMPLETED      | 해석 중(PROCESSING)인 궁합 결과에만 해석 결과를 저장할 수 있다. paymentId=10, 지금 상태=COMPLETED
			""")
		@DisplayName("해석 중이 아닌 결과면 IllegalStateException 을 던지고 본문·점수·요약·상태를 바꾸지 않는다")
		void rejectsWhenNotProcessing(ResultStatus current, String expectedMessage) {
			// given: 오래 멈춰 정보 입력 대기로 되돌려진 결과, 또는 이미 완료된 결과
			CompatibilityResult result = resultIn(current);

			// when & then
			assertThatThrownBy(() -> result.completeInterpretation("새 본문", 95, "새 요약"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage(expectedMessage);
			assertThat(result).as("같은 준비로 만든 결과와 모든 필드가 같다").usingRecursiveComparison()
				.isEqualTo(resultIn(current));
		}
	}

	/**
	 * JPA 가 저장 직전에 부르는 콜백 메서드(onCreate, onUpdate)를 같은 패키지에서 직접 불러 확인하고, 두 메서드에 콜백 애너테이션이
	 * 붙어 있는지 본다. 실제 저장에서 updated_at 이 채워지고 바뀌는지는 ResultTableConstraintMySqlTest 가 본다.
	 */
	@Nested
	@DisplayName("저장 콜백은")
	class Timestamps {

		@Test
		@DisplayName("onCreate 는 만든 시각과 고친 시각을 같은 값으로 채운다")
		void fillsCreatedAtAndUpdatedAtOnCreate() {
			// given
			CompatibilityResult result = inputRequired();

			// when
			result.onCreate();

			// then
			assertThat(result.getUpdatedAt()).isNotNull().isEqualTo(result.getCreatedAt());
		}

		@Test
		@DisplayName("onUpdate 는 고친 시각만 채우고 만든 시각은 건드리지 않는다")
		void fillsOnlyUpdatedAtOnUpdate() {
			// given: 고친 시각이 비어 있는 결과(updated_at 컬럼이 생기기 전에 저장된 행과 같다)
			CompatibilityResult result = inputRequired();

			// when
			result.onUpdate();

			// then
			assertThat(result.getUpdatedAt()).isNotNull();
			assertThat(result.getCreatedAt()).isNull();
		}

		@Test
		@DisplayName("onCreate 에는 @PrePersist, onUpdate 에는 @PreUpdate 가 붙어 있어 JPA 가 저장 직전에 부른다")
		void callbacksAreRegisteredWithJpa() throws NoSuchMethodException {
			assertThat(CompatibilityResult.class.getDeclaredMethod("onCreate").isAnnotationPresent(PrePersist.class))
				.as("onCreate 의 @PrePersist").isTrue();
			assertThat(CompatibilityResult.class.getDeclaredMethod("onUpdate").isAnnotationPresent(PreUpdate.class))
				.as("onUpdate 의 @PreUpdate").isTrue();
		}
	}

	@Test
	@DisplayName("상태를 비롯한 어떤 필드도 아무 값으로나 바꾸는 public 세터가 없다")
	void hasNoPublicSetter() {
		assertThat(CompatibilityResult.class.getMethods())
			.extracting(Method::getName)
			.filteredOn(name -> name.startsWith("set"))
			.isEmpty();
	}

	/**
	 * 원하는 상태의 궁합 결과를 만든다. 해석 중과 완료는 두 사람 정보(철수·영희)를 먼저 채워 두어, 막혀야 할 호출이 그 값을
	 * 덮어쓰는지 보이게 한다. 상태가 새로 생기면 switch 가 컴파일되지 않아 이 준비부터 고치게 된다.
	 */
	private static CompatibilityResult resultIn(ResultStatus status) {
		return switch (status) {
			case INPUT_REQUIRED -> inputRequired();
			case PROCESSING -> processingWithPersons();
			case COMPLETED -> {
				CompatibilityResult result = processingWithPersons();
				result.completeInterpretation("궁합 본문", 80, "궁합 요약");
				yield result;
			}
		};
	}

	private static CompatibilityResult inputRequired() {
		return ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.INPUT_REQUIRED);
	}

	private static CompatibilityResult processingWithPersons() {
		CompatibilityResult result = ResultFixture.compatibility(1L, PAYMENT_ID, ResultStatus.PROCESSING);
		result.updatePersonsInformation("철수", "병화", "영희", "정화");
		return result;
	}
}
