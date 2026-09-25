package com.mansereok.server.domain.interpret.postprocess;

/**
 * 무료 운세 상품(101~106) 후처리. 공통 앞단은 여기서 하고, 상품마다 다른 마지막 손질은
 * {@link FreeFortuneStyle} 에 맡긴다.
 */
final class FreeFortuneNormalizationRule implements SubcategoryNormalizationRule {

	private final FreeFortuneStyle style;

	FreeFortuneNormalizationRule(FreeFortuneStyle style) {
		this.style = style;
	}

	@Override
	public String normalizeAnalysis(String text) {
		String normalized = NormalizationSteps.normalizeLineEndings(text);
		normalized = NormalizationPatterns.PAGE_BREAK.matcher(normalized).replaceAll("\n\n");
		normalized = NormalizationSteps.stripStructuralLabels(normalized);
		normalized = NormalizationSteps.unifyPeriodNotation(normalized);
		normalized = NormalizationSteps.mergeSingleLineBreaksWithinParagraph(normalized);

		normalized = style.applyToAnalysis(normalized);

		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	@Override
	public String normalizeSummary(String text) {
		String normalized = style.applyToSummary(NormalizationSteps.normalizeLineEndings(text));
		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}
}
