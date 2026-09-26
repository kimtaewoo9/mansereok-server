package com.mansereok.server.domain.interpret.postprocess;

import com.mansereok.server.domain.interpret.text.TextCut;
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
 *
 * <p>다시 모으면서 본문을 되도록 버리지 않는다. 첫 제목 앞의 제목 없는 도입 문단은 첫 섹션 본문 앞에 붙이되,
 * 섹션 본문이 길이 상한을 쓰고 남은 자리만큼만 붙인다. 다시 모은 결과가 비면(표준 제목이 하나도 맞지 않은
 * 경우 등) 받은 글을 그대로 돌려준다.
 */
final class MarchMonthlySections {

	private static final int SECTION_MAX_CHARS = 230;

	/**
	 * {@code [3월의 금전운]} 처럼 문단 머리에 온, 표준 제목으로 되돌리지 못한 대괄호 제목.
	 * 표준 제목의 대괄호는 {@link #unifySectionTitles} 가 먼저 벗기므로 여기까지 남은 것은 표에 없는 제목이다.
	 */
	private static final Pattern UNKNOWN_BRACKET_TITLE = Pattern.compile("\\[[^\\[\\]\\n]{1,30}\\]");

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
		String original = text == null ? "" : text;

		// 1) 섹션 제목 변형(대괄호, 줄바꿈 분리, 콜론 표기)을 표준 제목으로 통일
		String normalized = unifySectionTitles(original);

		// 2) 섹션 단위로 재조립해 제목이 분리되는 문제 방지 + 섹션당 길이 상한 적용
		String rebuilt = rebuildSections(normalized);

		// 3) 재조립 결과가 비면(표준 제목이 하나도 맞지 않은 경우 등) 받은 글을 그대로 돌려준다.
		//    빈 결과가 그대로 저장되면 사용자는 빈 화면을 본다. 빈 줄 정리는 앞단(FreeFortuneNormalizationRule)이
		//    이 메서드를 부르기 전에 문단을 다시 붙이면서 이미 했다.
		if (rebuilt.isEmpty()) {
			return original;
		}
		return rebuilt;
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

		StringBuilder intro = new StringBuilder();
		String currentTitle = null;
		boolean introEnded = false;
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
				continue;
			}

			// 첫 표준 제목보다 앞에 온 문단. 제목 없는 도입 문단만 살린다. 표에 없는 대괄호 제목("[3월의 금전운]")이
			// 나오면 거기부터 첫 표준 제목까지는 어느 섹션 글인지 알 수 없어 버린다. 도입 문단으로 붙이면
			// 다른 섹션의 글이 대괄호 제목째 첫 섹션에 들어간다.
			if (UNKNOWN_BRACKET_TITLE.matcher(block).lookingAt()) {
				introEnded = true;
			}
			if (!introEnded) {
				appendSectionBody(intro, block);
			}
		}

		List<String> rebuilt = new ArrayList<>();
		String introToPlace = intro.toString();
		// SECTION_TITLES 순서가 화면 순서다. 도입 문단은 본문이 있는 첫 섹션에만 붙인다.
		for (String title : SECTION_TITLES) {
			String body = sectionBodies.get(title).toString().trim();
			if (body.isEmpty()) {
				continue;
			}
			String cappedBody = trimToSentenceLength(body, SECTION_MAX_CHARS);
			if (!introToPlace.isEmpty()) {
				cappedBody = prependIntroWithinLimit(introToPlace, cappedBody);
				introToPlace = "";
			}
			rebuilt.add(title + "\n" + cappedBody);
		}
		return TextBlocks.joinParagraphs(rebuilt).trim();
	}

	/**
	 * 도입 문단을 섹션 본문 앞에 붙이되 섹션 본문은 한 글자도 밀어내지 않는다. 본문이 길이 상한을 쓰고 남은
	 * 자리에 도입 문단의 앞 문장부터 통째로 들어가는 만큼만 붙이고, 한 문장도 들어가지 않으면 붙이지 않는다.
	 * 도입 문단을 붙인 뒤에 상한을 걸면, 긴 도입 문단이 상한을 다 차지해 섹션의 원래 문장이 잘려 나간다.
	 */
	private static String prependIntroWithinLimit(String intro, String body) {
		int room = SECTION_MAX_CHARS - body.length() - 1; // 1 은 도입 문단과 본문 사이의 공백
		StringBuilder fitted = new StringBuilder();
		for (String sentence : NormalizationPatterns.SENTENCE_BOUNDARY.split(intro)) {
			int separator = fitted.isEmpty() ? 0 : 1;
			if (fitted.length() + separator + sentence.length() > room) {
				break;
			}
			if (separator == 1) {
				fitted.append(' ');
			}
			fitted.append(sentence);
		}
		return fitted.isEmpty() ? body : fitted + " " + body;
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
			// 상한 위치에서 자르되 이모지 같은 서로게이트 쌍은 쪼개지 않는다. 반쪽 글자는 저장할 때 '?' 로 깨진다.
			return TextCut.atCodePointBoundary(normalized, maxChars).trim();
		}
		// 문장 끝 부호(. ! ?) 바로 뒤에서 자르므로 서로게이트 쌍이 쪼개질 일이 없다.
		return normalized.substring(0, cut).trim();
	}
}
