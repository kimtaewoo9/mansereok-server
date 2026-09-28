package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.product.InterpretationProduct.Kind;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 상품 목록의 모든 상품이 자기 종류의 팩터리 메서드에서만 프롬프트가 되고, 다른 두 곳에서는 예외가 되는지 교차로 확인한다.
 *
 * <p>상품 목록({@link InterpretationProduct})을 그대로 돌리므로 상품을 더하면 이 테스트도 따라온다. 팩터리의 switch 는 default 가
 * 없어 새 상품을 어떻게 다룰지 적어야 컴파일되는데, 그때 다른 종류의 프롬프트로 잘못 보내면 여기서 실패한다.
 */
@DisplayName("상품 종류와 프롬프트 팩터리")
class PromptFactoryProductKindTest {

	private final SajuPromptFactory sajuFactory = new SajuPromptFactory(PromptFixtures.FIXED_CLOCK);
	private final CompatibilityPromptFactory compatibilityFactory =
		new CompatibilityPromptFactory(PromptFixtures.FIXED_CLOCK);

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("sajuProducts")
	@DisplayName("한 사람의 사주 상품은 유료 사주 프롬프트가 되고, 무료 운세와 궁합 팩터리에서는 예외가 된다")
	void sajuProductIsAcceptedOnlyByPaidSajuFactory(InterpretationProduct product) {
		// when
		String prompt = sajuFactory.create(product.id(), person());

		// then
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
		assertThatThrownBy(() -> sajuFactory.createFree(product.id(), person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
		assertThatThrownBy(() -> compatibilityFactory.create(product.id(), persons()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("freeFortuneProducts")
	@DisplayName("무료 운세 상품은 무료 운세 프롬프트가 되고, 유료 사주와 궁합 팩터리에서는 예외가 된다")
	void freeFortuneProductIsAcceptedOnlyByFreeFactory(InterpretationProduct product) {
		// when
		String prompt = sajuFactory.createFree(product.id(), person());

		// then
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
		assertThatThrownBy(() -> sajuFactory.create(product.id(), person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
		assertThatThrownBy(() -> compatibilityFactory.create(product.id(), persons()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("compatibilityProducts")
	@DisplayName("궁합 상품은 궁합 프롬프트가 되고, 유료 사주와 무료 운세 팩터리에서는 예외가 된다")
	void compatibilityProductIsAcceptedOnlyByCompatibilityFactory(InterpretationProduct product) {
		// when
		String prompt = compatibilityFactory.create(product.id(), persons());

		// then
		assertThat(prompt).contains(UserInputSanitizer.ANALYSIS_SECTION_HEADER);
		assertThatThrownBy(() -> sajuFactory.create(product.id(), person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
		assertThatThrownBy(() -> sajuFactory.createFree(product.id(), person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", product.id());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("idsOutsideProductList")
	@DisplayName("1~120 중 상품 목록에 없는 번호는 세 팩터리 메서드 모두 예외가 된다")
	void idOutsideProductListIsRejectedEverywhere(long id) {
		assertThatThrownBy(() -> sajuFactory.create(id, person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", id);
		assertThatThrownBy(() -> sajuFactory.createFree(id, person()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", id);
		assertThatThrownBy(() -> compatibilityFactory.create(id, persons()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("지원하지 않는 카테고리입니다: %d", id);
	}

	static Stream<InterpretationProduct> sajuProducts() {
		return productsOf(Kind.SAJU);
	}

	static Stream<InterpretationProduct> freeFortuneProducts() {
		return productsOf(Kind.FREE_FORTUNE);
	}

	static Stream<InterpretationProduct> compatibilityProducts() {
		return productsOf(Kind.COMPATIBILITY);
	}

	static LongStream idsOutsideProductList() {
		return LongStream.rangeClosed(1, 120).filter(id -> InterpretationProduct.find(id).isEmpty());
	}

	private static Stream<InterpretationProduct> productsOf(Kind kind) {
		return Arrays.stream(InterpretationProduct.values()).filter(product -> product.kind() == kind);
	}

	private static PromptContext person() {
		return PromptContext.of("김태우", PromptFixtures.person1(), "원피스");
	}

	private static CompatibilityPromptContext persons() {
		return CompatibilityPromptContext.of(
			"김태우", PromptFixtures.person1(), "원피스",
			"이은정", PromptFixtures.person2(), "귀멸의 칼날");
	}
}
