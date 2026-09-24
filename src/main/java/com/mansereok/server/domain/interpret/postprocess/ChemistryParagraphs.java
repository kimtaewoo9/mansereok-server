package com.mansereok.server.domain.interpret.postprocess;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 케미 궁합(104) 전용 손질.
 *
 * <p>추천 목록 상품이라 "아이돌 1위", "🥇", "1위:" 같은 항목 표시가 줄글 안에 파묻혀 나온다.
 * 그 표시 앞에서 먼저 끊고, 그래도 문단이 모자라면 일반 문맥 기준으로 쪼갠다.
 */
final class ChemistryParagraphs {

	/** {@code [아이돌 추천]} 처럼 화면에 필요 없는 묶음 라벨. */
	static final Pattern RECOMMENDATION_LABEL = Pattern.compile(
		"\\[(아이돌\\s*추천|배우\\s*추천|캐릭터\\s*추천)\\]\\s*");

	/** 추천 묶음의 머리말. 이 앞에서 문단을 끊는다. */
	private static final Pattern RECOMMENDATION_GROUP = Pattern.compile(
		"\\s*(아이돌\\s*1명\\s*추천\\s*문단|배우\\s*1명\\s*추천\\s*문단|캐릭터\\s*1명\\s*추천\\s*문단"
			+ "|아이돌\\s*추천\\s*3명|배우\\s*추천\\s*3명|캐릭터\\s*추천\\s*3명|종합 원픽\\s*TOP3)");

	/** 개별 추천 항목의 머리말. */
	private static final Pattern RECOMMENDATION_ITEM = Pattern.compile(
		"\\s*(아이돌\\s*[1-3]위|배우\\s*[1-3]위|캐릭터\\s*[1-3]위|아이돌\\s*추천\\s*[1-3]"
			+ "|배우\\s*추천\\s*[1-3]|캐릭터\\s*추천\\s*[1-3]|아이돌\\s*1명|배우\\s*1명|캐릭터\\s*1명)\\s*[:：]?");

	/** 메달 이모지로 매긴 순위 표시. */
	private static final Pattern MEDAL_RANK = Pattern.compile("\\s*(🥇|🥈|🥉)\\s*");

	/** {@code 1위:} 꼴의 순위 표시. 앞에 숫자가 붙은 연도 등은 건드리지 않는다. */
	private static final Pattern NUMBERED_RANK = Pattern.compile("(?<!\\d)([123])위\\s*[:：]");

	/** 새 화제로 넘어가는 {@code ○○은/는} 문장의 시작 지점. */
	private static final Pattern NEW_TOPIC_SENTENCE = Pattern.compile(
		"(?<=[.!?])\\s*(?=[가-힣A-Za-z0-9]{2,20}(은|는)\\s)");

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
		String markerSplit = RECOMMENDATION_GROUP.matcher(text).replaceAll("\n\n$1");
		markerSplit = MEDAL_RANK.matcher(markerSplit).replaceAll("\n\n$1 ");
		markerSplit = NUMBERED_RANK.matcher(markerSplit).replaceAll("\n\n$1위:");
		markerSplit = RECOMMENDATION_ITEM.matcher(markerSplit).replaceAll("\n\n$1 ");
		// 새 화제로 넘어가는 "○○은/는" 문장 앞에서도 끊는다
		markerSplit = NEW_TOPIC_SENTENCE.matcher(markerSplit).replaceAll("\n\n");
		return NormalizationSteps.collapseBlankLines(markerSplit);
	}
}
