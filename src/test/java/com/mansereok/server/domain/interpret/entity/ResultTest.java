package com.mansereok.server.domain.interpret.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * 사주 결과의 상태가 궁합 결과와 같은 길로만 바뀌는지 확인한다.
 *
 * <p>열려 있는 길은 정보 입력 대기 → 해석 중(markProcessing), 해석 중 → 정보 입력 대기(revertToInputRequired),
 * 해석 완료(completeInterpretation) 셋뿐이다. 그 밖의 상태에서 전이 메서드를 부르면 아무것도 바꾸지 않고 false 를 돌려준다.
 */
class ResultTest {

	@Nested
	@DisplayName("markProcessing 을 부르면")
	class MarkProcessing {

		@Test
		@DisplayName("정보 입력 대기인 결과는 해석 중이 되고 true 를 돌려준다")
		void movesInputRequiredToProcessing() {
			// given
			Result result = resultIn(ResultStatus.INPUT_REQUIRED);

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
			Result result = resultIn(current);

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

	@ParameterizedTest(name = "[{index}] {0} 은 {0} 그대로")
	@EnumSource(ResultStatus.class)
	@DisplayName("updateInformation 으로 입력 정보를 채워도 상태는 바꾸지 않는다")
	void updateInformationKeepsStatus(ResultStatus current) {
		// given
		Result result = resultIn(current);

		// when
		result.updateInformation("민수", LocalDate.of(1990, 1, 1), LocalTime.of(12, 0), "MALE", false, "갑목");

		// then
		assertThat(result.getStatus()).isEqualTo(current);
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
	 * 공개된 메서드만 거쳐 원하는 상태의 사주 결과를 만든다. 상태가 새로 생기면 switch 가 컴파일되지 않아 이 준비부터 고치게 된다.
	 */
	private static Result resultIn(ResultStatus status) {
		return switch (status) {
			case INPUT_REQUIRED -> inputRequired();
			case PROCESSING -> processing();
			case COMPLETED -> completed();
		};
	}

	private static Result inputRequired() {
		return Result.createInitial(1L, 10L, "인생 총운");
	}

	private static Result processing() {
		Result result = inputRequired();
		result.markProcessing();
		return result;
	}

	private static Result completed() {
		Result result = inputRequired();
		result.completeInterpretation("사주 본문", "사주 요약");
		return result;
	}
}
