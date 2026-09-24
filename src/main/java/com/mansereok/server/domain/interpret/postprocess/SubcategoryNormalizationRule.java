package com.mansereok.server.domain.interpret.postprocess;

/**
 * 상품(subcategory) 하나의 GPT 응답 후처리 규칙.
 *
 * <p>구현하지 않은 쪽은 원문을 그대로 돌려준다. 후처리가 필요 없는 상품은 기본 구현만으로 충분하다.
 * 입력은 언제나 null 이 아니다. null 걸러내기는 {@link AnalysisNormalizer} 가 맡는다.
 */
interface SubcategoryNormalizationRule {

	/** 상세 분석 본문 후처리. */
	default String normalizeAnalysis(String text) {
		return text;
	}

	/** 요약 후처리. */
	default String normalizeSummary(String text) {
		return text;
	}
}
