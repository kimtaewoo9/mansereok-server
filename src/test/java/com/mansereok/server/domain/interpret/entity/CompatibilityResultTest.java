package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * 궁합 결과의 상태가 정해진 길로만 바뀌는지 확인한다.
 *
 * <p>열려 있는 길은 정보 입력 대기 → 해석 중(markProcessing), 해석 중 → 정보 입력 대기(revertToInputRequired),
 * 해석 완료(completeInterpretation) 셋뿐이다. 그 밖의 상태에서 전이 메서드를 부르면 아무것도 바꾸지 않고 false 를 돌려준다.
 * 예전에는 setStatus 가 받은 값을 버리고 늘 해석 중을 넣어, 실패한 궁합이 입력 대기로 돌아가지 못했다.
 */
class CompatibilityResultTest {

	@Nested
	@DisplayName("markProcessing 을 부르면")
	class MarkProcessing {

		@Test
		@DisplayName("정보 입력 대기인 결과는 해석 중이 되고 true 를 돌려준다")
		void movesInputRequiredToProcessing() {
			// given
			CompatibilityResult result = resultIn(ResultStatus.INPUT_REQUIRED);

			// when
			boolean changed = result.markProcessing();

			// then
			assertThat(changed).isTrue();
			assertThat(result.getStatus()).isEqualTo(ResultStatus.PROCESSING);
		}

		@ParameterizedTest(name = "[{index}] {0} 에서 부르면 {0} 그대로")
		@EnumSource(value = ResultStatus.class, names = "INPUT_REQUIRED", mode = Mode.EXCLUDE)
		@DisplayName("정보 입력 대기가 아닌 결과는 그대로 두고 false 를 돌려준다")
		void keepsOtherStatus(ResultStatus current) {
			// given
			CompatibilityResult result = resultIn(current);

			// when
			boolean changed = result.markProcessing();

			// then
			assertThat(changed).isFalse();
			assertThat(result.getStatus()).isEqualTo(current);
		}
	}

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
		@DisplayName("두 사람의 이름과 일간을 채운다")
		void fillsBothPersons() {
			// given
			CompatibilityResult result = resultIn(ResultStatus.PROCESSING);

			// when
			result.updatePersonsInformation("민수", "갑목", "지영", "을목");

			// then
			assertThat(result)
				.extracting(CompatibilityResult::getPerson1Name, CompatibilityResult::getPerson1Ilgan,
					CompatibilityResult::getPerson2Name, CompatibilityResult::getPerson2Ilgan)
				.containsExactly("민수", "갑목", "지영", "을목");
		}

		@ParameterizedTest(name = "[{index}] {0} 은 {0} 그대로")
		@EnumSource(ResultStatus.class)
		@DisplayName("상태는 바꾸지 않는다")
		void keepsStatus(ResultStatus current) {
			// given
			CompatibilityResult result = resultIn(current);

			// when
			result.updatePersonsInformation("민수", "갑목", "지영", "을목");

			// then
			assertThat(result.getStatus()).isEqualTo(current);
		}
	}

	/**
	 * JPA 가 저장 직전에 부르는 콜백(@PrePersist, @PreUpdate)을 같은 패키지에서 직접 불러 확인한다. 실제 저장에서 updated_at 이
	 * 바뀌는지는 ResultTableConstraintMySqlTest 가 본다.
	 */
	@Nested
	@DisplayName("저장될 때")
	class Timestamps {

		@Test
		@DisplayName("처음 저장하면(@PrePersist) 만든 시각과 고친 시각을 같은 값으로 채운다")
		void fillsCreatedAtAndUpdatedAtOnCreate() {
			// given
			CompatibilityResult result = inputRequired();

			// when
			result.onCreate();

			// then
			assertThat(result.getUpdatedAt()).isNotNull().isEqualTo(result.getCreatedAt());
		}

		@Test
		@DisplayName("고쳐 저장하면(@PreUpdate) 고친 시각을 채우고 만든 시각은 건드리지 않는다")
		void fillsOnlyUpdatedAtOnUpdate() {
			// given: 고친 시각이 비어 있는 결과(updated_at 컬럼이 생기기 전에 저장된 행과 같다)
			CompatibilityResult result = inputRequired();

			// when
			result.onUpdate();

			// then
			assertThat(result.getUpdatedAt()).isNotNull();
			assertThat(result.getCreatedAt()).isNull();
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
	 * 공개된 메서드만 거쳐 원하는 상태의 궁합 결과를 만든다. 상태가 새로 생기면 switch 가 컴파일되지 않아 이 준비부터 고치게 된다.
	 */
	private static CompatibilityResult resultIn(ResultStatus status) {
		return switch (status) {
			case INPUT_REQUIRED -> inputRequired();
			case PROCESSING -> processing();
			case COMPLETED -> completed();
		};
	}

	private static CompatibilityResult inputRequired() {
		return CompatibilityResult.createInitial(1L, 10L, "연인 궁합");
	}

	private static CompatibilityResult processing() {
		CompatibilityResult result = inputRequired();
		result.markProcessing();
		return result;
	}

	private static CompatibilityResult completed() {
		CompatibilityResult result = inputRequired();
		result.completeInterpretation("궁합 본문", 80, "궁합 요약");
		return result;
	}
}
