package com.mansereok.server.domain.interpret.postprocess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 한 덩어리로 쏟아진 줄글을 읽기 좋은 문단으로 쪼갠다.
 *
 * <p>기준은 두 가지다. 상품마다 다른 소제목(topicMarkers)과, 어느 상품에서나 화제가 바뀔 때 쓰이는
 * 접속 표현(FREE_PARAGRAPH_TRANSITIONS). 둘 중 하나가 다음 문장 앞에 오고 지금 문단이 최소 길이를
 * 넘었으면 거기서 끊는다. 길이 상한을 넘으면 접속 표현과 무관하게 끊는다.
 */
final class ParagraphSplitter {

	private static final List<String> FREE_PARAGRAPH_TRANSITIONS = List.of(
		"다만", "반면", "또한", "그리고", "한편", "특히", "무엇보다", "이때", "여기서", "정리하면",
		"결론적으로", "요약하면", "반대로");

	private ParagraphSplitter() {
	}

	/**
	 * 소제목 앞에서 한 번 끊고, 각 문단을 다시 길이·문맥 기준으로 쪼갠다.
	 *
	 * @param minChars 이 길이를 넘어야 문맥 전환에서 끊는다
	 * @param maxChars 이 길이를 넘으면 문맥과 무관하게 끊는다
	 */
	static String ensureContextAwareParagraphBreaks(String text, List<String> topicMarkers,
		int minChars, int maxChars) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		String withMarkerHints = normalized;
		for (String marker : topicMarkers) {
			withMarkerHints = withMarkerHints.replaceAll(
				"(?<!\\n\\n)\\s+(?=" + Pattern.quote(marker) + ")",
				"\n\n");
		}
		withMarkerHints = NormalizationSteps.collapseBlankLines(withMarkerHints);

		List<String> rebuilt = new ArrayList<>();
		for (String paragraph : TextBlocks.splitParagraphs(withMarkerHints)) {
			rebuilt.addAll(splitParagraphByContext(paragraph, topicMarkers, minChars, maxChars));
		}
		return TextBlocks.joinParagraphs(rebuilt);
	}

	/** 문단 하나를 문장 단위로 훑으며 문맥 전환 지점과 길이 상한에서 끊는다. */
	static List<String> splitParagraphByContext(String paragraph, List<String> topicMarkers,
		int minChars, int maxChars) {
		List<String> sentences = Arrays.stream(paragraph.split("(?<=[.!?])\\s+"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();
		if (sentences.isEmpty()) {
			return List.of(paragraph);
		}
		if (sentences.size() == 1 && paragraph.length() <= maxChars) {
			return List.of(paragraph);
		}

		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < sentences.size(); i++) {
			if (current.length() > 0) {
				current.append(" ");
			}
			current.append(sentences.get(i));

			String next = (i + 1) < sentences.size() ? sentences.get(i + 1).trim() : "";
			boolean contextShift = startsWithAny(next, topicMarkers)
				|| startsWithAny(next, FREE_PARAGRAPH_TRANSITIONS);
			boolean overSoftLimit = current.length() >= maxChars;
			boolean canSplit = current.length() >= minChars;

			if ((contextShift && canSplit) || overSoftLimit) {
				chunks.add(current.toString().trim());
				current.setLength(0);
			}
		}

		if (current.length() > 0) {
			chunks.add(current.toString().trim());
		}
		return chunks;
	}

	private static boolean startsWithAny(String text, List<String> prefixes) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String trimmed = text.trim();
		return prefixes.stream().anyMatch(trimmed::startsWith);
	}
}
