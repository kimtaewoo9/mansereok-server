package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SajuDataServiceTest {

	private static final List<String> STEMS = List.of(
		"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸");
	private static final List<String> BRANCHES = List.of(
		"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥");

	// 오행을 상생 순서(목 -> 화 -> 토 -> 금 -> 수 -> 목)로 적는다. 한 칸 뒤는 내가 생하는 오행, 두 칸 뒤는 내가 극하는 오행이다.
	private static final List<String> ELEMENTS_IN_GENERATING_ORDER = List.of("목", "화", "토", "금", "수");

	// 글자마다 오행. 천간은 둘씩, 지지는 寅卯(목) 巳午(화) 申酉(금) 亥子(수)와 辰戌丑未(토)
	private static final Map<String, String> ELEMENT_OF = Map.ofEntries(
		Map.entry("甲", "목"), Map.entry("乙", "목"), Map.entry("寅", "목"), Map.entry("卯", "목"),
		Map.entry("丙", "화"), Map.entry("丁", "화"), Map.entry("巳", "화"), Map.entry("午", "화"),
		Map.entry("戊", "토"), Map.entry("己", "토"), Map.entry("辰", "토"), Map.entry("戌", "토"),
		Map.entry("丑", "토"), Map.entry("未", "토"),
		Map.entry("庚", "금"), Map.entry("辛", "금"), Map.entry("申", "금"), Map.entry("酉", "금"),
		Map.entry("壬", "수"), Map.entry("癸", "수"), Map.entry("亥", "수"), Map.entry("子", "수"));

	// 십성을 가르는 음양. 지지는 지장간 정기의 음양으로 본다(子午는 음, 巳亥는 양).
	private static final List<String> YANG_LETTERS = List.of(
		"甲", "丙", "戊", "庚", "壬", "寅", "辰", "巳", "申", "戌", "亥");

	// 일간에서 본 오행 거리(0~4)마다의 십성. 음양이 같으면 앞, 다르면 뒤
	private static final List<String> SAME_POLARITY_TEN_STARS = List.of("비견", "식신", "편재", "편관", "편인");
	private static final List<String> OTHER_POLARITY_TEN_STARS = List.of("겁재", "상관", "정재", "정관", "정인");

	// 천간 한글 이름
	private static final List<String> STEM_KOREAN = List.of("갑", "을", "병", "정", "무", "기", "경", "신", "임", "계");

	// 일간마다 자시(子時)의 천간. 甲己일은 甲子시, 乙庚일은 丙子시, 丙辛일은 戊子시, 丁壬일은 庚子시, 戊癸일은 壬子시로 시작한다.
	private static final Map<String, String> JASI_STEM_OF = Map.of(
		"甲", "甲", "己", "甲",
		"乙", "丙", "庚", "丙",
		"丙", "戊", "辛", "戊",
		"丁", "庚", "壬", "庚",
		"戊", "壬", "癸", "壬");

	private final SajuDataService sajuDataService = new SajuDataService();

	@Test
	void shouldUseStandardHiddenStemForSingleBranch() {
		Map<String, Map<String, Object>> jijangan = sajuDataService.getJijangan();
		Map<String, Object> ja = jijangan.get("子");
		Map<String, Object> first = (Map<String, Object>) ja.get("first");

		assertEquals("癸", first.get("chinese"));
		assertEquals(30, first.get("rate"));
		assertNull(ja.get("second"));
		assertNull(ja.get("third"));
	}

	@Test
	void shouldUseStandardHiddenStemOrderForInBranch() {
		Map<String, Map<String, Object>> jijangan = sajuDataService.getJijangan();
		Map<String, Object> in = jijangan.get("寅");
		Map<String, Object> first = (Map<String, Object>) in.get("first");
		Map<String, Object> second = (Map<String, Object>) in.get("second");
		Map<String, Object> third = (Map<String, Object>) in.get("third");

		assertEquals("甲", first.get("chinese"));
		assertEquals("丙", second.get("chinese"));
		assertEquals("戊", third.get("chinese"));
	}

	/**
	 * 일간 10개 x 글자 22개의 십성 표를 규칙과 한 칸씩 대조한다. 기대값은 서버 표를 베끼지 않고 위의 오행·음양 표에서 만든다.
	 */
	@Nested
	@DisplayName("십성 표 220칸은")
	class TenStarTable {

		@ParameterizedTest(name = "[{index}] {0} 일간 + {1} → {2}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyDayStemAndLetterWithElement")
		@DisplayName("쉼표 뒤 오행이 글자 자체의 오행과 같다")
		void elementPartIsElementOfLetter(String dayStem, String letter, String expectedElement) {
			// when
			String tenStar = sajuDataService.getTenStar().get(dayStem).get(letter);

			// then
			assertThat(tenStar.split(",")[1]).isEqualTo(expectedElement);
		}

		@ParameterizedTest(name = "[{index}] {0} 일간 + {1} → {2}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyDayStemAndLetterWithTenStarName")
		@DisplayName("쉼표 앞 십성 이름이 일간과 글자의 오행 관계·음양으로 정한 이름과 같다")
		void namePartFollowsElementRelationAndPolarity(String dayStem, String letter, String expectedName) {
			// when
			String tenStar = sajuDataService.getTenStar().get(dayStem).get(letter);

			// then
			assertThat(tenStar.split(",")[0]).isEqualTo(expectedName);
		}
	}

	@Nested
	@DisplayName("시주 표는")
	class TimePillarTable {

		@ParameterizedTest(name = "[{index}] {0} 일간 {1}번 시 → {2}{3}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyDayStemAndHourByJasiRule")
		@DisplayName("일간 10개 x 시 12개 모두 자시 천간에서 한 칸씩 나아간 천간과 子부터 센 지지다")
		void followsJasiStemRule(String dayStem, int hourIndex, String expectedStem, String expectedBranch) {
			// when
			String[] timePillar = sajuDataService.getTimeJuData2().get(dayStem).get(String.valueOf(hourIndex));

			// then
			assertThat(timePillar).containsExactly(expectedStem, expectedBranch);
		}
	}

	@Nested
	@DisplayName("지장간 표는")
	class HiddenStemTable {

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyBranch")
		@DisplayName("12지지 모두 지장간 비율(rate)을 더하면 30이다")
		void ratesAddUpToThirty(String branch) {
			// when
			List<Map<String, Object>> hiddenStems = hiddenStemsOf(branch);

			// then
			assertThat(hiddenStems.stream().mapToInt(hidden -> (Integer) hidden.get("rate")).sum()).isEqualTo(30);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyBranch")
		@DisplayName("12지지 모두 첫 지장간(정기)의 오행이 지지 자체의 오행과 같다")
		void firstHiddenStemHasElementOfBranch(String branch) {
			// when
			List<Map<String, Object>> hiddenStems = hiddenStemsOf(branch);

			// then
			assertThat(hiddenStems.get(0).get("fiveCircle")).isEqualTo(ELEMENT_OF.get(branch));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyBranch")
		@DisplayName("12지지 모두 각 지장간의 한글 이름·오행·음양이 그 천간의 것과 같다")
		void hiddenStemAttributesMatchStem(String branch) {
			// when
			List<Map<String, Object>> hiddenStems = hiddenStemsOf(branch);

			// then
			assertThat(hiddenStems).isNotEmpty().allSatisfy(hidden -> {
				String stem = (String) hidden.get("chinese");
				assertThat(hidden.get("korean")).as("%s 의 한글", stem).isEqualTo(STEM_KOREAN.get(STEMS.indexOf(stem)));
				assertThat(hidden.get("fiveCircle")).as("%s 의 오행", stem).isEqualTo(ELEMENT_OF.get(stem));
				assertThat(hidden.get("minusPlus")).as("%s 의 음양", stem)
					.isEqualTo(YANG_LETTERS.contains(stem) ? "양" : "음");
			});
		}
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> hiddenStemsOf(String branch) {
		Map<String, Object> hidden = sajuDataService.getJijangan().get(branch);
		return Stream.of(hidden.get("first"), hidden.get("second"), hidden.get("third"))
			.filter(Objects::nonNull)
			.map(value -> (Map<String, Object>) value)
			.toList();
	}

	static Stream<String> everyBranch() {
		return BRANCHES.stream();
	}

	static Stream<Arguments> everyDayStemAndLetterWithElement() {
		return everyDayStemAndLetter().map(pair -> Arguments.of(pair[0], pair[1], ELEMENT_OF.get(pair[1])));
	}

	/**
	 * 일간 오행에서 글자 오행까지 상생 순서로 몇 칸인지(0 같음, 1 내가 생함, 2 내가 극함, 3 나를 극함, 4 나를 생함)와 음양이 같은지로
	 * 십성 이름을 정한다.
	 */
	static Stream<Arguments> everyDayStemAndLetterWithTenStarName() {
		return everyDayStemAndLetter().map(pair -> {
			int distance = Math.floorMod(ELEMENTS_IN_GENERATING_ORDER.indexOf(ELEMENT_OF.get(pair[1]))
				- ELEMENTS_IN_GENERATING_ORDER.indexOf(ELEMENT_OF.get(pair[0])), 5);
			boolean samePolarity = YANG_LETTERS.contains(pair[0]) == YANG_LETTERS.contains(pair[1]);
			String name = samePolarity ? SAME_POLARITY_TEN_STARS.get(distance) : OTHER_POLARITY_TEN_STARS.get(distance);
			return Arguments.of(pair[0], pair[1], name);
		});
	}

	static Stream<Arguments> everyDayStemAndHourByJasiRule() {
		return STEMS.stream().flatMap(dayStem -> IntStream.range(0, 12).mapToObj(hourIndex -> {
			int stemIndex = (STEMS.indexOf(JASI_STEM_OF.get(dayStem)) + hourIndex) % 10;
			return Arguments.of(dayStem, hourIndex, STEMS.get(stemIndex), BRANCHES.get(hourIndex));
		}));
	}

	private static Stream<String[]> everyDayStemAndLetter() {
		return STEMS.stream().flatMap(dayStem -> Stream.concat(STEMS.stream(), BRANCHES.stream())
			.map(letter -> new String[]{dayStem, letter}));
	}
}
