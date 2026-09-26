package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 지지 144쌍과 천간 100쌍의 관계를 규칙과 한 쌍씩 대조한다.
 *
 * <p>만세력 계산은 년지-월지, 월지-일지처럼 두 글자를 정해진 순서로 넘기므로, 글자 순서를 바꿔도 결과가 같아야 한다. 기대값은
 * 서버 표를 베끼지 않고 아래 쌍 목록(관계마다 성립하는 두 글자)에서 만든다. 결과 목록의 순서는 프롬프트에 그대로 나가므로
 * 서버가 확인하는 순서(충, 원진, 형, 파, 해, 반합)까지 맞춘다.
 */
@DisplayName("지지·천간 관계 계산")
class RelationCalculatorTest {

	private static final List<String> BRANCHES = List.of(
		"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥");
	private static final List<String> STEMS = List.of(
		"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸");

	// 관계마다 성립하는 두 글자. 글자 순서는 상관없다.
	private static final List<String> CHUNG_PAIRS = List.of("子午", "丑未", "寅申", "卯酉", "辰戌", "巳亥");
	private static final List<String> WONJIN_PAIRS = List.of("子未", "丑午", "寅酉", "卯申", "辰亥", "巳戌");
	// 삼형(寅巳申, 丑戌未)은 셋 중 아무 두 글자, 상형은 子卯, 자형은 같은 글자 둘(辰, 午, 酉, 亥)
	private static final List<String> HYEONG_PAIRS = List.of(
		"寅巳", "巳申", "申寅", "丑戌", "戌未", "未丑", "子卯", "辰辰", "午午", "酉酉", "亥亥");
	private static final List<String> PA_PAIRS = List.of("子酉", "丑辰", "寅亥", "卯午", "巳申", "未戌");
	private static final List<String> HAE_PAIRS = List.of("子未", "丑午", "寅巳", "卯辰", "申亥", "酉戌");

	// 삼합 무리: 세 글자, 이름, 가운데 글자
	private static final List<String[]> SAMHAP_GROUPS = List.of(
		new String[]{"申子辰", "수국(물)", "子"},
		new String[]{"寅午戌", "화국(불)", "午"},
		new String[]{"巳酉丑", "금국(쇠)", "酉"},
		new String[]{"亥卯未", "목국(나무)", "卯"});

	private static final List<String> SKY_HAP_PAIRS = List.of("甲己", "乙庚", "丙辛", "丁壬", "戊癸");
	private static final List<String> SKY_CHUNG_PAIRS = List.of("甲庚", "乙辛", "丙壬", "丁癸");

	private final RelationCalculator calculator = new RelationCalculator();

	@Nested
	@DisplayName("지지 두 글자의 관계는")
	class BranchPair {

		@ParameterizedTest(name = "[{index}] {0}{1}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.RelationCalculatorTest#everyBranchPair")
		@DisplayName("144쌍 모두 글자 순서를 바꿔도 같다")
		void isSameInEitherOrder(String first, String second) {
			// when
			List<String> forward = calculator.analyzeRelation(first, second);

			// then
			assertThat(calculator.analyzeRelation(second, first)).containsExactlyElementsOf(forward);
		}

		@ParameterizedTest(name = "[{index}] {0}{1} → {2}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.RelationCalculatorTest#everyBranchPairByRule")
		@DisplayName("144쌍 모두 충·원진·형·파·해·반합 쌍 목록으로 만든 관계와 같다")
		void matchesPairRules(String first, String second, List<String> expected) {
			// when
			List<String> relations = calculator.analyzeRelation(first, second);

			// then
			assertThat(relations).containsExactlyElementsOf(expected);
		}

		@ParameterizedTest(name = "[{index}] {0}{1} → [{2}]")
		@DisplayName("대표 쌍은 표에 적은 관계를 그 순서대로 돌려준다")
		@CsvSource(textBlock = """
			# 지지 1, 지지 2, 관계(쉼표로 이음)
			子, 午, 충
			子, 卯, 형
			子, 酉, 파
			卯, 辰, 해
			子, 未, '원진, 해'
			寅, 申, '충, 형'
			巳, 申, '형, 파'
			辰, 辰, 형
			申, 子, 반합(수국(물))
			午, 戌, 반합(화국(불))
			# 가운데 글자(子)가 없는 두 글자는 같은 삼합 무리여도 반합이 아니다
			申, 辰, ''
			# 육합(子丑)은 이 계산기가 보지 않는다
			子, 丑, ''
			子, 寅, ''
			""")
		void returnsRelationsOfRepresentativePairs(String first, String second, String expected) {
			// when
			List<String> relations = calculator.analyzeRelation(first, second);

			// then
			assertThat(String.join(", ", relations)).isEqualTo(expected);
		}

		@ParameterizedTest(name = "[{index}] {0}, {1}")
		@DisplayName("한쪽이라도 비어 있으면 빈 목록을 돌려준다")
		@CsvSource(textBlock = """
			  , 子
			子,
			  ,
			""")
		void returnsEmptyWhenEitherIsMissing(String first, String second) {
			// when
			List<String> relations = calculator.analyzeRelation(first, second);

			// then
			assertThat(relations).isEmpty();
		}
	}

	@Nested
	@DisplayName("천간 두 글자의 관계는")
	class StemPair {

		@ParameterizedTest(name = "[{index}] {0}{1}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.RelationCalculatorTest#everyStemPair")
		@DisplayName("100쌍 모두 글자 순서를 바꿔도 같다")
		void isSameInEitherOrder(String first, String second) {
			// when
			List<String> forward = calculator.analyzeSkyRelation(first, second);

			// then
			assertThat(calculator.analyzeSkyRelation(second, first)).containsExactlyElementsOf(forward);
		}

		@ParameterizedTest(name = "[{index}] {0}{1} → {2}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.RelationCalculatorTest#everyStemPairByRule")
		@DisplayName("100쌍 모두 천간합(甲己·乙庚·丙辛·丁壬·戊癸)과 천간충(甲庚·乙辛·丙壬·丁癸) 목록으로 만든 관계와 같다")
		void matchesPairRules(String first, String second, List<String> expected) {
			// when
			List<String> relations = calculator.analyzeSkyRelation(first, second);

			// then
			assertThat(relations).containsExactlyElementsOf(expected);
		}

		@ParameterizedTest(name = "[{index}] {0}, {1}")
		@DisplayName("한쪽이라도 비어 있으면 빈 목록을 돌려준다")
		@CsvSource(textBlock = """
			  , 甲
			甲,
			""")
		void returnsEmptyWhenEitherIsMissing(String first, String second) {
			// when
			List<String> relations = calculator.analyzeSkyRelation(first, second);

			// then
			assertThat(relations).isEmpty();
		}
	}

	@Nested
	@DisplayName("사주 지지 전체에서 삼합을 찾을 때")
	class FullSamhap {

		@ParameterizedTest(name = "[{index}] {0} → [{1}]")
		@DisplayName("세 글자가 모두 있는 무리만 완성으로 돌려준다")
		@CsvSource(textBlock = """
			# 사주 지지(시지를 모르면 셋), 완성된 삼합(쉼표로 이음)
			申子辰午, 삼합(수국(물) 완성)
			寅午戌子, 삼합(화국(불) 완성)
			巳酉丑,   삼합(금국(쇠) 완성)
			亥卯未酉, 삼합(목국(나무) 완성)
			# 두 글자만 있으면 완성이 아니다
			申子午卯, ''
			寅戌巳亥, ''
			""")
		void returnsOnlyCompletedGroups(String branches, String expected) {
			// when
			List<String> samhap = calculator.findFullSamhap(Arrays.asList(branches.split("")));

			// then
			assertThat(String.join(", ", samhap)).isEqualTo(expected);
		}
	}

	static Stream<Arguments> everyBranchPair() {
		return pairsOf(BRANCHES).map(pair -> Arguments.of(pair[0], pair[1]));
	}

	/**
	 * 144쌍마다 쌍 목록에 들어 있는 관계를 충, 원진, 형, 파, 해, 반합 순서로 모아 기대값을 만든다.
	 *
	 * <p>반합은 두 글자가 같은 삼합 무리에 있고 둘 중 하나가 그 무리의 가운데 글자일 때다. 지금 코드는 같은 가운데 글자 두 개
	 * (子子·午午·卯卯·酉酉)도 이 규칙대로 반합으로 본다.
	 */
	static Stream<Arguments> everyBranchPairByRule() {
		return pairsOf(BRANCHES).map(pair -> {
			String first = pair[0];
			String second = pair[1];
			List<String> expected = new ArrayList<>();
			addIfPaired(expected, "충", CHUNG_PAIRS, first, second);
			addIfPaired(expected, "원진", WONJIN_PAIRS, first, second);
			addIfPaired(expected, "형", HYEONG_PAIRS, first, second);
			addIfPaired(expected, "파", PA_PAIRS, first, second);
			addIfPaired(expected, "해", HAE_PAIRS, first, second);
			for (String[] group : SAMHAP_GROUPS) {
				boolean sameGroup = group[0].contains(first) && group[0].contains(second);
				boolean hasCenter = first.equals(group[2]) || second.equals(group[2]);
				if (sameGroup && hasCenter) {
					expected.add("반합(" + group[1] + ")");
				}
			}
			return Arguments.of(first, second, expected);
		});
	}

	static Stream<Arguments> everyStemPair() {
		return pairsOf(STEMS).map(pair -> Arguments.of(pair[0], pair[1]));
	}

	static Stream<Arguments> everyStemPairByRule() {
		return pairsOf(STEMS).map(pair -> {
			List<String> expected = new ArrayList<>();
			addIfPaired(expected, "천간합", SKY_HAP_PAIRS, pair[0], pair[1]);
			addIfPaired(expected, "천간충", SKY_CHUNG_PAIRS, pair[0], pair[1]);
			return Arguments.of(pair[0], pair[1], expected);
		});
	}

	private static Stream<String[]> pairsOf(List<String> letters) {
		return letters.stream().flatMap(first -> letters.stream().map(second -> new String[]{first, second}));
	}

	private static void addIfPaired(List<String> expected, String relation, List<String> pairs,
		String first, String second) {
		if (pairs.contains(first + second) || pairs.contains(second + first)) {
			expected.add(relation);
		}
	}
}
