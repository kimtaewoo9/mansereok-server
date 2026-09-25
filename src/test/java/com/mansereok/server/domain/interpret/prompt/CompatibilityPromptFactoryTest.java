package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	@ParameterizedTest(name = "int 범위를 벗어난 상품 {0} 은 다른 상품으로 잘리지 않고 예외가 된다")
	@ValueSource(longs = {4294967306L, -4294967286L})
	void rejectsSubcategoryOutOfIntRange(long subcategoryId) {
		CompatibilityPromptContext context = context();

		assertThatThrownBy(() -> factory.create(subcategoryId, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@Test
	@DisplayName("subcategoryId 가 null 이면 IllegalArgumentException 을 던진다")
	void rejectsNullSubcategory() {
		CompatibilityPromptContext context = context();

		assertThatThrownBy(() -> factory.create(null, context))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	/**
	 * 라우팅이 뒤섞이는 변이를 기대 결과 파일 없이도 잡는 장치.
	 *
	 * <p>4, 6, 14 는 {@code CompatibilityPrompts.createLoveStoryPrompt} 를 같은 인자로 부르므로
	 * 프롬프트가 바이트 단위로 같다. 그래서 "모든 상품이 다르다" 가 아니라
	 * "같은 빌더를 쓰는 묶음 안에서는 같고, 묶음끼리는 다르다" 로 검증한다.
	 * 새 상품이 기존 빌더를 재사용한다면 이 목록에 같이 넣고, 아니라면 자기 묶음을 만들면 된다.
	 */
	private static final List<List<Long>> ROUTING_GROUPS = List.of(
		List.of(4L, 6L, 14L), // 같은 빌더 · 같은 인자 → 바이트 동일
		List.of(7L),
		List.of(8L),
		List.of(10L),
		List.of(11L),
		List.of(15L),
		List.of(19L));

	@Test
	@DisplayName("같은 빌더로 가는 4, 6, 14 는 프롬프트가 바이트 단위로 같다")
	void subcategoriesSharingABuilderProduceTheSamePrompt() {
		for (List<Long> group : ROUTING_GROUPS) {
			Long first = group.getFirst();
			String expected = factory.create(first, context());
			for (Long id : group) {
				assertThat(factory.create(id, context()))
					.as("같은 묶음인 %d 와 %d 의 프롬프트", first, id)
					.isEqualTo(expected);
			}
		}
	}

	@Test
	@DisplayName("서로 다른 빌더로 가는 묶음끼리는 프롬프트가 다르다")
	void differentRoutingGroupsProduceDifferentPrompts() {
		Map<String, Long> promptToId = new LinkedHashMap<>();
		for (List<Long> group : ROUTING_GROUPS) {
			Long id = group.getFirst();
			Long collided = promptToId.putIfAbsent(factory.create(id, context()), id);
			assertThat(collided)
				.as("다른 묶음인 %d 와 %d 의 프롬프트가 같다", collided, id)
				.isNull();
		}
		assertThat(promptToId).hasSameSizeAs(ROUTING_GROUPS);
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
