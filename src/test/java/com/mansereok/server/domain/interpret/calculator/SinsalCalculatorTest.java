package com.mansereok.server.domain.interpret.calculator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SinsalCalculatorTest {

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
}
