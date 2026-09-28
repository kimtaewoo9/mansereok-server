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
 *
 * <p>모든 문장이 개운법이나 내부 수치 문장이면 결과는 빈 문자열이다. 원문으로 되돌리면 지우려던 개운법이
 * 그대로 나가므로 되돌리지 않는다.
 */
@Slf4j
final class BusinessNormalizationRule implements SubcategoryNormalizationRule {

	private static final int SUMMARY_MAX_LINES = 5;
	private static final int SUMMARY_MAX_CHARS = 280;

	/** 독자가 모르는 용어를 괄호 설명으로 풀어 준다. */
	private static final Map<String, String> JARGON_EXPANSIONS = jargonExpansions();

	/** 이 말이 들어가면 그 문장은 개운법 안내다. */
	private static final Pattern LUCK_ADVICE_TERM = Pattern.compile("행운의 색|개운색|개운법");

	/**
	 * 방위·색·숫자. 이 말만으로는 개운법인지 알 수 없다("동쪽 지역 거래처와의 협업이 늘어나는 흐름").
	 * 그래서 {@link #RECOMMENDING_WORD} 와 한 문장에 함께 있을 때만 그 문장을 뺀다.
	 * 숫자 앞뒤에 숫자가 더 붙으면 걸지 않는다("숫자 30%", "13과 8개" 는 숫자 3, 3과 8 이 아니다).
	 * "녹색 성장" 처럼 친환경을 뜻하는 녹색은 색이 아니라서 걸지 않는다.
	 */
	private static final Pattern DIRECTION_COLOR_NUMBER = Pattern.compile(
		"청색|녹색(?!\\s*(?:성장|산업|에너지|금융|기술))|동쪽|서쪽|남쪽|북쪽"
			+ "|(?<!\\d)3과\\s*8(?!\\d)|숫자\\s*3(?!\\d)|숫자\\s*8(?!\\d)");

	/**
	 * 방위·색·숫자를 권하는 말. 사업 말투여도 방위·색·숫자가 좋다거나 유리하다고 하면 개운법으로 본다
	 * ("서쪽 지역 파트너와의 협업이 유리합니다", "동쪽 지역 거래처를 활용하면 매출이 늡니다").
	 * 좋·가까이·입어·배치·향하·챙기는 옷, 소품, 책상 방향을 권하는 흔한 개운법 서술어다.
	 */
	private static final Pattern RECOMMENDING_WORD = Pattern.compile(
		"추천|개운|행운|유리|길하|길한|길합|도움|활용|좋|가까이|쓰면|쓰세요|두면|두세요|입으|입어"
			+ "|배치|향하|향해|향한|챙기");

	/** 오행 점수를 전하는 문장. 수치를 알려 주는 것이 목적인 문장이라 통째로 뺀다. */
	private static final Pattern ELEMENT_SCORE_TERM = Pattern.compile("오행 점수");

	/**
	 * {@code 내 세력이 32.5로}, {@code 남의 세력은 67.5점} 처럼 세력 비교 말 바로 뒤(조사 이·가·은·는, 콜론, 공백만
	 * 사이에 둔)에 수치가 오는 문장. 숫자만 지우면 "내 세력로", "내 세력은 , 남의 세력은 입니다" 처럼 문장이 깨지므로
	 * 오행 점수 문장처럼 통째로 뺀다. 세력 말은 서버가 프롬프트 데이터({@code 내 세력 %.1f vs 남의 세력 %.1f})로
	 * 넣어 주므로 모델이 되받아 쓰기 쉽다. 뒤에 수치가 없는 "시장 내 세력 판도" 같은 말은 걸지 않는다.
	 */
	private static final Pattern STRENGTH_SCORE_TERM = Pattern.compile(
		"(?:내 세력|남의 세력)(?:[이가은는]|\\s*[:：])?\\s*\\d");

	/**
	 * {@code 신약 구조(내 세력 32.5 vs 남의 세력 67.5)라} 처럼 세력 말과 수치가 함께 든 괄호. 괄호째 지우면
	 * 문장이 그대로 읽히므로 문장은 남긴다.
	 */
	private static final Pattern STRENGTH_SCORE_PARENTHESES = Pattern.compile(
		"\\s*\\((?=[^()]*\\d)[^()]*(?:내 세력|남의 세력)[^()]*\\)");

	/** {@code 내 세력(32.5)이} 처럼 세력 말 바로 뒤의 괄호에 든 수치. 괄호째 지우고 세력 말은 남긴다. */
	private static final Pattern STRENGTH_SCORE_AFTER_TERM_IN_PARENTHESES = Pattern.compile(
		"(내 세력|남의 세력)\\s*\\(\\s*\\d+(?:\\.\\d+)?\\s*(?:점|%)?\\s*\\)");

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
		String normalized = stripLabelsAndJargon(text);

		// 문장 단위 줄바꿈을 문단 줄글로 정리. 문장이 두 줄에 걸쳐 있으면("서쪽 지역\n파트너와의 협업이 유리합니다")
		// 줄마다 걸러서는 방위와 권하는 말이 갈려 걸리지 않으므로, 줄을 먼저 합친 뒤 문장을 거른다.
		normalized = NormalizationSteps.mergeSingleLineBreaksWithinParagraph(normalized);
		normalized = removeLuckAdviceAndInternalScores(normalized, "본문");

		return NormalizationSteps.collapseBlankLines(normalized).trim();
	}

	/**
	 * 요약은 줄이 곧 화면 카드의 한 줄이라 줄을 합치지 않고 줄마다 문장을 거른다. 그래서 한 문장이 두 줄에
	 * 걸쳐 방위와 권하는 말이 다른 줄로 갈리면 걸러지지 않는다. 요약 프롬프트는 4~5줄, 280자 이내로 핵심 행동만
	 * 짧게 쓰라고 한다.
	 */
	@Override
	public String normalizeSummary(String text) {
		String normalized = stripLabelsAndJargon(text);
		normalized = removeLuckAdviceAndInternalScores(normalized, "요약");
		normalized = NormalizationSteps.collapseBlankLines(normalized).trim();
		return limitSummaryLength(normalized);
	}

	/** 본문과 요약이 공유하는 앞단. 라벨 제거, 기간 통일, 용어 풀이, 오행 점수·한자 괄호 정리까지. */
	private static String stripLabelsAndJargon(String text) {
		String normalized = NormalizationSteps.normalizeLineEndings(text);

		// UI 페이지 구분은 반드시 빈 줄 1개(\n\n)로 통일
		normalized = NormalizationPatterns.PAGE_BREAK.matcher(normalized).replaceAll("\n\n");

		// 요구하지 않은 라벨/목차 제거
		normalized = NormalizationSteps.stripStructuralLabels(normalized);

		normalized = NormalizationSteps.unifyPeriodNotation(normalized);
		normalized = expandJargonForReadability(normalized);
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
	 * 개운법 문장, 오행 점수 문장, 세력 수치 문장을 빼고, 괄호에 든 세력 수치는 괄호째 지운다.
	 *
	 * <p>줄마다 문장으로 나눠 뺄 문장만 뺀다. 한 줄의 문장이 모두 빠지면 그 줄도 없앤다.
	 * 원래 비어 있던 줄은 문단 경계라 그대로 둔다. 지운 것이 있으면 지운 글자 수를 로그로 남겨
	 * 운영에서 과하게 지우는지 볼 수 있게 한다. 로그에 본문 내용은 남기지 않는다.
	 *
	 * @param part 로그에 적을 대상 이름(본문, 요약)
	 */
	private String removeLuckAdviceAndInternalScores(String text, String part) {
		String withoutParenthesizedScores = STRENGTH_SCORE_PARENTHESES.matcher(text).replaceAll("");
		withoutParenthesizedScores = STRENGTH_SCORE_AFTER_TERM_IN_PARENTHESES
			.matcher(withoutParenthesizedScores).replaceAll("$1");

		List<String> keptLines = new ArrayList<>();
		for (String line : withoutParenthesizedScores.split("\n", -1)) {
			String filtered = removeForbiddenSentences(line);
			boolean everySentenceRemoved = filtered.isEmpty() && !line.isBlank();
			if (!everySentenceRemoved) {
				keptLines.add(filtered);
			}
		}
		String result = String.join("\n", keptLines);

		int removedChars = text.length() - result.length();
		if (removedChars > 0) {
			log.info("사업운 계열 {}에서 개운법·내부 수치를 지웠다. subcategoryId={}, removedChars={}",
				part, subcategoryId, removedChars);
		}
		return result;
	}

	/** 뺄 문장이 없으면 줄을 그대로 돌려준다. 있으면 남은 문장만 한 칸 띄어 다시 붙인다. */
	private static String removeForbiddenSentences(String line) {
		String[] sentences = NormalizationPatterns.SENTENCE_BOUNDARY.split(line);
		List<String> keptSentences = Arrays.stream(sentences)
			.filter(sentence -> !isForbiddenSentence(sentence))
			.toList();
		if (keptSentences.size() == sentences.length) {
			return line;
		}
		return String.join(" ", keptSentences).trim();
	}

	private static boolean isForbiddenSentence(String sentence) {
		if (LUCK_ADVICE_TERM.matcher(sentence).find()
			|| ELEMENT_SCORE_TERM.matcher(sentence).find()
			|| STRENGTH_SCORE_TERM.matcher(sentence).find()) {
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
