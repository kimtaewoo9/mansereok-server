package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.mansereok.server.domain.interpret.calculator.FiveElement;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트의 오행 줄 순서를 정하는 점수 순서(목·화·토·금·수)와, 받은 쪽이 점수·개수를 바꾸지 못하는 것을 확인한다.
 */
@DisplayName("오행 점수와 십성 개수 묶음")
class ElementDistributionTest {

	@Test
	@DisplayName("오행 점수를 수·금·토·화·목 순서의 맵으로 받아도 목·화·토·금·수 순서로 돌려준다")
	void keepsWoodFireEarthMetalWaterOrder() {
		// given
		Map<FiveElement, Double> scores = new LinkedHashMap<>();
		scores.put(FiveElement.WATER, 5.0);
		scores.put(FiveElement.METAL, 4.0);
		scores.put(FiveElement.EARTH, 3.0);
		scores.put(FiveElement.FIRE, 2.0);
		scores.put(FiveElement.WOOD, 1.0);

		// when
		ElementDistribution distribution = new ElementDistribution(scores, new HashMap<>());

		// then
		assertThat(distribution.elementScores()).containsExactly(
			entry(FiveElement.WOOD, 1.0),
			entry(FiveElement.FIRE, 2.0),
			entry(FiveElement.EARTH, 3.0),
			entry(FiveElement.METAL, 4.0),
			entry(FiveElement.WATER, 5.0));
	}

	@Test
	@DisplayName("받은 오행 점수와 십성 개수를 바꾸려 하면 UnsupportedOperationException 을 던진다")
	void rejectsChangesToScoresAndCounts() {
		// given
		ElementDistribution distribution = new ElementDistribution(
			new EnumMap<>(Map.of(FiveElement.WOOD, 1.0)), new HashMap<>(Map.of("비견", 1)));

		// when & then
		assertThatThrownBy(() -> distribution.elementScores().put(FiveElement.WOOD, 9.0))
			.isExactlyInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> distribution.tenStarCounts().put("비견", 9))
			.isExactlyInstanceOf(UnsupportedOperationException.class);
	}
}
