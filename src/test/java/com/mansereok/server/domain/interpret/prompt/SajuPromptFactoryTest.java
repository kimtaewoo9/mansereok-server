package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("사주 프롬프트 팩토리")
class SajuPromptFactoryTest {

	private final SajuPromptFactory factory = new SajuPromptFactory();
	private final ManseryeokCalculationResponse response = PromptFixtures.person1();

	@ParameterizedTest(name = "유료 상품 {0} 은 비어 있지 않은 프롬프트를 만든다")
	@ValueSource(longs = {1, 2, 3, 5, 9, 13, 17, 18, 20, 21, 22, 23})
	void createsNonEmptyPromptForEverySupportedSubcategory(long subcategoryId) {
		String prompt = factory.create(subcategoryId, PromptContext.of("김태우", response, "원피스"));

		assertThat(prompt).isNotBlank();
		assertThat(prompt).contains(UserInputSanitizer.USER_INPUT_SECTION_HEADER);
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
		assertThat(prompt).contains("김태우");
	}

	@ParameterizedTest(name = "무료 상품 {0} 은 비어 있지 않은 프롬프트를 만든다")
	@ValueSource(longs = {101, 102, 103, 104, 105, 106})
	void createsNonEmptyFreePromptForEverySupportedSubcategory(long subcategoryId) {
		String prompt = factory.createFree(subcategoryId, PromptContext.of("김태우", response));

		assertThat(prompt).isNotBlank();
		assertThat(prompt).contains(UserInputSanitizer.USER_INPUT_SECTION_HEADER);
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
	}

	@ParameterizedTest(name = "지원하지 않는 유료 상품 {0} 은 IllegalArgumentException 을 던진다")
	@ValueSource(longs = {0, 4, 7, 12, 24, 101, 999})
	void rejectsUnsupportedSubcategory(long subcategoryId) {
		PromptContext context = PromptContext.of("김태우", response, "원피스");

		assertThatThrownBy(() -> factory.create(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@ParameterizedTest(name = "지원하지 않는 무료 상품 {0} 은 IllegalArgumentException 을 던진다")
	@ValueSource(longs = {1, 100, 107, 999})
	void rejectsUnsupportedFreeSubcategory(long subcategoryId) {
		PromptContext context = PromptContext.of("김태우", response);

		assertThatThrownBy(() -> factory.createFree(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@ParameterizedTest(name = "int 범위를 벗어난 상품 {0} 은 다른 상품으로 잘리지 않고 예외가 된다")
	@ValueSource(longs = {4294967297L, 4294967305L, -4294967295L})
	void rejectsSubcategoryOutOfIntRange(long subcategoryId) {
		PromptContext context = PromptContext.of("김태우", response, "원피스");

		assertThatThrownBy(() -> factory.create(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
		assertThatThrownBy(() -> factory.createFree(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@Test
	@DisplayName("subcategoryId 가 null 이면 IllegalArgumentException 을 던진다")
	void rejectsNullSubcategory() {
		PromptContext context = PromptContext.of("김태우", response, "원피스");

		assertThatThrownBy(() -> factory.create(null, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
		assertThatThrownBy(() -> factory.createFree(null, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@Test
	@DisplayName("작품명을 쓰는 캐릭터 상품만 사용자 입력 구획에 작품명을 선언한다")
	void declaresSourceTitleOnlyForCharacterProduct() {
		String character = factory.create(9L, PromptContext.of("김태우", response, "원피스"));
		String free = factory.createFree(101L, PromptContext.of("김태우", response));

		assertThat(character).contains("\n작품명: 원피스\n");
		assertThat(free).doesNotContain("\n작품명:");
	}
}
