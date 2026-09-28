package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStem;
import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStems;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("만세력 기초 데이터 표")
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
	@DisplayName("지장간이 하나뿐인 子 를 찾으면 정기 癸 가 비율 30 을 모두 갖고 나머지 칸은 비어 있다")
	void shouldUseStandardHiddenStemForSingleBranch() {
		// when
		HiddenStems ja = sajuDataService.hiddenStemsOf("子");

		// then
		assertThat(ja.first()).extracting(HiddenStem::chinese, HiddenStem::rate).containsExactly("癸", 30);
		assertThat(ja.second()).isNull();
		assertThat(ja.third()).isNull();
	}

	@Test
	@DisplayName("寅 을 찾으면 지장간을 정기 甲, 중기 丙, 여기 戊 순서로 돌려준다")
	void shouldUseStandardHiddenStemOrderForInBranch() {
		// when
		HiddenStems in = sajuDataService.hiddenStemsOf("寅");

		// then
		assertThat(List.of(in.first(), in.second(), in.third()))
			.extracting(HiddenStem::chinese)
			.containsExactly("甲", "丙", "戊");
	}

	@Nested
	@DisplayName("조회표는")
	class LookupTables {

		@Test
		@DisplayName("같은 지지의 지장간을 두 번 찾으면 서비스 인스턴스가 달라도 같은 객체를 돌려준다(호출마다 표를 새로 만들지 않는다)")
		void returnsSameHiddenStemsInstanceEveryTime() {
			// given
			HiddenStems foundByAnotherInstance = new SajuDataService().hiddenStemsOf("寅");

			// when
			HiddenStems found = sajuDataService.hiddenStemsOf("寅");

			// then
			assertThat(found).isSameAs(foundByAnotherInstance);
		}

		@Test
		@DisplayName("같은 일간·시의 시주를 두 번 찾으면 서비스 인스턴스가 달라도 같은 목록을 돌려준다(호출마다 표를 새로 만들지 않는다)")
		void returnsSameTimePillarInstanceEveryTime() {
			// given
			List<String> foundByAnotherInstance = new SajuDataService().timePillarOf("甲", 0);

			// when
			List<String> found = sajuDataService.timePillarOf("甲", 0);

			// then
			assertThat(found).isSameAs(foundByAnotherInstance);
		}

		@Test
		@DisplayName("돌려받은 시주 목록을 고치려 하면 UnsupportedOperationException 을 던지고 표는 그대로다")
		void timePillarCannotBeModified() {
			// given
			List<String> timePillar = sajuDataService.timePillarOf("甲", 0);

			// when & then
			assertThatThrownBy(() -> timePillar.set(0, "乙")).isInstanceOf(UnsupportedOperationException.class);
			assertThat(sajuDataService.timePillarOf("甲", 0)).containsExactly("甲", "子");
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#lookupsWithNullOrUnknownKey")
		@DisplayName("null 이나 표에 없는 글자로 찾으면 NullPointerException 없이 null 을 돌려준다")
		void returnsNullForNullOrUnknownKey(Supplier<Object> lookup) {
			// when
			Object found = lookup.get();

			// then
			assertThat(found).isNull();
		}

		@Test
		@DisplayName("정기(first)가 비어 있는 지장간은 만들 수 없다")
		void hiddenStemsRequireFirst() {
			// given
			HiddenStem second = new HiddenStem("癸", "계", "수", "음", 9);

			// when & then
			assertThatThrownBy(() -> new HiddenStems(null, second, null))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("지장간 정기(first)는 비어 있을 수 없습니다");
		}
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
			String tenStar = sajuDataService.tenStarOf(dayStem, letter);

			// then
			assertThat(tenStar.split(",")[1]).isEqualTo(expectedElement);
		}

		@ParameterizedTest(name = "[{index}] {0} 일간 + {1} → {2}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyDayStemAndLetterWithTenStarName")
		@DisplayName("쉼표 앞 십성 이름이 일간과 글자의 오행 관계·음양으로 정한 이름과 같다")
		void namePartFollowsElementRelationAndPolarity(String dayStem, String letter, String expectedName) {
			// when
			String tenStar = sajuDataService.tenStarOf(dayStem, letter);

			// then
			assertThat(tenStar.split(",")[0]).isEqualTo(expectedName);
		}
	}

	/**
	 * 기둥에 보여 주는 음양과 한글 이름을 22글자 모두 값 그대로 적는다. 지지의 표시 음양은 子부터 양·음을 번갈아 매긴 값이라, 십성을
	 * 가르는 음양(子午 음, 巳亥 양)과 네 글자가 다르다.
	 */
	@Nested
	@DisplayName("음양·한글 이름 표는")
	class YinYangAndKoreanTable {

		@ParameterizedTest(name = "[{index}] {0} → {1}, {2}")
		@CsvSource(textBlock = """
			# 천간
			甲, 갑, 양
			乙, 을, 음
			丙, 병, 양
			丁, 정, 음
			戊, 무, 양
			己, 기, 음
			庚, 경, 양
			辛, 신, 음
			壬, 임, 양
			癸, 계, 음
			# 지지
			子, 자, 양
			丑, 축, 음
			寅, 인, 양
			卯, 묘, 음
			辰, 진, 양
			巳, 사, 음
			午, 오, 양
			未, 미, 음
			申, 신, 양
			酉, 유, 음
			戌, 술, 양
			亥, 해, 음
			""")
		@DisplayName("천간 10글자와 지지 12글자 모두 적힌 한글 이름과 음양을 돌려준다")
		void returnsKoreanAndYinYangOfEveryLetter(String hanja, String expectedKorean, String expectedYinYang) {
			// when
			String korean = sajuDataService.koreanOf(hanja);
			String yinYang = sajuDataService.yinYangOf(hanja);

			// then
			assertThat(korean).as("%s 의 한글 이름", hanja).isEqualTo(expectedKorean);
			assertThat(yinYang).as("%s 의 음양", hanja).isEqualTo(expectedYinYang);
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
			List<String> timePillar = sajuDataService.timePillarOf(dayStem, hourIndex);

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
			List<HiddenStem> hiddenStems = hiddenStemsOf(branch);

			// then
			assertThat(hiddenStems.stream().mapToInt(HiddenStem::rate).sum()).isEqualTo(30);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyBranch")
		@DisplayName("12지지 모두 첫 지장간(정기)이 있고 그 오행이 지지 자체의 오행과 같다")
		void firstHiddenStemHasElementOfBranch(String branch) {
			// when
			HiddenStem first = sajuDataService.hiddenStemsOf(branch).first();

			// then
			assertThat(first).isNotNull();
			assertThat(first.fiveCircle()).isEqualTo(ELEMENT_OF.get(branch));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.SajuDataServiceTest#everyBranch")
		@DisplayName("12지지 모두 각 지장간의 한글 이름·오행·음양이 그 천간의 것과 같다")
		void hiddenStemAttributesMatchStem(String branch) {
			// when
			List<HiddenStem> hiddenStems = hiddenStemsOf(branch);

			// then
			assertThat(hiddenStems).isNotEmpty().allSatisfy(hidden -> {
				String stem = hidden.chinese();
				assertThat(hidden.korean()).as("%s 의 한글", stem).isEqualTo(STEM_KOREAN.get(STEMS.indexOf(stem)));
				assertThat(hidden.fiveCircle()).as("%s 의 오행", stem).isEqualTo(ELEMENT_OF.get(stem));
				assertThat(hidden.minusPlus()).as("%s 의 음양", stem)
					.isEqualTo(YANG_LETTERS.contains(stem) ? "양" : "음");
			});
		}
	}

	private List<HiddenStem> hiddenStemsOf(String branch) {
		HiddenStems hidden = sajuDataService.hiddenStemsOf(branch);
		return Stream.of(hidden.first(), hidden.second(), hidden.third())
			.filter(Objects::nonNull)
			.toList();
	}

	static Stream<Named<Supplier<Object>>> lookupsWithNullOrUnknownKey() {
		SajuDataService service = new SajuDataService();
		return Stream.of(
			named("yinYangOf(null)", () -> service.yinYangOf(null)),
			named("yinYangOf(\"X\")", () -> service.yinYangOf("X")),
			named("koreanOf(null)", () -> service.koreanOf(null)),
			named("koreanOf(\"X\")", () -> service.koreanOf("X")),
			named("tenStarOf(null, \"甲\")", () -> service.tenStarOf(null, "甲")),
			named("tenStarOf(\"甲\", null)", () -> service.tenStarOf("甲", null)),
			named("tenStarOf(\"X\", \"甲\")", () -> service.tenStarOf("X", "甲")),
			named("tenStarOf(\"甲\", \"X\")", () -> service.tenStarOf("甲", "X")),
			named("hiddenStemsOf(null)", () -> service.hiddenStemsOf(null)),
			named("hiddenStemsOf(\"甲\")", () -> service.hiddenStemsOf("甲")),
			named("timePillarOf(null, 0)", () -> service.timePillarOf(null, 0)),
			named("timePillarOf(\"X\", 0)", () -> service.timePillarOf("X", 0)),
			named("timePillarOf(\"甲\", 12)", () -> service.timePillarOf("甲", 12)));
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
