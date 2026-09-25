package com.mansereok.server.domain.interpret.postprocess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

	/**
	 * 제목 변형 하나를 표준 제목으로 되돌리는 규칙. 여는 대괄호 형태와 줄머리 형태를 모두 본다.
	 *
	 * @param bracketed {@code [금전운]} 처럼 대괄호로 싸인 제목
	 * @param lineHead  {@code 금전운:} 처럼 줄머리에 놓인 제목
	 * @param canonical 화면이 기대하는 표준 제목
	 */
	private record TitleAlias(Pattern bracketed, Pattern lineHead, String canonical) {

		static TitleAlias of(String titleRegex, String canonical) {
			return new TitleAlias(
				Pattern.compile("(?is)\\[\\s*" + titleRegex + "\\s*\\]"),
				Pattern.compile("(?m)^\\s*" + titleRegex + "\\s*[:：-]?\\s*"),
				canonical);
		}
	}

	/** 제목 변형을 표준 제목으로 되돌리는 표. 적용 순서가 결과를 바꾸므로 순서를 그대로 둔다. */
	private static final List<TitleAlias> TITLE_ALIASES = List.of(
		TitleAlias.of("3월\\s*핵심\\s*키워드", "3월 핵심 키워드"),
		TitleAlias.of("금전\\s*운", "금전운"),
		TitleAlias.of("연애\\s*운", "연애운"),
		TitleAlias.of("학업\\s*운", "학업운"),
		TitleAlias.of("학업\\s*/\\s*일\\s*운", "학업운"),
		TitleAlias.of("직장\\s*운", "직장/일운"),
		TitleAlias.of("직장\\s*/\\s*일\\s*운", "직장/일운"),
		TitleAlias.of("건강\\s*운", "건강운"),
		TitleAlias.of("주의할\\s*점과\\s*조언", "주의할 점과 조언"),
		TitleAlias.of("3월운\\s*총평", "3월운 총평")
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
		for (TitleAlias alias : TITLE_ALIASES) {
			normalized = alias.bracketed().matcher(normalized).replaceAll(alias.canonical());
		}
		for (TitleAlias alias : TITLE_ALIASES) {
			normalized = alias.lineHead().matcher(normalized)
				.replaceAll("\n\n" + alias.canonical() + "\n");
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
		builder.append(NormalizationPatterns.ANY_WHITESPACE.matcher(text).replaceAll(" ").trim());
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
