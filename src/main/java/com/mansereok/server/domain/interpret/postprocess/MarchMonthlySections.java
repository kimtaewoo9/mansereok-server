package com.mansereok.server.domain.interpret.postprocess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3월 월운(106) 전용 손질.
 *
 * <p>이 상품만 화면이 섹션 단위로 고정돼 있다. GPT 가 제목을 대괄호로 싸거나 줄바꿈으로 쪼개 놓으면
 * 화면이 깨지므로, 제목 변형을 표준 제목으로 되돌린 뒤 섹션별로 본문을 다시 모으고 길이를 자른다.
 */
final class MarchMonthlySections {

	private static final int SECTION_MAX_CHARS = 230;

	/** 화면이 기대하는 섹션 순서. 재조립 결과도 이 순서를 따른다. */
	private static final List<String> SECTION_TITLES = List.of(
		"3월 핵심 키워드",
		"금전운",
		"연애운",
		"학업운",
		"직장/일운",
		"건강운",
		"주의할 점과 조언",
		"3월운 총평"
	);

	/** 제목 변형을 표준 제목으로 되돌리는 표. 여는 대괄호 형태와 줄머리 형태를 모두 본다. */
	private static final List<String[]> TITLE_ALIASES = List.of(
		new String[]{"3월\\s*핵심\\s*키워드", "3월 핵심 키워드"},
		new String[]{"금전\\s*운", "금전운"},
		new String[]{"연애\\s*운", "연애운"},
		new String[]{"학업\\s*운", "학업운"},
		new String[]{"학업\\s*/\\s*일\\s*운", "학업운"},
		new String[]{"직장\\s*운", "직장/일운"},
		new String[]{"직장\\s*/\\s*일\\s*운", "직장/일운"},
		new String[]{"건강\\s*운", "건강운"},
		new String[]{"주의할\\s*점과\\s*조언", "주의할 점과 조언"},
		new String[]{"3월운\\s*총평", "3월운 총평"}
	);

	private MarchMonthlySections() {
	}

	static String normalize(String text) {
		String normalized = text == null ? "" : text;

		// 1) 섹션 제목 변형(대괄호, 줄바꿈 분리, 콜론 표기)을 표준 제목으로 통일
		normalized = unifySectionTitles(normalized);

		// 2) 섹션 단위로 재조립해 제목이 분리되는 문제 방지 + 섹션당 길이 상한 적용
		return rebuildSections(normalized);
	}

	private static String unifySectionTitles(String text) {
		String normalized = text;
		for (String[] alias : TITLE_ALIASES) {
			normalized = normalized.replaceAll("(?is)\\[\\s*" + alias[0] + "\\s*\\]", alias[1]);
		}
		for (String[] alias : TITLE_ALIASES) {
			normalized = normalized.replaceAll("(?m)^\\s*" + alias[0] + "\\s*[:：-]?\\s*",
				"\n\n" + alias[1] + "\n");
		}
		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	private static String rebuildSections(String normalized) {
		Map<String, StringBuilder> sectionBodies = new LinkedHashMap<>();
		for (String title : SECTION_TITLES) {
			sectionBodies.put(title, new StringBuilder());
		}

		String currentTitle = null;
		for (String block : TextBlocks.splitParagraphs(normalized)) {
			if (SECTION_TITLES.contains(block)) {
				currentTitle = block;
				continue;
			}

			String startingTitle = titleStartingBlock(block);
			if (startingTitle != null) {
				currentTitle = startingTitle;
				appendSectionBody(sectionBodies.get(startingTitle),
					block.substring(startingTitle.length()).trim());
				continue;
			}

			if (currentTitle != null) {
				appendSectionBody(sectionBodies.get(currentTitle), block);
			}
		}

		List<String> rebuilt = new ArrayList<>();
		for (String title : SECTION_TITLES) {
			String body = sectionBodies.get(title).toString().trim();
			if (body.isEmpty()) {
				continue;
			}
			rebuilt.add(title + "\n" + trimToSentenceLength(body, SECTION_MAX_CHARS));
		}
		return TextBlocks.joinParagraphs(rebuilt).trim();
	}

	/** 문단이 표준 제목 + 줄바꿈으로 시작하면 그 제목을 돌려준다. */
	private static String titleStartingBlock(String block) {
		for (String title : SECTION_TITLES) {
			if (block.startsWith(title + "\n")) {
				return title;
			}
		}
		return null;
	}

	private static void appendSectionBody(StringBuilder builder, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(" ");
		}
		builder.append(text.replaceAll("\\s+", " ").trim());
	}

	/** 상한을 넘으면 문장 끝에서 자른다. 문장 끝이 너무 앞이면 상한 위치에서 그냥 자른다. */
	private static String trimToSentenceLength(String text, int maxChars) {
		if (text == null) {
			return "";
		}
		String normalized = text.trim();
		if (normalized.length() <= maxChars) {
			return normalized;
		}

		int hardCut = Math.min(maxChars, normalized.length());
		int cut = -1;
		for (String marker : new String[]{"다.", "요.", "니다.", ".", "!", "?"}) {
			int idx = normalized.lastIndexOf(marker, hardCut);
			if (idx > cut) {
				cut = idx + marker.length();
			}
		}

		if (cut < (int) (maxChars * 0.55)) {
			cut = hardCut;
		}
		return normalized.substring(0, cut).trim();
	}
}
