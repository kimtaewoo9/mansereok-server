package com.mansereok.server.domain.interpret.postprocess;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026 키워드 운세(102) 전용 손질.
 *
 * <p>이 상품은 상담 멘트("직접 대면 상담하듯...")가 본문에 섞여 나오고, 결과가 한 덩어리 줄글로
 * 나오는 일이 잦다. 맨 앞 대괄호 키워드 제목은 살려 두고 본문만 문단으로 쪼갠다.
 */
final class KeywordParagraphs {

	/** 맨 앞 줄의 {@code [2026년 상반기 운명 키워드: 균형]} 같은 제목. */
	private static final Pattern TITLE_LINE = Pattern.compile("^\\s*\\[[^\\]\\n]{1,120}\\]");

	private static final List<String> TOPIC_MARKERS =
		List.of("다만", "특히", "반면", "무엇보다", "결론적으로");

	private KeywordParagraphs() {
	}

	/** 본문에 섞인 상담 멘트·AI 언급을 지운다. */
	static String removeMetaPhrases(String text) {
		String normalized = text.replaceAll(
			"직접\\s*대면\\s*상담하듯\\s*핵심만\\s*전해드(?:립니|릴게)다\\.?", "");
		normalized = normalized.replaceAll("핵심만\\s*전해드(?:립니|릴게)다\\.?", "");
		return normalized.replaceAll("AI가\\s*분석한\\s*결과", "");
	}

	/**
	 * 문단이 이미 3개 이상이면 그대로 두고, 아니면 문맥 기준으로 쪼갠다.
	 * 그래도 문단이 늘지 않고 본문이 길면 더 짧은 기준으로 한 번 더 쪼갠다.
	 */
	static String ensureParagraphBreaks(String text) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		String titleLine = "";
		Matcher titleMatcher = TITLE_LINE.matcher(normalized);
		if (titleMatcher.find() && titleMatcher.start() == 0) {
			titleLine = titleMatcher.group().trim();
			normalized = normalized.substring(titleMatcher.end()).trim();
		}

		List<String> existingParagraphs = TextBlocks.splitParagraphs(normalized);
		if (existingParagraphs.size() >= 3) {
			return withTitle(titleLine, new ArrayList<>(existingParagraphs), normalized);
		}

		String body = ParagraphSplitter.ensureContextAwareParagraphBreaks(
			normalized, TOPIC_MARKERS, 150, 240);
		List<String> rebuilt = new ArrayList<>(TextBlocks.splitParagraphs(body));
		if (rebuilt.size() < 2 && normalized.length() > 120) {
			rebuilt = new ArrayList<>(
				ParagraphSplitter.splitParagraphByContext(normalized, List.of(), 90, 150));
		}
		return withTitle(titleLine, rebuilt, normalized);
	}

	/** 제목이 있으면 첫 문단 위에 붙인다. 쪼갤 문단이 하나도 없으면 원문을 그대로 쓴다. */
	private static String withTitle(String titleLine, List<String> paragraphs, String fallback) {
		if (paragraphs.isEmpty()) {
			return titleLine.isEmpty() ? fallback : titleLine + "\n" + fallback;
		}
		if (!titleLine.isEmpty()) {
			paragraphs.set(0, titleLine + "\n" + paragraphs.get(0));
		}
		return TextBlocks.joinParagraphs(paragraphs);
	}
}
