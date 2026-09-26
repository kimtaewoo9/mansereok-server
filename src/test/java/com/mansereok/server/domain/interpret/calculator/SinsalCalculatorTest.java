package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class SinsalCalculatorTest {

	private static final List<String> STEMS = List.of(
		"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸");
	private static final List<String> BRANCHES = List.of(
		"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥");

	// 삼합 무리마다 붙는 신살 지지: 세 글자, 도화, 역마, 화개
	private static final String[][] SAMHAP_SINSAL_RULE = {
		{"寅午戌", "卯", "申", "戌"},
		{"申子辰", "酉", "寅", "辰"},
		{"巳酉丑", "午", "亥", "丑"},
		{"亥卯未", "子", "巳", "未"},
	};

	// 괴강살이 있는 일주
	private static final List<String> GOEGANG_ILJU = List.of("庚辰", "庚戌", "壬辰", "壬戌", "戊戌");
	// 백호대살이 있는 일주
	private static final List<String> BAEKHO_ILJU = List.of("甲辰", "乙未", "丙戌", "丁丑", "戊辰", "壬戌", "癸丑");

	private final SinsalCalculator calculator = new SinsalCalculator();

	@Test
	void shouldDetectWoldeokAndWoldeokhapByMonthBranch() {
		// 寅월: 월덕귀인=丙, 월덕합=辛
		Map<String, List<String>> result = calculator.analyzeAllSinsal(
			"甲",
			"丙", "子",
			"辛", "寅",
			"甲", "戌",
			null, null
		);

		assertTrue(result.get("년주").contains("월덕귀인"));
		assertTrue(result.get("월주").contains("월덕합"));
	}

	@Test
	void shouldDetectMunchangByDayStem() {
		// 壬일간의 문창귀인은 寅
		Map<String, List<String>> result = calculator.analyzeAllSinsal(
			"壬",
			"甲", "子",
			"乙", "申",
			"壬", "辰",
			"丙", "寅"
		);

		assertTrue(result.get("시주").contains("문창귀인"));
	}

	@Test
	void shouldProvideDescriptionsForNewSinsal() {
		assertTrue(calculator.getSinsalDescription("월덕귀인").contains("완화"));
		assertTrue(calculator.getSinsalDescription("월덕합").contains("화합"));
		assertTrue(calculator.getSinsalDescription("문창귀인").contains("학업"));
	}

	@Test
	void shouldDetectCheondeokAndCheondeokhapByMonthBranch() {
		// 寅월: 천덕귀인=丁, 천덕합=壬
		Map<String, List<String>> result = calculator.analyzeAllSinsal(
			"甲",
			"丁", "子",
			"壬", "寅",
			"甲", "戌",
			null, null
		);

		assertTrue(result.get("년주").contains("천덕귀인"));
		assertTrue(result.get("월주").contains("천덕합"));
	}

	@Test
	void shouldDetectTaegeukAndGukinGwiin() {
		// 甲일간: 태극귀인(子/午), 국인귀인(戌)
		Map<String, List<String>> result = calculator.analyzeAllSinsal(
			"甲",
			"丙", "子",
			"辛", "寅",
			"甲", "戌",
			null, null
		);

		assertTrue(result.get("년주").contains("태극귀인"));
		assertTrue(result.get("일주").contains("국인귀인"));
	}

	@Test
	void shouldIncludeMunchangAndWoldeokForGivenSampleCase() {
		// 사용자 제보 케이스:
		// 년주 戊寅 / 월주 庚申 / 일주 壬子 / 시주 丙午
		// 壬일간 기준 문창귀인=寅(년지), 申월 기준 월덕귀인=壬(일간)
		Map<String, List<String>> result = calculator.analyzeAllSinsal(
			"壬",
			"戊", "寅",
			"庚", "申",
			"壬", "子",
			"丙", "午"
		);

		assertTrue(result.get("년주").contains("문창귀인"));
		assertTrue(result.get("일주").contains("월덕귀인"));
	}

	@Nested
	@DisplayName("공망은")
	class Gongmang {

		@ParameterizedTest(name = "[{index}] {0}{1} 일주 → {2}, {3}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.SinsalCalculatorTest#everyIljuWithEmptyBranches")
		@DisplayName("60갑자 모두 그 일주가 속한 순(간지 10개)이 쓰지 않은 두 지지를 지지 순서대로 돌려준다")
		void returnsBranchesUnusedInSun(String stem, String branch, String first, String second) {
			// when
			List<String> gongmang = calculator.calculateGongmang(stem, branch);

			// then
			assertThat(gongmang).containsExactly(first, second);
		}

		@ParameterizedTest(name = "[{index}] {0}({1}) → {2}, {3}")
		@DisplayName("여섯 순마다 공망 두 지지가 정해져 있고, 壬子 일주가 드는 갑진순은 寅, 卯 다")
		@CsvSource(textBlock = """
			# 순, 그 순에 드는 일주, 공망 두 지지
			갑자순, 甲子, 戌, 亥
			갑술순, 甲戌, 申, 酉
			갑신순, 甲申, 午, 未
			갑오순, 甲午, 辰, 巳
			갑진순, 壬子, 寅, 卯
			갑인순, 癸亥, 子, 丑
			""")
		void returnsEmptyBranchesOfEachSun(String sun, String ilju, String first, String second) {
			// when
			List<String> gongmang = calculator.calculateGongmang(ilju.substring(0, 1), ilju.substring(1));

			// then
			assertThat(gongmang).containsExactly(first, second);
		}

		@ParameterizedTest(name = "[{index}] {0}{1}")
		@DisplayName("60갑자에 없는 조합(양간과 음지, 음간과 양지)이면 빈 목록을 돌려준다")
		@CsvSource({"甲, 丑", "乙, 子"})
		void returnsEmptyForCombinationOutsideSixtyGapja(String stem, String branch) {
			// when
			List<String> gongmang = calculator.calculateGongmang(stem, branch);

			// then
			assertThat(gongmang).isEmpty();
		}
	}

	@Nested
	@DisplayName("괴강살과 백호대살은")
	class GoegangAndBaekho {

		@ParameterizedTest(name = "[{index}] {0}{1} 일주 → 괴강 {2}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.SinsalCalculatorTest#everyIljuWithGoegang")
		@DisplayName("60갑자 중 庚辰·庚戌·壬辰·壬戌·戊戌 일주에만 괴강살이 있다")
		void goegangOnlyForFiveIlju(String stem, String branch, boolean expected) {
			// when
			boolean hasGoegang = calculator.hasGoegang(stem, branch);

			// then
			assertThat(hasGoegang).isEqualTo(expected);
		}

		@ParameterizedTest(name = "[{index}] {0}{1} 일주 → 백호 {2}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.SinsalCalculatorTest#everyIljuWithBaekho")
		@DisplayName("60갑자 중 甲辰·乙未·丙戌·丁丑·戊辰·壬戌·癸丑 일주에만 백호대살이 있다")
		void baekhoOnlyForSevenIlju(String stem, String branch, boolean expected) {
			// when
			boolean hasBaekho = calculator.hasBaekho(stem, branch);

			// then
			assertThat(hasBaekho).isEqualTo(expected);
		}
	}

	@Nested
	@DisplayName("연지·일지 기준 신살은")
	class ByYearOrDayBranch {

		@ParameterizedTest(name = "[{index}] 연지·일지 {0} 기준 시지 {2} → {1}")
		@MethodSource("com.mansereok.server.domain.interpret.calculator.SinsalCalculatorTest#samhapSinsalOfEveryBranch")
		@DisplayName("12지지 모두 그 지지가 속한 삼합 무리의 도화·역마·화개 지지에 붙는다")
		void attachesToBranchOfSamhapGroup(String baseBranch, String sinsal, String targetBranch) {
			// when
			List<String> timePillarSinsal = timePillarSinsal("甲", baseBranch, baseBranch, targetBranch);

			// then
			assertThat(timePillarSinsal).contains(sinsal);
		}

		@ParameterizedTest(name = "[{index}] 연지 {0}, 일지 {1}, 시지 {2}")
		@DisplayName("연지와 일지 중 한쪽만 기준에 맞아도 도화살이 붙는다")
		@CsvSource(textBlock = """
			# 연지, 일지, 시지. 寅의 도화는 卯, 子의 도화는 酉
			寅, 子, 卯
			子, 寅, 卯
			""")
		void attachesWhenEitherYearOrDayMatches(String yearBranch, String dayBranch, String timeBranch) {
			// when
			List<String> timePillarSinsal = timePillarSinsal("甲", yearBranch, dayBranch, timeBranch);

			// then
			assertThat(timePillarSinsal).contains("도화살");
		}
	}

	@Nested
	@DisplayName("일간 기준 신살은")
	class ByDayStem {

		@ParameterizedTest(name = "[{index}] {0} 일간 → {1}, {2}")
		@DisplayName("천을귀인은 일간마다 정해진 두 지지에 붙는다")
		@CsvSource(textBlock = """
			甲, 丑, 未
			乙, 子, 申
			丙, 亥, 酉
			丁, 亥, 酉
			戊, 丑, 未
			己, 子, 申
			庚, 丑, 未
			辛, 寅, 午
			壬, 卯, 巳
			癸, 卯, 巳
			""")
		void cheoneulAttachesToTwoBranches(String stem, String monthBranch, String timeBranch) {
			// when
			Map<String, List<String>> result = calculator.analyzeAllSinsal(
				stem, "甲", "戌", "甲", monthBranch, stem, "戌", null, timeBranch);

			// then
			assertThat(result.get("월주")).contains("천을귀인");
			assertThat(result.get("시주")).contains("천을귀인");
		}

		@ParameterizedTest(name = "[{index}] {0} 일간 + 시지 {1}")
		@DisplayName("양인살은 양간(甲丙戊庚壬)의 제왕 지지에 붙는다")
		@CsvSource(textBlock = """
			甲, 卯
			丙, 午
			戊, 午
			庚, 酉
			壬, 子
			""")
		void yanginAttachesForYangStem(String stem, String timeBranch) {
			// when
			List<String> timePillarSinsal = timePillarSinsal(stem, "戌", "戌", timeBranch);

			// then
			assertThat(timePillarSinsal).contains("양인살");
		}

		@ParameterizedTest(name = "[{index}] {0} 일간 + 시지 {1}")
		@DisplayName("음간(乙丁己辛癸)은 제왕 지지를 만나도 양인살이 없다")
		@CsvSource(textBlock = """
			乙, 寅
			丁, 巳
			己, 巳
			辛, 申
			癸, 亥
			""")
		void noYanginForYinStem(String stem, String timeBranch) {
			// when
			List<String> timePillarSinsal = timePillarSinsal(stem, "戌", "戌", timeBranch);

			// then
			assertThat(timePillarSinsal).doesNotContain("양인살");
		}

		@ParameterizedTest(name = "[{index}] {0} 일간 + 시지 {1}")
		@DisplayName("홍염살은 일간마다 정해진 지지에 붙는다")
		@CsvSource(textBlock = """
			甲, 午
			乙, 申
			丙, 寅
			丁, 未
			戊, 辰
			己, 辰
			庚, 戌
			辛, 酉
			壬, 子
			癸, 申
			""")
		void hongyeomAttachesToBranchOfStem(String stem, String timeBranch) {
			// when
			List<String> timePillarSinsal = timePillarSinsal(stem, "戌", "戌", timeBranch);

			// then
			assertThat(timePillarSinsal).contains("홍염살");
		}
	}

	/**
	 * 시주에 붙은 신살만 꺼낸다. 년간·월간·월지는 결과에 상관없는 값으로 고정하고, 시간은 비워 천간 기준 신살이 끼지 않게 한다.
	 */
	private List<String> timePillarSinsal(String stem, String yearBranch, String dayBranch, String timeBranch) {
		return calculator.analyzeAllSinsal(stem, "甲", yearBranch, "甲", "戌", stem, dayBranch, null, timeBranch)
			.get("시주");
	}

	/**
	 * 60갑자를 천간 10개와 지지 12개를 함께 한 칸씩 돌려 만들고, 일주가 속한 순(간지 10개)이 쓰지 않은 두 지지를 기대값으로 둔다.
	 * 순 첫 간지의 지지에서 10칸, 11칸 뒤 지지와 같다.
	 */
	static Stream<Arguments> everyIljuWithEmptyBranches() {
		return IntStream.range(0, 60).mapToObj(index -> {
			int sunStart = index - index % 10;
			List<String> unused = new ArrayList<>(BRANCHES);
			IntStream.range(sunStart, sunStart + 10).forEach(i -> unused.remove(BRANCHES.get(i % 12)));
			return Arguments.of(STEMS.get(index % 10), BRANCHES.get(index % 12), unused.get(0), unused.get(1));
		});
	}

	static Stream<Arguments> everyIljuWithGoegang() {
		return IntStream.range(0, 60).mapToObj(index -> {
			String stem = STEMS.get(index % 10);
			String branch = BRANCHES.get(index % 12);
			return Arguments.of(stem, branch, GOEGANG_ILJU.contains(stem + branch));
		});
	}

	static Stream<Arguments> everyIljuWithBaekho() {
		return IntStream.range(0, 60).mapToObj(index -> {
			String stem = STEMS.get(index % 10);
			String branch = BRANCHES.get(index % 12);
			return Arguments.of(stem, branch, BAEKHO_ILJU.contains(stem + branch));
		});
	}

	static Stream<Arguments> samhapSinsalOfEveryBranch() {
		return Stream.of(SAMHAP_SINSAL_RULE).flatMap(rule -> Stream.of(rule[0].split("")).flatMap(base -> Stream.of(
			Arguments.of(base, "도화살", rule[1]),
			Arguments.of(base, "역마살", rule[2]),
			Arguments.of(base, "화개살", rule[3]))));
	}
}
