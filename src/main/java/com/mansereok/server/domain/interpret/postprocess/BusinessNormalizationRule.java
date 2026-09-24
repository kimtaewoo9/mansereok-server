package com.mansereok.server.domain.interpret.postprocess;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사업운(21)·학업운(22)·인생조언(23) 후처리.
 *
 * <p>세 상품은 같은 문제를 공유한다. 명리 용어가 설명 없이 튀어나오고, 오행 점수 같은 내부 수치가
 * 그대로 노출되며, 요청하지 않은 개운법(행운의 색, 방위, 숫자)이 붙는다. 요약은 화면 카드에 들어가므로
 * 줄 수와 글자 수를 함께 제한한다.
 */
final class BusinessNormalizationRule implements SubcategoryNormalizationRule {

	private static final int SUMMARY_MAX_LINES = 5;
	private static final int SUMMARY_MAX_CHARS = 280;

	/** 독자가 모르는 용어를 괄호 설명으로 풀어 준다. */
	private static final Map<String, String> JARGON_EXPANSIONS = new LinkedHashMap<>();

	static {
		JARGON_EXPANSIONS.put("수국", "수기운 결속 구조");
		JARGON_EXPANSIONS.put("천간충", "천간 충돌(생각과 실행이 맞부딪히는 구조)");
		JARGON_EXPANSIONS.put("양인살", "양인살(추진력이 강하지만 과속 시 마찰이 생기기 쉬운 신살)");
		JARGON_EXPANSIONS.put("공망", "공망(기대와 현실이 어긋나기 쉬운 구간)");
		JARGON_EXPANSIONS.put("역마살", "역마살(이동과 변화가 많아지는 기운)");
	}

	/** 요청하지 않은 개운법 안내가 들어간 줄. */
	private static final String FORBIDDEN_ADVICE_LINE_REGEX =
		"(?m)^.*(행운의 색|개운색|개운법|청색|녹색|동쪽|서쪽|남쪽|북쪽|3과\\s*8|숫자\\s*3|숫자\\s*8).*$\\n?";

	/** 내부 계산값이 그대로 노출된 줄. */
	private static final String INTERNAL_SCORE_LINE_REGEX =
		"(?m)^.*(오행 점수|내 세력|남의 세력).*$\\n?";

	@Override
	public String normalizeAnalysis(String text) {
		String normalized = stripLabelsAndJargon(text);

		// 문장 단위 줄바꿈을 문단 줄글로 정리
		normalized = NormalizationSteps.mergeSingleLineBreaksWithinParagraph(normalized);

		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	@Override
	public String normalizeSummary(String text) {
		String normalized = stripLabelsAndJargon(text);
		normalized = NormalizationSteps.collapseBlankLines(normalized).trim();
		return limitSummaryLength(normalized);
	}

	/** 본문과 요약이 공유하는 앞단. 라벨 제거, 기간 통일, 용어 풀이, 금지 문구 제거까지. */
	private static String stripLabelsAndJargon(String text) {
		String normalized = NormalizationSteps.normalizeLineEndings(text);

		// UI 페이지 구분은 반드시 빈 줄 1개(\n\n)로 통일
		normalized = NormalizationPatterns.PAGE_BREAK.matcher(normalized).replaceAll("\n\n");

		// 요구하지 않은 라벨/목차 제거
		normalized = NormalizationSteps.stripStructuralLabels(normalized);

		normalized = NormalizationSteps.unifyPeriodNotation(normalized);
		normalized = expandJargonForReadability(normalized);
		normalized = normalized.replaceAll(FORBIDDEN_ADVICE_LINE_REGEX, "");
		normalized = normalized.replaceAll(INTERNAL_SCORE_LINE_REGEX, "");
		normalized = normalized.replaceAll("(?m)([목화토금수])\\s*\\d+\\.\\d+", "$1 기운");
		return normalized.replaceAll("\\(\\p{IsHan}+\\)", "");
	}

	private static String expandJargonForReadability(String text) {
		String normalized = text;
		for (Map.Entry<String, String> expansion : JARGON_EXPANSIONS.entrySet()) {
			normalized = normalized.replace(expansion.getKey(), expansion.getValue());
		}
		return normalized;
	}

	/** 요약 카드가 넘치지 않도록 줄 수와 글자 수를 함께 자른다. */
	private static String limitSummaryLength(String summary) {
		if (summary.isEmpty()) {
			return summary;
		}

		List<String> lines = Arrays.stream(summary.split("\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.limit(SUMMARY_MAX_LINES)
			.toList();

		String limited = String.join("\n", lines);
		if (limited.length() > SUMMARY_MAX_CHARS) {
			limited = limited.substring(0, SUMMARY_MAX_CHARS).trim();
		}
		return limited;
	}
}
