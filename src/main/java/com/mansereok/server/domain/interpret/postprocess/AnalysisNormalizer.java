package com.mansereok.server.domain.interpret.postprocess;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * GPT 가 돌려준 해석 본문과 요약을 화면에 그대로 쓸 수 있는 모양으로 다듬는다.
 *
 * <p>상품(subcategory)마다 손질이 다르므로 분기 사슬 대신 {@code subcategoryId -> 규칙} 표를 둔다.
 * 표에 없는 상품은 손대지 않는다. 즉 새 상품이 들어와도 아무 일도 일어나지 않고, 손질이 필요해지면
 * {@link SubcategoryNormalizationRule} 하나를 더해 표에 등록하면 된다.
 *
 * <p>여기서 바꾸는 것은 표기와 구조(라벨, 개행, 기간 표기, 문단 나누기)뿐이다.
 * 해석 내용 자체는 건드리지 않는다.
 */
@Component
public class AnalysisNormalizer {

	/** 표에 없는 상품이 쓰는 규칙. 원문을 그대로 돌려준다. */
	private static final SubcategoryNormalizationRule PASS_THROUGH =
		new SubcategoryNormalizationRule() {
		};

	private static final Map<Long, SubcategoryNormalizationRule> RULES = buildRules();

	private static Map<Long, SubcategoryNormalizationRule> buildRules() {
		Map<Long, SubcategoryNormalizationRule> rules = new HashMap<>();

		rules.put(20L, new MoneyLuckNormalizationRule());

		SubcategoryNormalizationRule business = new BusinessNormalizationRule();
		for (Long subcategoryId : List.of(21L, 22L, 23L)) {
			rules.put(subcategoryId, business);
		}

		for (FreeFortuneStyle style : FreeFortuneStyle.values()) {
			rules.put(style.subcategoryId(), new FreeFortuneNormalizationRule(style));
		}

		return Map.copyOf(rules);
	}

	/**
	 * 상세 분석 본문을 다듬는다.
	 *
	 * @param subcategoryId 상품 식별자. null 이면 아무 손질도 하지 않는다
	 * @param raw           GPT 원문. null 이면 null 을 그대로 돌려준다
	 */
	public String normalizeAnalysis(Long subcategoryId, String raw) {
		if (raw == null) {
			return null;
		}
		return ruleFor(subcategoryId).normalizeAnalysis(raw);
	}

	/**
	 * 요약을 다듬는다.
	 *
	 * @param subcategoryId 상품 식별자. null 이면 아무 손질도 하지 않는다
	 * @param raw           GPT 원문. null 이면 null 을 그대로 돌려준다
	 */
	public String normalizeSummary(Long subcategoryId, String raw) {
		if (raw == null) {
			return null;
		}
		return ruleFor(subcategoryId).normalizeSummary(raw);
	}

	private SubcategoryNormalizationRule ruleFor(Long subcategoryId) {
		if (subcategoryId == null) {
			return PASS_THROUGH;
		}
		return RULES.getOrDefault(subcategoryId, PASS_THROUGH);
	}
}
