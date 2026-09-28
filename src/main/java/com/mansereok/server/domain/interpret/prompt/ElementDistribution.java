package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.calculator.FiveElement;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 천간·지지와 지장간까지 센 오행 점수와 십성 개수. 두 맵 모두 고칠 수 없어, 받는 쪽이 값을 바꿔 다른 소비자에게 번지지 않는다.
 *
 * @param elementScores 오행별 점수. 다섯 오행이 모두 들어 있고, 만들 때 EnumMap 으로 옮겨 담으므로 목·화·토·금·수 순서로 돈다.
 *                      프롬프트의 오행 줄 순서가 이 순서로 정해진다.
 * @param tenStarCounts 십성 이름별 개수. 한 번도 나오지 않은 십성은 들어 있지 않다. 도는 순서는 넘겨받은 맵의 순서 그대로다.
 */
record ElementDistribution(Map<FiveElement, Double> elementScores, Map<String, Integer> tenStarCounts) {

	ElementDistribution {
		elementScores = Collections.unmodifiableMap(new EnumMap<>(elementScores));
		tenStarCounts = Collections.unmodifiableMap(tenStarCounts);
	}
}
