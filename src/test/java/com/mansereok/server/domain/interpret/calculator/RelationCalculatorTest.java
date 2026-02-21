package com.mansereok.server.domain.interpret.calculator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelationCalculatorTest {

	private final RelationCalculator calculator = new RelationCalculator();

	@Test
	void shouldIncludeHyeongForJamoPair() {
		List<String> relations = calculator.analyzeRelation("子", "卯");
		assertTrue(relations.contains("형"));
	}

	@Test
	void shouldIncludePaForJayuPair() {
		List<String> relations = calculator.analyzeRelation("子", "酉");
		assertTrue(relations.contains("파"));
	}

	@Test
	void shouldIncludeHaeForMyojinPair() {
		List<String> relations = calculator.analyzeRelation("卯", "辰");
		assertTrue(relations.contains("해"));
	}

	@Test
	void shouldKeepExistingRelationTypes() {
		List<String> relations = calculator.analyzeRelation("子", "午");
		assertTrue(relations.contains("충"));
	}
}
