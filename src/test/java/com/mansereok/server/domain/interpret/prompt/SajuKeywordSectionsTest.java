package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.calculator.Strength;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 해석 키워드 블록이 계산기의 판정을 그대로 따르고, 여러 줄을 늘 같은 순서로 적는지 확인한다.
 */
@DisplayName("해석 키워드 블록")
class SajuKeywordSectionsTest {

	/**
	 * 강약 지침은 계산기가 내린 판정(Strength)으로만 고른다. 표의 점수는 계산기가 그 판정을 내린 사주의 점수이고, 나의 힘 비율은
	 * 0.40·0.45·0.54·0.60 이다. 예전에는 프롬프트가 점수를 50% 기준으로 다시 판정해, 중화(0.42~0.58) 사주에도 신강·신약 지침이 붙었다.
	 */
	@Nested
	@DisplayName("사주 강약 지침은")
	class StrengthGuidance {

		@ParameterizedTest(name = "[{index}] {0} (내 세력 {1} / 전체 {2})")
		@DisplayName("계산기가 신강이나 신약으로 판정하면 그쪽 지침 한 줄만 붙인다")
		@CsvSource(quoteCharacter = '"', textBlock = """
			# 계산기 판정, 나의 점수, 전체 점수, 붙는 지침
			WEAK,   40.0, 100.0, "  -> (지침) 주변 환경에 잘 휩쓸립니다. '자기 주관'을 가지라고 조언하세요."
			STRONG, 60.0, 100.0, "  -> (지침) 주관이 뚜렷하고 고집이 셉니다. '독단적인 행동'을 주의하라고 조언하세요."
			""")
		void addsGuidanceOfJudgedSide(Strength strength, double myScore, double totalScore, String guidance) {
			// given
			ManseryeokCalculationResponse response = responseWith(yongsin(strength, myScore, totalScore));

			// when
			String prompt = appendKeywords(response);

			// then
			assertThat(guidanceLines(prompt)).containsExactly(guidance);
		}

		@ParameterizedTest(name = "[{index}] 중화 (내 세력 {0} / 전체 {1})")
		@DisplayName("계산기가 중화로 판정하면 점수가 한쪽으로 조금 기울어도 신강·신약 지침을 붙이지 않고 중화라고만 알린다")
		@CsvSource(textBlock = """
			# 나의 점수, 전체 점수, 강약 줄
			45.0, 100.0, '- 사주 강약 판정: 중화(中和) (내 세력 45.0 vs 남의 세력 55.0)'
			54.0, 100.0, '- 사주 강약 판정: 중화(中和) (내 세력 54.0 vs 남의 세력 46.0)'
			""")
		void addsNoGuidanceWhenBalanced(double myScore, double totalScore, String strengthLine) {
			// given
			ManseryeokCalculationResponse response =
				responseWith(yongsin(Strength.BALANCED, myScore, totalScore));

			// when
			String prompt = appendKeywords(response);

			// then
			assertThat(prompt.lines()).contains(strengthLine);
			assertThat(guidanceLines(prompt)).isEmpty();
		}
	}

	/**
	 * 예전에는 [결핍] 줄을 Map.of 를 돌며 적어서, JVM 을 띄울 때마다 여러 줄의 순서가 바뀔 수 있었다.
	 */
	@Test
	@DisplayName("부족한 오행이 여럿이면 [결핍] 줄을 목·화·토·금·수 순서로 적는다")
	void writesLackLinesInElementOrder() {
		// given: 천간·지지가 토·금 뿐이라 목·화·수 가 0점이다
		SajuInfo saju = SajuInfo.builder()
			.yearSky(element("토")).yearGround(element("금"))
			.monthSky(element("토")).monthGround(element("금"))
			.daySky(element("토")).dayGround(element("금"))
			.build();
		ManseryeokCalculationResponse response = ManseryeokCalculationResponse.builder().saju(saju).build();

		// when
		String prompt = appendKeywords(response);

		// then
		assertThat(prompt.lines().filter(line -> line.startsWith("- [결핍]")).toList()).containsExactly(
			"- [결핍] 목 부족: 성장/확장 동력이 약해 새 일을 벌이는 결단이 늦음. 보완 방향을 조언에 반영하세요.",
			"- [결핍] 화 부족: 표현과 열정의 발산이 약해 존재감이 묻히기 쉬움. 보완 방향을 조언에 반영하세요.",
			"- [결핍] 수 부족: 유연한 사고와 휴식이 부족해 번아웃에 취약함. 보완 방향을 조언에 반영하세요.");
	}

	@Nested
	@DisplayName("기둥별 신살 줄은")
	class SinsalByPillar {

		@Test
		@DisplayName("신살 목록에 모르는 기둥 이름이 있어도 예외 없이 그 키만 건너뛰고 나머지를 적는다")
		void skipsUnknownPillarLabel() {
			// given: 계산기가 쓰지 않는 "시지" 키가 섞여 있다
			Map<String, List<String>> sinsalInfo = new LinkedHashMap<>();
			sinsalInfo.put("시지", List.of("양인살"));
			sinsalInfo.put("년주", List.of("역마살"));
			SajuInfo saju = SajuInfo.builder().sinsalInfo(sinsalInfo).build();

			// when
			String prompt = appendSinsalFull(saju);

			// then
			assertThat(prompt).isEqualTo("년주: 역마살\n\n");
		}

		@Test
		@DisplayName("키를 넣은 순서와 상관없이 년주·월주·일주·시주 순서로 적고, 괴강살·백호대살·공망은 일주 줄 끝에 붙인다")
		void writesInPillarOrderWithDayExtras() {
			// given: 시주부터 거꾸로 넣는다
			Map<String, List<String>> sinsalInfo = new LinkedHashMap<>();
			sinsalInfo.put("시주", List.of("양인살"));
			sinsalInfo.put("일주", List.of("천을귀인"));
			sinsalInfo.put("월주", List.of("도화살", "화개살"));
			sinsalInfo.put("년주", List.of("역마살"));
			SajuInfo saju = SajuInfo.builder()
				.sinsalInfo(sinsalInfo)
				.hasGoegang(Boolean.TRUE)
				.hasBaekho(Boolean.TRUE)
				.gongmang(List.of("寅", "卯"))
				.build();

			// when
			String prompt = appendSinsalFull(saju);

			// then
			assertThat(prompt.lines()).containsExactly(
				"년주: 역마살",
				"월주: 도화살, 화개살",
				"일주: 천을귀인, 괴강살, 백호대살, 공망:寅,卯",
				"시주: 양인살",
				"");
		}

		private String appendSinsalFull(SajuInfo saju) {
			StringBuilder prompt = new StringBuilder();
			SajuKeywordSections.appendSinsalFull(prompt, saju);
			return prompt.toString();
		}
	}

	private static PillarElement element(String fiveCircle) {
		return PillarElement.builder().fiveCircle(fiveCircle).build();
	}

	private static String appendKeywords(ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		SajuKeywordSections.appendKeywords(prompt, response);
		return prompt.toString();
	}

	private static List<String> guidanceLines(String prompt) {
		return prompt.lines().filter(line -> line.contains("(지침)")).toList();
	}

	private static ManseryeokCalculationResponse responseWith(YongsinResult yongsin) {
		SajuInfo saju = SajuInfo.builder().yongsinInfo(yongsin).build();
		return ManseryeokCalculationResponse.builder().saju(saju).build();
	}

	private static YongsinResult yongsin(Strength strength, double myScore, double totalScore) {
		return new YongsinResult(strength, myScore, totalScore, "수", "설명", "EOKBU_JOHU_V1",
			"억부 중심 + 조후 보정");
	}
}
