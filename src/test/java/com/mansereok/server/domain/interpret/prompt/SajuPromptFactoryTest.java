package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
	@DisplayName("유료 경로는 상품 종류와 상관없이 작품명이 비어 있지 않을 때만 선언하고, 무료 경로는 아예 선언하지 않는다")
	void declaresSourceTitleOnlyOnPaidPathWithNonBlankTitle() {
		// 작품명을 실제로 쓰는 캐릭터 상품
		String character = factory.create(9L, PromptContext.of("김태우", response, "원피스"));
		// 작품명을 쓰지 않는 유료 상품도 같은 유료 경로를 타므로 똑같이 선언한다
		String nonCharacter = factory.create(1L, PromptContext.of("김태우", response, "원피스"));
		// 갈림은 상품 종류가 아니라 작품명이 비었는지다
		String characterWithoutTitle = factory.create(9L, PromptContext.of("김태우", response, ""));
		String free = factory.createFree(101L, PromptContext.of("김태우", response));

		assertThat(character).contains("\n작품명: 원피스\n");
		assertThat(nonCharacter).contains("\n작품명: 원피스\n");
		assertThat(characterWithoutTitle).doesNotContain("\n작품명:");
		assertThat(free).doesNotContain("\n작품명:");
	}

	/**
	 * 라우팅이 뒤섞이는 변이를 기대 결과 파일 없이도 잡는 장치. isNotBlank 와 머리말 포함만 보면
	 * case 17 과 case 20 을 맞바꿔도 전부 통과하기 때문에, 상품마다 결과가 실제로 다른지 본다.
	 * 기대 결과 파일이 아직 없는 새 상품이 추가돼도 이 테스트는 그대로 동작한다.
	 */
	@Test
	@DisplayName("유료 상품 12개는 서로 다른 프롬프트를 만든다")
	void everyPaidSubcategoryProducesADistinctPrompt() {
		List<Long> ids = List.of(1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L, 22L, 23L);

		Map<String, Long> promptToId = new LinkedHashMap<>();
		for (Long id : ids) {
			String prompt = factory.create(id, PromptContext.of("김태우", response, "원피스"));
			Long collided = promptToId.putIfAbsent(prompt, id);
			assertThat(collided)
				.as("유료 상품 %d 와 %d 의 프롬프트가 같다", collided, id)
				.isNull();
		}
		assertThat(promptToId).hasSameSizeAs(ids);
	}

	@Test
	@DisplayName("무료 상품 6개는 서로 다른 프롬프트를 만든다")
	void everyFreeSubcategoryProducesADistinctPrompt() {
		List<Long> ids = List.of(101L, 102L, 103L, 104L, 105L, 106L);

		Map<String, Long> promptToId = new LinkedHashMap<>();
		for (Long id : ids) {
			String prompt = factory.createFree(id, PromptContext.of("김태우", response));
			Long collided = promptToId.putIfAbsent(prompt, id);
			assertThat(collided)
				.as("무료 상품 %d 와 %d 의 프롬프트가 같다", collided, id)
				.isNull();
		}
		assertThat(promptToId).hasSameSizeAs(ids);
	}
}
