package com.mansereok.server.domain.interpret.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import org.junit.jupiter.api.Test;

class YongsinCalculatorTest {

	private final YongsinCalculator calculator = new YongsinCalculator();

	@Test
	void shouldPrioritizeWaterForSummerMonth() {
		SajuInfo saju = SajuInfo.builder()
			.yearSky(pillar("癸", "수"))
			.yearGround(pillar("亥", "수"))
			.monthSky(pillar("丁", "화"))
			.monthGround(pillar("午", "화"))
			.daySky(pillar("甲", "목"))
			.dayGround(pillar("寅", "목"))
			.timeSky(pillar("丙", "화"))
			.timeGround(pillar("辰", "토"))
			.build();

		YongsinResult result = calculator.analyzeYongsin(saju);

		assertNotNull(result);
		assertEquals("수", result.getYongsin());
		assertTrue(result.getDescription().contains("조후"));
		assertEquals("EOKBU_JOHU_V1", result.getAppliedRuleCode());
	}

	@Test
	void shouldPrioritizeFireForWinterMonth() {
		SajuInfo saju = SajuInfo.builder()
			.yearSky(pillar("辛", "금"))
			.yearGround(pillar("酉", "금"))
			.monthSky(pillar("壬", "수"))
			.monthGround(pillar("子", "수"))
			.daySky(pillar("庚", "금"))
			.dayGround(pillar("申", "금"))
			.timeSky(pillar("癸", "수"))
			.timeGround(pillar("亥", "수"))
			.build();

		YongsinResult result = calculator.analyzeYongsin(saju);

		assertNotNull(result);
		assertEquals("화", result.getYongsin());
		assertTrue(result.getDescription().contains("조후"));
		assertEquals("EOKBU_JOHU_V1", result.getAppliedRuleCode());
	}

	@Test
	void shouldReturnStrengthAndScores() {
		SajuInfo saju = SajuInfo.builder()
			.yearSky(pillar("甲", "목"))
			.yearGround(pillar("寅", "목"))
			.monthSky(pillar("乙", "목"))
			.monthGround(pillar("卯", "목"))
			.daySky(pillar("丙", "화"))
			.dayGround(pillar("午", "화"))
			.timeSky(pillar("戊", "토"))
			.timeGround(pillar("辰", "토"))
			.build();

		YongsinResult result = calculator.analyzeYongsin(saju);

		assertNotNull(result);
		assertNotNull(result.getStrength());
		assertTrue(result.getTotalScore() > 0);
		assertNotNull(result.getYongsin());
		assertEquals("EOKBU_JOHU_V1", result.getAppliedRuleCode());
		assertEquals("억부 중심 + 조후 보정", result.getAppliedRuleName());
	}

	private PillarElement pillar(String chinese, String fiveCircle) {
		return PillarElement.builder()
			.chinese(chinese)
			.fiveCircle(fiveCircle)
			.build();
	}
}
