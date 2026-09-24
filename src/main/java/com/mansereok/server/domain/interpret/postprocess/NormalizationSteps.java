package com.mansereok.server.domain.interpret.postprocess;

import java.util.regex.Matcher;

/**
 * 상품 종류와 무관하게 되풀이되는 후처리 단계. 규칙 클래스들은 이 단계를 조합해서 자기 파이프라인을 만든다.
 */
final class NormalizationSteps {

	private NormalizationSteps() {
	}

	/** 줄바꿈을 {@code \n} 하나로 통일한다. 뒤의 모든 정규식이 이 전제를 깔고 있다. */
	static String normalizeLineEndings(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n");
	}

	/** 요구하지 않은 목차·번호·마크다운 머리말을 지운다. */
	static String stripStructuralLabels(String text) {
		String normalized = NormalizationPatterns.BRACKET_SECTION_TITLE.matcher(text)
			.replaceAll("");
		normalized = NormalizationPatterns.NUMBERED_SUBSECTION.matcher(normalized).replaceAll("");
		normalized = NormalizationPatterns.NUMBERED_LIST.matcher(normalized).replaceAll("");
		return NormalizationPatterns.HASH_HEADER.matcher(normalized).replaceAll("");
	}

	/**
	 * 기간 표기를 통일한다.
	 * 2026-02-04T04:38:00 / 2026-02-04 04:38 / 2026-02-04 를 모두 "2026년 2월" 로 만든다.
	 */
	static String unifyPeriodNotation(String text) {
		String normalized = NormalizationPatterns.ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS
			.matcher(text).replaceAll("$1 $2");
		normalized = NormalizationPatterns.DATETIME_WITH_SPACE.matcher(normalized)
			.replaceAll("$1-$2");
		normalized = NormalizationPatterns.DATE_WITH_DAY.matcher(normalized).replaceAll("$1-$2");
		return convertYearMonthToKorean(normalized);
	}

	private static String convertYearMonthToKorean(String text) {
		Matcher matcher = NormalizationPatterns.YEAR_MONTH.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (matcher.find()) {
			String year = matcher.group(1);
			int month = Integer.parseInt(matcher.group(2));
			matcher.appendReplacement(sb, Matcher.quoteReplacement(year + "년 " + month + "월"));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/** 빈 줄 없이 이어진 문장 단위 줄바꿈을 한 문단의 줄글로 합친다. */
	static String mergeSingleLineBreaksWithinParagraph(String text) {
		return TextBlocks.joinParagraphs(TextBlocks.splitParagraphs(text).stream()
			.map(block -> block.replaceAll("\\n+", " ").replaceAll("[ \\t]{2,}", " "))
			.toList());
	}

	/** 빈 줄이 두 줄 이상 이어지면 한 줄로 줄인다. */
	static String collapseBlankLines(String text) {
		return NormalizationPatterns.THREE_OR_MORE_NEWLINES.matcher(text).replaceAll("\n\n");
	}
}
