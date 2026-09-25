package com.mansereok.server.domain.interpret.postprocess;

import java.util.regex.Pattern;

/**
 * 재물운(20) 후처리. 이 상품은 GPT 가 A. B. 꼴의 영문 절 번호와 대괄호 제목을 자주 만들고,
 * 간혹 아랍 문자 같은 깨진 유니코드를 섞어 내보낸다. 그 둘을 걷어내는 것이 핵심이다.
 */
final class MoneyLuckNormalizationRule implements SubcategoryNormalizationRule {

	/** {@code A. 돈벼락 가능성} 처럼 영문 글자로 매긴 절 제목. */
	private static final Pattern LETTERED_SECTION = Pattern.compile(
		"(?m)^\\s*\\[?[A-H]\\s*[.)]\\s*[^\\n]*\\n?");

	/** 줄바꿈 때문에 두 줄로 쪼개진 대괄호 제목의 앞부분({@code [주의할 점과}). */
	private static final Pattern BRACKET_OPEN_FRAGMENT = Pattern.compile(
		"(?m)^\\s*\\[\\s*([^\\]\\n]{1,120})\\s*$");

	/** 같은 제목의 뒷부분({@code 조언]}). */
	private static final Pattern BRACKET_CLOSE_FRAGMENT = Pattern.compile(
		"(?m)^\\s*([^\\[\\]\\n]{1,120})\\s*\\]\\s*$");

	/** 한 줄이 통째로 대괄호 제목인 경우. */
	private static final Pattern BRACKET_ONLY_HEADING = Pattern.compile(
		"(?m)^\\s*\\[[^\\]\\n]{1,120}\\]\\s*$");

	private static final Pattern ARABIC_OR_CYRILLIC = Pattern.compile(
		"[\\p{IsArabic}\\p{IsCyrillic}]+");

	/** 줄 끝에 남은 공백. */
	private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");

	@Override
	public String normalizeAnalysis(String text) {
		String normalized = NormalizationSteps.normalizeLineEndings(text);
		normalized = NormalizationPatterns.PAGE_BREAK.matcher(normalized).replaceAll("\n\n");
		normalized = LETTERED_SECTION.matcher(normalized).replaceAll("");
		normalized = NormalizationSteps.stripStructuralLabels(normalized);

		// 깨진 대괄호 제목 조각 정리 ([주의할 점과 / 조언] 같은 케이스)
		normalized = BRACKET_OPEN_FRAGMENT.matcher(normalized).replaceAll("$1");
		normalized = BRACKET_CLOSE_FRAGMENT.matcher(normalized).replaceAll("$1");
		normalized = BRACKET_ONLY_HEADING.matcher(normalized).replaceAll("");

		normalized = repairKnownBrokenTokens(normalized);
		normalized = NormalizationSteps.unifyPeriodNotation(normalized);

		// 비정상 유니코드(아랍/키릴) 제거
		normalized = ARABIC_OR_CYRILLIC.matcher(normalized).replaceAll("");

		normalized = NormalizationSteps.mergeSingleLineBreaksWithinParagraph(normalized);
		normalized = NormalizationPatterns.MULTI_SPACE.matcher(normalized).replaceAll(" ");
		normalized = TRAILING_SPACE.matcher(normalized).replaceAll("\n");
		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	/** 실제 운영에서 반복 관찰된 깨짐 토큰 보정. */
	private static String repairKnownBrokenTokens(String text) {
		return text
			.replace("جذب力", "흡인력")
			.replace("جذب 력", "흡인력")
			.replace(" جذب", " 흡인력")
			.replace("جذب", "흡인력");
	}
}
