package com.mansereok.server.domain.interpret.postprocess;

import java.util.List;

/**
 * 케미 궁합(104) 전용 손질.
 *
 * <p>추천 목록 상품이라 "아이돌 1위", "🥇", "1위:" 같은 항목 표시가 줄글 안에 파묻혀 나온다.
 * 그 표시 앞에서 먼저 끊고, 그래도 문단이 모자라면 일반 문맥 기준으로 쪼갠다.
 */
final class ChemistryParagraphs {

	/** 추천 묶음의 머리말. 이 앞에서 문단을 끊는다. */
	private static final String RECOMMENDATION_GROUP_REGEX =
		"\\s*(아이돌\\s*1명\\s*추천\\s*문단|배우\\s*1명\\s*추천\\s*문단|캐릭터\\s*1명\\s*추천\\s*문단"
			+ "|아이돌\\s*추천\\s*3명|배우\\s*추천\\s*3명|캐릭터\\s*추천\\s*3명|종합 원픽\\s*TOP3)";

	/** 개별 추천 항목의 머리말. */
	private static final String RECOMMENDATION_ITEM_REGEX =
		"\\s*(아이돌\\s*[1-3]위|배우\\s*[1-3]위|캐릭터\\s*[1-3]위|아이돌\\s*추천\\s*[1-3]"
			+ "|배우\\s*추천\\s*[1-3]|캐릭터\\s*추천\\s*[1-3]|아이돌\\s*1명|배우\\s*1명|캐릭터\\s*1명)\\s*[:：]?";

	private static final List<String> TOPIC_MARKERS = List.of("또한", "다만", "특히", "반면", "그리고");

	private ChemistryParagraphs() {
	}

	static String ensureParagraphBreaks(String text) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		List<String> existingParagraphs = TextBlocks.splitParagraphs(normalized);
		if (existingParagraphs.size() >= 3) {
			return TextBlocks.joinParagraphs(existingParagraphs);
		}

		String markerSplit = splitOnRecommendationMarkers(normalized);
		List<String> markerParagraphs = TextBlocks.splitParagraphs(markerSplit);
		if (markerParagraphs.size() >= 3) {
			return TextBlocks.joinParagraphs(markerParagraphs);
		}

		return ParagraphSplitter.ensureContextAwareParagraphBreaks(
			markerSplit, TOPIC_MARKERS, 140, 240);
	}

	private static String splitOnRecommendationMarkers(String text) {
		String markerSplit = text.replaceAll(RECOMMENDATION_GROUP_REGEX, "\n\n$1");
		markerSplit = markerSplit.replaceAll("\\s*(🥇|🥈|🥉)\\s*", "\n\n$1 ");
		markerSplit = markerSplit.replaceAll("(?<!\\d)([123])위\\s*[:：]", "\n\n$1위:");
		markerSplit = markerSplit.replaceAll(RECOMMENDATION_ITEM_REGEX, "\n\n$1 ");
		// 새 화제로 넘어가는 "○○은/는" 문장 앞에서도 끊는다
		markerSplit = markerSplit.replaceAll("(?<=[.!?])\\s*(?=[가-힣A-Za-z0-9]{2,20}(은|는)\\s)",
			"\n\n");
		return NormalizationSteps.collapseBlankLines(markerSplit);
	}
}
