package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 아이돌 궁합(7)과 배우 궁합(15)이 두 사람을 부르는 말 말고는 같은 프롬프트를 만드는지 확인한다.
 *
 * <p>두 상품은 기대 결과 파일이 따로 있어서, 한쪽 빌더만 고쳐도 고친 쪽 기대 결과 파일만 다시 만들면 테스트가 모두 통과한다.
 * 이 테스트는 두 프롬프트를 서로 비교하므로 두 상품이 갈라지면 바로 실패한다.
 */
@DisplayName("아이돌·배우 궁합 프롬프트")
class CelebrityCompatibilityPromptsTest {

	private final CompatibilityPromptFactory factory =
		new CompatibilityPromptFactory(PromptFixtures.FIXED_CLOCK);

	static Stream<Arguments> people() {
		return Stream.of(
			Arguments.of("두 사람 모두 정보가 채워짐", CompatibilityPromptContext.of(
				"김태우", PromptFixtures.person1(), "이은정", PromptFixtures.person2())),
			Arguments.of("첫 번째 사람만 정보가 빠짐", CompatibilityPromptContext.of(
				"박하늘", PromptFixtures.personEdge(), "최서준", PromptFixtures.person2())),
			Arguments.of("두 사람 모두 정보가 빠짐", CompatibilityPromptContext.of(
				"박하늘", PromptFixtures.personEdge(), "최서준", PromptFixtures.personEdge())));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("people")
	@DisplayName("아이돌 궁합 프롬프트에서 '아이돌' 을 '배우' 로 바꾸면 배우 궁합 프롬프트와 같다")
	void idolPromptMatchesActorPromptExceptLabel(String situation,
		CompatibilityPromptContext context) {
		// when
		String idolPrompt = factory.create(7L, context);
		String actorPrompt = factory.create(15L, context);

		// then
		assertThat(idolPrompt).contains("라는 제3자(아이돌)들에 대한 것입니다.");
		assertThat(idolPrompt.replace("아이돌", "배우")).isEqualTo(actorPrompt);
	}
}
