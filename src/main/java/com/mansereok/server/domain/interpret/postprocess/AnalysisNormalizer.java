package com.mansereok.server.domain.interpret.postprocess;

import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * GPT 가 돌려준 해석 본문과 요약을 화면에 그대로 쓸 수 있는 모양으로 다듬는다.
 *
 * <p>상품마다 손질이 다르므로 분기 사슬 대신 {@code 상품 -> 규칙} 표를 둔다. 손질이 필요 없는 상품은 원문 유지 목록에
 * 따로 적는다. 모든 상품은 규칙 표와 원문 유지 목록 중 정확히 한쪽에 있어야 하고, 클래스가 올라올 때 이를 확인한다.
 * 그래서 {@link InterpretationProduct} 에 상품을 더하고 여기서 정하지 않으면 애플리케이션이 뜨지 않는다.
 * 손질이 필요해지면 {@link SubcategoryNormalizationRule} 하나를 더해 표에 등록하고 원문 유지 목록에서 뺀다.
 *
 * <p>상품 목록에 없는 번호나 null 이 들어오면 손대지 않는다.
 *
 * <p>여기서 바꾸는 것은 표기와 구조(라벨, 개행, 기간 표기, 문단 나누기)뿐이다.
 * 해석 내용 자체는 건드리지 않는다.
 */
@Component
public class AnalysisNormalizer {

	/** 원문 유지 상품과 상품 목록에 없는 번호가 쓰는 규칙. 원문을 그대로 돌려준다. */
	private static final SubcategoryNormalizationRule PASS_THROUGH =
		new SubcategoryNormalizationRule() {
		};

	/**
	 * 일부러 손대지 않는 상품. 궁합 상품은 본문을 정규화하지 않아 이 클래스를 거치지 않지만, 모든 상품을 한쪽에 두는지
	 * 확인하려고 함께 적는다.
	 */
	private static final Set<InterpretationProduct> KEEP_ORIGINAL = Collections.unmodifiableSet(EnumSet.of(
		InterpretationProduct.LIFE_OVERALL,
		InterpretationProduct.PERSONALITY_ANALYSIS,
		InterpretationProduct.CAREER_APTITUDE,
		InterpretationProduct.IDOL_ANALYSIS,
		InterpretationProduct.CHARACTER_SAJU,
		InterpretationProduct.ACTOR_ANALYSIS,
		InterpretationProduct.LOVE_LUCK,
		InterpretationProduct.NEW_YEAR_2026,
		InterpretationProduct.LOVE_STORY_4,
		InterpretationProduct.LOVE_STORY_6,
		InterpretationProduct.IDOL_COMPATIBILITY,
		InterpretationProduct.TRIANGLE_RELATIONSHIP,
		InterpretationProduct.CHARACTER_COMPATIBILITY,
		InterpretationProduct.CHARACTER_TO_CHARACTER_COMPATIBILITY,
		InterpretationProduct.LOVE_STORY_14,
		InterpretationProduct.ACTOR_COMPATIBILITY,
		InterpretationProduct.REUNION));

	private static final Map<InterpretationProduct, SubcategoryNormalizationRule> RULES = buildRules();

	static {
		requireEveryProductInExactlyOnePlace(RULES.keySet(), KEEP_ORIGINAL);
	}

	private static Map<InterpretationProduct, SubcategoryNormalizationRule> buildRules() {
		Map<InterpretationProduct, SubcategoryNormalizationRule> rules = new EnumMap<>(InterpretationProduct.class);

		rules.put(InterpretationProduct.MONEY_LUCK, new MoneyLuckNormalizationRule());

		// 세 상품은 같은 규칙을 쓴다. 지운 양을 로그에 남길 때 상품을 밝히려고 상품마다 따로 만든다.
		for (InterpretationProduct product : List.of(InterpretationProduct.BUSINESS_LUCK,
			InterpretationProduct.ACADEMIC_LUCK, InterpretationProduct.LIFE_ADVICE)) {
			rules.put(product, new BusinessNormalizationRule(product.id()));
		}

		for (FreeFortuneStyle style : FreeFortuneStyle.values()) {
			rules.put(style.product(), new FreeFortuneNormalizationRule(style));
		}

		return Collections.unmodifiableMap(rules);
	}

	/**
	 * 모든 상품이 규칙 표와 원문 유지 목록 중 정확히 한쪽에만 있는지 확인한다.
	 *
	 * @throws IllegalStateException 어느 쪽에도 없는 상품이나 양쪽에 모두 있는 상품이 있을 때. 메시지에 그 상품을 담는다
	 */
	static void requireEveryProductInExactlyOnePlace(Set<InterpretationProduct> withRule,
		Set<InterpretationProduct> keptOriginal) {
		Set<InterpretationProduct> missing = EnumSet.allOf(InterpretationProduct.class);
		missing.removeAll(withRule);
		missing.removeAll(keptOriginal);
		if (!missing.isEmpty()) {
			throw new IllegalStateException("후처리 규칙도 원문 유지도 정하지 않은 상품이 있습니다: " + missing);
		}

		Set<InterpretationProduct> both = EnumSet.noneOf(InterpretationProduct.class);
		both.addAll(withRule);
		both.retainAll(keptOriginal);
		if (!both.isEmpty()) {
			throw new IllegalStateException("후처리 규칙과 원문 유지에 함께 들어 있는 상품이 있습니다: " + both);
		}
	}

	/**
	 * 상세 분석 본문을 다듬는다.
	 *
	 * @param subcategoryId 상품 번호. null 이거나 상품 목록에 없으면 아무 손질도 하지 않는다
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
	 * @param subcategoryId 상품 번호. null 이거나 상품 목록에 없으면 아무 손질도 하지 않는다
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
		return InterpretationProduct.find(subcategoryId)
			.map(product -> RULES.getOrDefault(product, PASS_THROUGH))
			.orElse(PASS_THROUGH);
	}
}
