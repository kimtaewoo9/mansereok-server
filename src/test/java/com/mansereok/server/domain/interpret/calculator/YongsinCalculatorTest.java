package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.service.SajuDataService;
import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStem;
import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStems;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 용신 계산을 운영과 같은 모양의 사주로 확인한다.
 *
 * <p>운영의 만세력 계산은 지지마다 SajuDataService 의 지장간 표를 채워 넘긴다. 그래서 테스트 사주도 같은 표로 지장간을 채우고,
 * 천간·지지의 오행은 글자별 오행 표로 채운다. 기대값(강약, 나의 점수, 전체 점수, 용신, 설명)은 지금 코드가 내는 값을 그대로 적었다.
 * 점수는 소수 첫째 자리로 반올림해 돌려주므로 그 자리까지 고정한다. 가중치나 통근 계산이 바뀌면 점수가 달라져 실패한다.
 */
@DisplayName("용신 계산")
class YongsinCalculatorTest {

	// 글자마다 오행
	private static final Map<String, String> ELEMENT_OF = Map.ofEntries(
		Map.entry("甲", "목"), Map.entry("乙", "목"), Map.entry("寅", "목"), Map.entry("卯", "목"),
		Map.entry("丙", "화"), Map.entry("丁", "화"), Map.entry("巳", "화"), Map.entry("午", "화"),
		Map.entry("戊", "토"), Map.entry("己", "토"), Map.entry("辰", "토"), Map.entry("戌", "토"),
		Map.entry("丑", "토"), Map.entry("未", "토"),
		Map.entry("庚", "금"), Map.entry("辛", "금"), Map.entry("申", "금"), Map.entry("酉", "금"),
		Map.entry("壬", "수"), Map.entry("癸", "수"), Map.entry("亥", "수"), Map.entry("子", "수"));

	private final YongsinCalculator calculator = new YongsinCalculator();
	private final SajuDataService sajuDataService = new SajuDataService();

	@Nested
	@DisplayName("조후가 없는 달(寅卯辰申酉戌)이면")
	class WithoutClimateMonth {

		@ParameterizedTest(name = "[{index}] {0} {1} {2} {3} → {4} {5}/{6}, 용신 {7}")
		@DisplayName("신강이면 관살·식상, 신약이면 인성·비겁 중 점수가 낮은 쪽이, 중화면 가장 모자란 오행이 용신이다")
		@CsvSource(textBlock = """
			# 연주, 월주, 일주, 시주, 강약, 나의 점수, 전체 점수, 용신, 설명
			# 戊(토) 일간 신강: 관살 목(0.0)이 식상 금(1.8)보다 낮다
			丙子, 丁酉, 戊午, 戊午, 신강(身强), 7.0, 10.4, 목, '억부용신(신강 사주 제어) / 희신:금 / 행운색:청색, 녹색, 방향:동쪽'
			# 甲(목) 일간 신강: 식상 화(0.1)가 관살 금(1.78)보다 낮다
			庚戌, 己卯, 甲子, 壬申, 신강(身强), 7.8, 11.9, 화, '억부용신(신강 사주 제어) / 희신:금 / 행운색:적색, 분홍, 방향:남쪽'
			# 甲(목) 일간 신약: 인성 수(1.69)가 비겁 목(2.1)보다 낮다
			癸未, 庚申, 甲午, 乙丑, 신약(身弱), 3.8, 10.6, 수, '억부용신(신약 사주 보강) / 희신:목 / 행운색:검정, 남색, 방향:북쪽'
			# 庚(금) 일간 신약: 비겁 금(1.2)이 인성 토(1.79)보다 낮다
			甲子, 丁卯, 庚辰, 戊寅, 신약(身弱), 3.0, 9.4, 금, '억부용신(신약 사주 보강) / 희신:토 / 행운색:백색, 은색, 방향:서쪽'
			# 辛(금) 일간 중화: 다섯 오행 중 수(0.1)가 가장 모자라고, 희신은 인성 토
			丙辰, 庚寅, 辛卯, 丁酉, 중화(中和), 4.9, 9.8, 수, '중화용신(오행 균형) / 희신:토 / 행운색:검정, 남색, 방향:북쪽'
			""")
		void decidesByStrength(String year, String month, String day, String time, String strength,
			double myScore, double totalScore, String yongsin, String description) {
			// when
			YongsinResult result = calculator.analyzeYongsin(saju(year, month, day, time));

			// then
			assertThat(result)
				.extracting(YongsinCalculatorTest::strengthLabel, YongsinResult::getMyScore, YongsinResult::getTotalScore,
					YongsinResult::getYongsin, YongsinResult::getDescription)
				.containsExactly(strength, myScore, totalScore, yongsin, description);
		}
	}

	@Nested
	@DisplayName("조후가 있는 달이면")
	class WithClimateMonth {

		@ParameterizedTest(name = "[{index}] {0} {1} {2} {3} → {4} {5}/{6}, 용신 {7}")
		@DisplayName("강약과 상관없이 여름(巳午未)은 수, 겨울(亥子丑)은 화가 용신이고 억부로 고른 오행은 희신이 된다")
		@CsvSource(textBlock = """
			# 연주, 월주, 일주, 시주(비면 시간 모름), 강약, 나의 점수, 전체 점수, 용신, 설명
			己丑, 己巳, 己巳, 甲子, 신강(身强), 8.0, 10.8, 수, '조후+억부용신(한난 조절 후 강한 기운 제어) / 희신:목 / 행운색:검정, 남색, 방향:북쪽'
			戊辰, 癸亥, 庚子, 丁丑, 신약(身弱), 3.5, 10.0, 화, '조후+억부용신(한난 조절 후 약한 기운 보강) / 희신:금 / 행운색:적색, 분홍, 방향:남쪽'
			甲寅, 辛未, 甲子,     , 중화(中和), 4.5,  8.6, 수, '조후용신(한난 조절 우선) / 희신:화 / 행운색:검정, 남색, 방향:북쪽'
			丙午, 庚子, 壬寅,     , 신강(身强), 6.9, 10.2, 화, '조후+억부용신(한난 조절 후 강한 기운 제어) / 희신:토 / 행운색:적색, 분홍, 방향:남쪽'
			""")
		void climateElementComesFirst(String year, String month, String day, String time, String strength,
			double myScore, double totalScore, String yongsin, String description) {
			// when
			YongsinResult result = calculator.analyzeYongsin(saju(year, month, day, time));

			// then
			assertThat(result)
				.extracting(YongsinCalculatorTest::strengthLabel, YongsinResult::getMyScore, YongsinResult::getTotalScore,
					YongsinResult::getYongsin, YongsinResult::getDescription)
				.containsExactly(strength, myScore, totalScore, yongsin, description);
		}
	}

	/**
	 * 나의 힘 비율(나의 점수 / 전체 점수)이 경계 바로 위·아래인 사주로 강약 판정을 확인한다. 비율은 반올림하지 않은 값이다.
	 * 경계와 같은 두 줄은 부동소수 계산 결과가 정확히 0.58, 0.42 로 나오는 사주를 골랐다.
	 */
	@ParameterizedTest(name = "[{index}] {0} {1} {2} {3} (비율 {4}) → {5}")
	@DisplayName("나의 힘 비율이 0.58 이상이면 신강, 0.42 이하면 신약, 그 사이면 중화다")
	@CsvSource(textBlock = """
		# 연주, 월주, 일주, 시주, 나의 힘 비율, 강약
		甲子, 己巳, 壬子, 庚戌, 0.58,     신강(身强)
		丙申, 庚寅, 壬子, 壬寅, 0.579988, 중화(中和)
		庚午, 丙戌, 丁亥, 庚戌, 0.420006, 중화(中和)
		丁丑, 乙巳, 甲子, 己巳, 0.42,     신약(身弱)
		""")
	void judgesStrengthAtRatioBoundaries(String year, String month, String day, String time, String ratio,
		String strength) {
		// when
		YongsinResult result = calculator.analyzeYongsin(saju(year, month, day, time));

		// then
		assertThat(result.getStrength().label()).isEqualTo(strength);
	}

	@Test
	@DisplayName("월지가 한자 없이 한글 '신'으로만 오면 申월로 보고 한자로 받은 것과 같은 결과를 낸다")
	void readsKoreanSinAsMonthBranchShen() {
		// given: 조후가 없는 달 표의 癸未 庚申 甲午 乙丑 과 같은 사주에서 월지 한자만 뺀다
		SajuInfo saju = saju("癸未", "庚申", "甲午", "乙丑");
		saju.getMonthGround().setChinese(null);
		saju.getMonthGround().setKorean("신");

		// when
		YongsinResult result = calculator.analyzeYongsin(saju);

		// then: 申월은 甲(목) 일간에게 힘을 빼는 달이라, 월지를 못 읽으면 전체 점수가 10.6 이 아니라 9.4 가 된다
		assertThat(result)
			.extracting(YongsinCalculatorTest::strengthLabel, YongsinResult::getMyScore, YongsinResult::getTotalScore,
				YongsinResult::getYongsin, YongsinResult::getDescription)
			.containsExactly("신약(身弱)", 3.8, 10.6, "수",
				"억부용신(신약 사주 보강) / 희신:목 / 행운색:검정, 남색, 방향:북쪽");
	}

	@Test
	@DisplayName("적용한 규칙 코드와 이름을 함께 돌려준다")
	void returnsAppliedRuleset() {
		// when
		YongsinResult result = calculator.analyzeYongsin(saju("丙子", "丁酉", "戊午", "戊午"));

		// then
		assertThat(result.getAppliedRuleCode()).isEqualTo("EOKBU_JOHU_V1");
		assertThat(result.getAppliedRuleName()).isEqualTo("억부 중심 + 조후 보정");
	}

	/**
	 * 표에는 응답 JSON 과 프롬프트에 나가는 강약 이름("신강(身强)")을 그대로 적는다.
	 */
	private static String strengthLabel(YongsinResult result) {
		return result.getStrength().label();
	}

	/**
	 * 간지 두 글자("甲子")로 네 기둥을 받아 운영과 같은 모양의 사주를 만든다. 시주를 모르면 null 을 넘긴다.
	 */
	private SajuInfo saju(String year, String month, String day, String time) {
		SajuInfo.SajuInfoBuilder builder = SajuInfo.builder()
			.yearSky(sky(year)).yearGround(ground(year))
			.monthSky(sky(month)).monthGround(ground(month))
			.daySky(sky(day)).dayGround(ground(day));
		if (time != null) {
			builder.timeSky(sky(time)).timeGround(ground(time));
		}
		return builder.build();
	}

	private PillarElement sky(String pillar) {
		String stem = pillar.substring(0, 1);
		return PillarElement.builder()
			.chinese(stem)
			.fiveCircle(ELEMENT_OF.get(stem))
			.build();
	}

	private PillarElement ground(String pillar) {
		String branch = pillar.substring(1);
		return PillarElement.builder()
			.chinese(branch)
			.fiveCircle(ELEMENT_OF.get(branch))
			.jijanggan(hiddenStemsOf(branch))
			.build();
	}

	/**
	 * 운영의 만세력 계산처럼 SajuDataService 의 지장간 표를 JijangganInfo 로 옮긴다.
	 */
	private JijangganInfo hiddenStemsOf(String branch) {
		HiddenStems hidden = sajuDataService.hiddenStemsOf(branch);
		return JijangganInfo.builder()
			.first(hiddenStem(hidden.first()))
			.second(hiddenStem(hidden.second()))
			.third(hiddenStem(hidden.third()))
			.build();
	}

	private JijangganElement hiddenStem(HiddenStem hidden) {
		if (hidden == null) {
			return null;
		}
		return JijangganElement.builder()
			.chinese(hidden.chinese())
			.fiveCircle(hidden.fiveCircle())
			.rate(hidden.rate())
			.build();
	}
}
