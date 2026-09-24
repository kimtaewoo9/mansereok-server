package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 프롬프트 길이 회귀 감지용. 상한은 실제 길이보다 넉넉하게(약 1.3배) 잡아 두었다.
 *
 * <p>목적은 "이 길이가 맞다" 를 고정하는 것이 아니라, 공용 조각을 잘못 끼워 같은 블록이 두 번 붙거나
 * 상품 하나가 갑자기 두 배로 불어나는 사고를 잡는 것이다. 문구를 다듬어 길이가 조금 변하는 것은
 * 이 테스트를 건드리지 않는다. 하한은 그 반대로, 블록이 통째로 빠지는 사고를 잡는다.
 */
@DisplayName("프롬프트 길이 예산")
class PromptLengthBudgetTest {

	private static final SajuPromptFactory SAJU = new SajuPromptFactory();
	private static final CompatibilityPromptFactory COMPATIBILITY =
		new CompatibilityPromptFactory();

	/** 상품별 상한. 값은 현재 길이(2026-09 기준)에 여유를 더한 것이다. */
	private static final Map<Long, Integer> SAJU_MAX = Map.ofEntries(
		Map.entry(1L, 11_200), Map.entry(2L, 11_300), Map.entry(3L, 15_900),
		Map.entry(5L, 10_900), Map.entry(9L, 10_900), Map.entry(13L, 10_900),
		Map.entry(17L, 11_900), Map.entry(18L, 15_300), Map.entry(20L, 6_100),
		Map.entry(21L, 12_000), Map.entry(22L, 12_500), Map.entry(23L, 11_800));

	private static final Map<Long, Integer> COMPATIBILITY_MAX = Map.of(
		4L, 14_000, 6L, 14_000, 7L, 15_300, 8L, 10_200, 10L, 14_300,
		11L, 14_000, 14L, 14_000, 15L, 15_300, 19L, 15_300);

	private static final Map<Long, Integer> FREE_MAX = Map.of(
		101L, 4_800, 102L, 4_700, 103L, 5_100, 104L, 5_600, 105L, 5_500, 106L, 5_100);

	/** 어떤 프롬프트도 이보다 짧으면 블록이 통째로 빠진 것이다. 가장 짧은 무료 상품이 3,500자대다. */
	private static final int MIN_LENGTH = 3_000;

	static Stream<Arguments> cases() {
		Stream.Builder<Arguments> builder = Stream.builder();
		SAJU_MAX.forEach((id, max) -> builder.add(Arguments.of("saju", id, max)));
		COMPATIBILITY_MAX.forEach((id, max) ->
			builder.add(Arguments.of("compatibility", id, max)));
		FREE_MAX.forEach((id, max) -> builder.add(Arguments.of("free", id, max)));
		return builder.build();
	}

	@ParameterizedTest(name = "{0} {1} 프롬프트 길이는 {2}자를 넘지 않는다")
	@MethodSource("cases")
	void promptStaysWithinBudget(String kind, Long subcategoryId, int maxLength) {
		String prompt = switch (kind) {
			case "saju" -> SAJU.create(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.person1(), "원피스"));
			case "compatibility" -> COMPATIBILITY.create(subcategoryId,
				CompatibilityPromptContext.of("김태우", PromptFixtures.person1(), "원피스",
					"이은정", PromptFixtures.person2(), "귀멸의 칼날"));
			case "free" -> SAJU.createFree(subcategoryId,
				PromptContext.of("김태우", PromptFixtures.person1()));
			default -> throw new IllegalArgumentException("알 수 없는 종류: " + kind);
		};

		assertThat(prompt.length())
			.as("%s %s 프롬프트 길이", kind, subcategoryId)
			.isBetween(MIN_LENGTH, maxLength);
	}
}
