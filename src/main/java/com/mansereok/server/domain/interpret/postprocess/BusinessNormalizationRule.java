package com.mansereok.server.domain.interpret.postprocess;

import com.mansereok.server.domain.interpret.text.TextCut;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 사업운(21)·학업운(22)·인생조언(23) 후처리.
 *
 * <p>세 상품은 같은 문제를 공유한다. 명리 용어가 설명 없이 튀어나오고, 오행 점수 같은 내부 수치가
 * 그대로 노출되며, 요청하지 않은 개운법(행운의 색, 방위, 숫자)이 붙는다. 요약은 화면 카드에 들어가므로
 * 줄 수와 글자 수를 함께 제한한다.
 *
 * <p>개운법과 내부 수치는 문장 단위로 뺀다. 세 상품의 프롬프트는 문단 안에서 줄을 바꾸지 말라고 지시하므로
 * 실제 응답의 한 줄은 250~350자짜리 한 문단이다. 줄째 지우면 금지어 한 단어 때문에 유료 본문 문단이
 * 통째로 사라진다.
 */
@Slf4j
final class BusinessNormalizationRule implements SubcategoryNormalizationRule {

	private static final int SUMMARY_MAX_LINES = 5;
	private static final int SUMMARY_MAX_CHARS = 280;

	/** 독자가 모르는 용어를 괄호 설명으로 풀어 준다. */
	private static final Map<String, String> JARGON_EXPANSIONS = jargonExpansions();

	/** 문장 끝 부호 뒤의 공백. 한 줄을 문장으로 나누는 경계다. {@code 3.2} 처럼 부호 뒤에 공백이 없으면 나누지 않는다. */
	private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

	/** 이 말이 들어가면 그 문장은 개운법 안내다. */
	private static final Pattern LUCK_ADVICE_TERM = Pattern.compile("행운의 색|개운색|개운법");

	/**
	 * 방위·색·숫자. 이 말만으로는 개운법인지 알 수 없다("동쪽 지역 거래처와의 협업"). 그래서
	 * {@link #RECOMMENDING_WORD} 와 한 문장에 함께 있을 때만 그 문장을 뺀다.
	 * 숫자 뒤에 숫자가 더 이어지면 걸지 않는다("숫자 30%" 는 숫자 3 이 아니다).
	 */
	private static final Pattern DIRECTION_COLOR_NUMBER = Pattern.compile(
		"청색|녹색|동쪽|서쪽|남쪽|북쪽|(?<!\\d)3과\\s*8(?!\\d)|숫자\\s*3(?!\\d)|숫자\\s*8(?!\\d)");

	/** 방위·색·숫자를 권하는 말. */
	private static final Pattern RECOMMENDING_WORD = Pattern.compile(
		"추천|개운|행운|유리|길하|길한|길합|도움|활용|쓰면|쓰세요|두면|두세요|입으면|입으세요");

	/** 오행 점수를 전하는 문장. 수치를 알려 주는 것이 목적인 문장이라 통째로 뺀다. */
	private static final Pattern ELEMENT_SCORE_TERM = Pattern.compile("오행 점수");

	/**
	 * {@code 내 세력 32.5} 처럼 세력 비교 말 뒤에 붙은 내부 수치. 숫자만 지우고 말과 문장은 남긴다.
	 * 이 말은 서버가 프롬프트 데이터로 넣어 주므로 모델이 되받아 쓰기 쉽고, "시장 내 세력 판도" 처럼 흔한 말이기도 하다.
	 */
	private static final Pattern STRENGTH_SCORE_NUMBER = Pattern.compile(
		"(내 세력|남의 세력)\\s*[:：]?\\s*\\d+(?:\\.\\d+)?");

	/** {@code 목 3.2} 처럼 오행 옆에 붙은 점수. 숫자는 지우고 "기운" 으로만 남긴다. */
	private static final Pattern ELEMENT_SCORE = Pattern.compile("(?m)([목화토금수])\\s*\\d+\\.\\d+");

	/** {@code 비겁(比劫)} 의 괄호 한자처럼 독자가 읽지 않는 원문 병기. */
	private static final Pattern PARENTHESIZED_HANJA = Pattern.compile("\\(\\p{IsHan}+\\)");

	/** 지운 양을 로그에 남길 때 어느 상품인지 밝히는 데만 쓴다. */
	private final Long subcategoryId;

	BusinessNormalizationRule(Long subcategoryId) {
		this.subcategoryId = subcategoryId;
	}

	/**
	 * 치환은 등록 순서대로 이뤄진다. 순서를 바꾸면 결과가 달라지므로 {@link LinkedHashMap} 을 쓰고
	 * 밖에서 고칠 수 없도록 감싼다.
	 */
	private static Map<String, String> jargonExpansions() {
		Map<String, String> expansions = new LinkedHashMap<>();
		expansions.put("수국", "수기운 결속 구조");
		expansions.put("천간충", "천간 충돌(생각과 실행이 맞부딪히는 구조)");
		expansions.put("양인살", "양인살(추진력이 강하지만 과속 시 마찰이 생기기 쉬운 신살)");
		expansions.put("공망", "공망(기대와 현실이 어긋나기 쉬운 구간)");
		expansions.put("역마살", "역마살(이동과 변화가 많아지는 기운)");
		return Collections.unmodifiableMap(expansions);
	}

	@Override
	public String normalizeAnalysis(String text) {
		String normalized = stripLabelsAndJargon(text, "본문");

		// 문장 단위 줄바꿈을 문단 줄글로 정리
		normalized = NormalizationSteps.mergeSingleLineBreaksWithinParagraph(normalized);

		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	@Override
	public String normalizeSummary(String text) {
		String normalized = stripLabelsAndJargon(text, "요약");
		normalized = NormalizationSteps.collapseBlankLines(normalized).trim();
		return limitSummaryLength(normalized);
	}

	/**
	 * 본문과 요약이 공유하는 앞단. 라벨 제거, 기간 통일, 용어 풀이, 금지 문구 제거까지.
	 *
	 * @param part 로그에 적을 대상 이름(본문, 요약)
	 */
	private String stripLabelsAndJargon(String text, String part) {
		String normalized = NormalizationSteps.normalizeLineEndings(text);

		// UI 페이지 구분은 반드시 빈 줄 1개(\n\n)로 통일
		normalized = NormalizationPatterns.PAGE_BREAK.matcher(normalized).replaceAll("\n\n");

		// 요구하지 않은 라벨/목차 제거
		normalized = NormalizationSteps.stripStructuralLabels(normalized);

		normalized = NormalizationSteps.unifyPeriodNotation(normalized);
		normalized = expandJargonForReadability(normalized);
		normalized = removeLuckAdviceAndInternalScores(normalized, part);
		normalized = ELEMENT_SCORE.matcher(normalized).replaceAll("$1 기운");
		return PARENTHESIZED_HANJA.matcher(normalized).replaceAll("");
	}

	private static String expandJargonForReadability(String text) {
		String normalized = text;
		for (Map.Entry<String, String> expansion : JARGON_EXPANSIONS.entrySet()) {
			normalized = normalized.replace(expansion.getKey(), expansion.getValue());
		}
		return normalized;
	}

	/**
	 * 개운법 문장과 오행 점수 문장을 빼고, 세력 비교 수치는 숫자만 지운다.
	 *
	 * <p>줄마다 문장으로 나눠 뺄 문장만 뺀다. 한 줄의 문장이 모두 빠지면 그 줄도 없앤다.
	 * 원래 비어 있던 줄은 문단 경계라 그대로 둔다. 지운 것이 있으면 지운 글자 수를 로그로 남겨
	 * 운영에서 과하게 지우는지 볼 수 있게 한다. 로그에 본문 내용은 남기지 않는다.
	 */
	private String removeLuckAdviceAndInternalScores(String text, String part) {
		List<String> keptLines = new ArrayList<>();
		for (String line : text.split("\n", -1)) {
			String filtered = removeForbiddenSentences(line);
			boolean everySentenceRemoved = filtered.isEmpty() && !line.isBlank();
			if (!everySentenceRemoved) {
				keptLines.add(filtered);
			}
		}
		String result = STRENGTH_SCORE_NUMBER.matcher(String.join("\n", keptLines)).replaceAll("$1");

		int removedChars = text.length() - result.length();
		if (removedChars > 0) {
			log.info("사업운 계열 {}에서 개운법·내부 수치를 지웠다. subcategoryId={}, removedChars={}",
				part, subcategoryId, removedChars);
		}
		return result;
	}

	/** 뺄 문장이 없으면 줄을 그대로 돌려준다. 있으면 남은 문장만 한 칸 띄어 다시 붙인다. */
	private static String removeForbiddenSentences(String line) {
		String[] sentences = SENTENCE_BOUNDARY.split(line);
		List<String> keptSentences = Arrays.stream(sentences)
			.filter(sentence -> !isForbiddenSentence(sentence))
			.toList();
		if (keptSentences.size() == sentences.length) {
			return line;
		}
		return String.join(" ", keptSentences).trim();
	}

	private static boolean isForbiddenSentence(String sentence) {
		if (LUCK_ADVICE_TERM.matcher(sentence).find() || ELEMENT_SCORE_TERM.matcher(sentence).find()) {
			return true;
		}
		return DIRECTION_COLOR_NUMBER.matcher(sentence).find()
			&& RECOMMENDING_WORD.matcher(sentence).find();
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
		// 280번째 글자가 이모지의 앞쪽 절반이면 그 이모지는 통째로 뺀다. 반쪽 글자는 저장할 때 '?' 로 깨진다.
		return TextCut.atCodePointBoundary(limited, SUMMARY_MAX_CHARS).trim();
	}
}
