package com.mansereok.server.domain.interpret.postprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * 후처리 표가 상품 목록({@link InterpretationProduct})의 모든 상품을 규칙과 원문 유지 중 정확히 한쪽에 두는지 확인한다.
 *
 * <p>규칙별로 무엇을 다듬는지는 {@link AnalysisNormalizerTest} 가 본다. 여기서는 어느 상품에 규칙이 붙어 있는지만 본다.
 */
@DisplayName("후처리 표와 상품 목록")
class AnalysisNormalizerCoverageTest {

	private final AnalysisNormalizer normalizer = new AnalysisNormalizer();

	@Test
	@DisplayName("지금의 후처리 표는 모든 상품을 한쪽에만 두므로 클래스가 예외 없이 올라온다")
	void currentTableCoversEveryProductOnce() {
		assertThatCode(AnalysisNormalizer::new).doesNotThrowAnyException();
	}

	/**
	 * 모든 규칙은 쪽 나눔 표시([PAGE_BREAK])를 빈 줄로 바꾼다. 원문 유지 상품은 이 표시를 그대로 둔다.
	 */
	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(value = InterpretationProduct.class, names = {
		"MONEY_LUCK", "BUSINESS_LUCK", "ACADEMIC_LUCK", "LIFE_ADVICE",
		"CHANGES_2026", "KEYWORD_2026", "FLIRTING", "CHEMISTRY_MATCH", "TODAY_FORTUNE", "MARCH_MONTHLY_FORTUNE"})
	@DisplayName("재물운·사업운 계열·무료 운세 상품은 후처리 규칙이 있어 쪽 나눔 표시를 지운다")
	void productsWithRuleRemovePageBreak(InterpretationProduct product) {
		// when
		String normalized = normalizer.normalizeAnalysis(product.id(), "앞 단락입니다.\n[PAGE_BREAK]\n뒤 단락입니다.");

		// then
		assertThat(normalized).doesNotContain("PAGE_BREAK");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(value = InterpretationProduct.class, mode = Mode.EXCLUDE, names = {
		"MONEY_LUCK", "BUSINESS_LUCK", "ACADEMIC_LUCK", "LIFE_ADVICE",
		"CHANGES_2026", "KEYWORD_2026", "FLIRTING", "CHEMISTRY_MATCH", "TODAY_FORTUNE", "MARCH_MONTHLY_FORTUNE"})
	@DisplayName("그 밖의 상품은 원문 유지라 본문과 요약을 한 글자도 바꾸지 않는다")
	void otherProductsKeepOriginal(InterpretationProduct product) {
		// given
		String raw = "[1. 총운]\n1-1 그대로 남아야 합니다.\n[PAGE_BREAK]\n### 머리말";

		// when & then
		assertThat(normalizer.normalizeAnalysis(product.id(), raw)).as("본문").isEqualTo(raw);
		assertThat(normalizer.normalizeSummary(product.id(), raw)).as("요약").isEqualTo(raw);
	}

	@Nested
	@DisplayName("표를 확인하는 규칙은")
	class CoverageCheck {

		@Test
		@DisplayName("규칙에도 원문 유지에도 없는 상품이 있으면 그 상품을 담아 IllegalStateException 을 던진다")
		void rejectsProductInNeitherPlace() {
			// given
			Set<InterpretationProduct> withRule = EnumSet.of(InterpretationProduct.MONEY_LUCK);
			Set<InterpretationProduct> keptOriginal = EnumSet.complementOf(
				EnumSet.of(InterpretationProduct.MONEY_LUCK, InterpretationProduct.REUNION));

			// when & then
			assertThatThrownBy(() -> AnalysisNormalizer.requireEveryProductInExactlyOnePlace(withRule, keptOriginal))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("후처리 규칙도 원문 유지도 정하지 않은 상품이 있습니다: [REUNION]");
		}

		@Test
		@DisplayName("규칙과 원문 유지에 함께 있는 상품이 있으면 그 상품을 담아 IllegalStateException 을 던진다")
		void rejectsProductInBothPlaces() {
			// given
			Set<InterpretationProduct> withRule = EnumSet.of(InterpretationProduct.MONEY_LUCK);
			Set<InterpretationProduct> keptOriginal = EnumSet.allOf(InterpretationProduct.class);

			// when & then
			assertThatThrownBy(() -> AnalysisNormalizer.requireEveryProductInExactlyOnePlace(withRule, keptOriginal))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("후처리 규칙과 원문 유지에 함께 들어 있는 상품이 있습니다: [MONEY_LUCK]");
		}

		@Test
		@DisplayName("모든 상품이 정확히 한쪽에만 있으면 통과한다")
		void acceptsEveryProductInExactlyOnePlace() {
			// given
			Set<InterpretationProduct> withRule = EnumSet.of(InterpretationProduct.MONEY_LUCK);
			Set<InterpretationProduct> keptOriginal = EnumSet.complementOf(
				EnumSet.of(InterpretationProduct.MONEY_LUCK));

			// when & then
			assertThatCode(() -> AnalysisNormalizer.requireEveryProductInExactlyOnePlace(withRule, keptOriginal))
				.doesNotThrowAnyException();
		}
	}
}
