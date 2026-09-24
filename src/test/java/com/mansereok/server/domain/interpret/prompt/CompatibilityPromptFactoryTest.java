package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("궁합 프롬프트 팩토리")
class CompatibilityPromptFactoryTest {

	private final CompatibilityPromptFactory factory = new CompatibilityPromptFactory();

	private CompatibilityPromptContext context() {
		return CompatibilityPromptContext.of(
			"김태우", PromptFixtures.person1(), "원피스",
			"이은정", PromptFixtures.person2(), "귀멸의 칼날");
	}

	@ParameterizedTest(name = "궁합 상품 {0} 은 비어 있지 않은 프롬프트를 만든다")
	@ValueSource(longs = {4, 6, 7, 8, 10, 11, 14, 15, 19})
	void createsNonEmptyPromptForEverySupportedSubcategory(long subcategoryId) {
		String prompt = factory.create(subcategoryId, context());

		assertThat(prompt).isNotBlank();
		assertThat(prompt).contains(UserInputSanitizer.USER_INPUT_SECTION_HEADER);
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
		assertThat(prompt).contains("김태우");
		assertThat(prompt).contains("이은정");
	}

	@ParameterizedTest(name = "지원하지 않는 궁합 상품 {0} 은 IllegalArgumentException 을 던진다")
	@ValueSource(longs = {1, 5, 9, 12, 13, 16, 20, 999})
	void rejectsUnsupportedSubcategory(long subcategoryId) {
		CompatibilityPromptContext context = context();

		assertThatThrownBy(() -> factory.create(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@Test
	@DisplayName("두 사람의 이름과 작품명을 모두 사용자 입력 구획 하나에 담는다")
	void declaresBothPeopleInOneUserInputSection() {
		String prompt = factory.create(11L, context());

		int begin = prompt.indexOf(UserInputSanitizer.USER_INPUT_BEGIN);
		int end = prompt.indexOf(UserInputSanitizer.USER_INPUT_END);
		String section = prompt.substring(begin, end);

		assertThat(section).contains("첫 번째 사람 이름");
		assertThat(section).contains("첫 번째 사람 작품명");
		assertThat(section).contains("두 번째 사람 이름");
		assertThat(section).contains("두 번째 사람 작품명");
		assertThat(prompt.indexOf(UserInputSanitizer.USER_INPUT_BEGIN,
			begin + 1)).isEqualTo(-1);
	}
}
