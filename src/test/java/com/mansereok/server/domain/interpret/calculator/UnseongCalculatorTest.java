package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 12운성 표 120칸(일간 10개 x 지지 12개)을 규칙과 한 칸씩 대조한다.
 *
 * <p>규칙은 "일간마다 장생이 되는 지지가 하나 있고, 거기서부터 양간은 12지지를 순서대로(순행), 음간은 거꾸로(역행) 돌며
 * 장생·목욕·관대·건록·제왕·쇠·병·사·묘·절·태·양이 차례로 붙는다" 이다. 기대값은 서버 표를 베끼지 않고 아래 장생 지지 표와
 * 방향에서 만든다. 표의 한 칸이라도 오타가 나면 그 칸 이름으로 실패한다.
 */
@DisplayName("12운성 계산")
class UnseongCalculatorTest {

	private static final List<String> BRANCHES = List.of(
		"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥");

	private static final List<String> STAGES_FROM_JANGSAENG = List.of(
		"장생", "목욕", "관대", "건록", "제왕", "쇠", "병", "사", "묘", "절", "태", "양");

	private static final int FORWARD = 1;
	private static final int BACKWARD = -1;

	// 일간, 장생 지지, 도는 방향(양간은 순행, 음간은 역행)
	private static final Object[][] JANGSAENG_RULE = {
		{"甲", "亥", FORWARD},
		{"乙", "午", BACKWARD},
		{"丙", "寅", FORWARD},
		{"丁", "酉", BACKWARD},
		{"戊", "寅", FORWARD},
		{"己", "酉", BACKWARD},
		{"庚", "巳", FORWARD},
		{"辛", "子", BACKWARD},
		{"壬", "申", FORWARD},
		{"癸", "卯", BACKWARD},
	};

	private final UnseongCalculator calculator = new UnseongCalculator();

	@ParameterizedTest(name = "[{index}] {0} 일간 + {1} 지지 → {2}")
	@MethodSource("everyStemAndBranchByRule")
	@DisplayName("120칸 모두 장생 지지에서 양간은 순행, 음간은 역행으로 센 운성과 같다")
	void matchesJangsaengRule(String stem, String branch, String expectedStage) {
		// when
		String stage = calculator.calculate(stem, branch);

		// then
		assertThat(stage).isEqualTo(expectedStage);
	}

	/**
	 * 위 규칙 표(장생 지지와 방향)가 틀리면 서버 표와 함께 틀려도 모를 수 있어서, 따로 아는 규칙 하나를 값 그대로 적어 맞춰 본다.
	 */
	@ParameterizedTest(name = "[{index}] {0} 일간의 건록은 {1}")
	@DisplayName("건록은 일간과 오행·음양이 같은 지지다")
	@CsvSource(textBlock = """
		甲, 寅
		乙, 卯
		丙, 巳
		丁, 午
		戊, 巳
		己, 午
		庚, 申
		辛, 酉
		壬, 亥
		癸, 子
		""")
	void geonrokIsBranchOfSameElementAndPolarity(String stem, String branch) {
		// when
		String stage = calculator.calculate(stem, branch);

		// then
		assertThat(stage).isEqualTo("건록");
	}

	/**
	 * 장생 지지에서 방향대로 한 칸씩 가며 12운성을 차례로 붙여 120칸의 기대값을 만든다.
	 */
	static Stream<Arguments> everyStemAndBranchByRule() {
		List<Arguments> cases = new ArrayList<>();
		for (Object[] rule : JANGSAENG_RULE) {
			String stem = (String) rule[0];
			int jangsaengIndex = BRANCHES.indexOf((String) rule[1]);
			int direction = (int) rule[2];
			for (int step = 0; step < STAGES_FROM_JANGSAENG.size(); step++) {
				String branch = BRANCHES.get(Math.floorMod(jangsaengIndex + direction * step, 12));
				cases.add(Arguments.of(stem, branch, STAGES_FROM_JANGSAENG.get(step)));
			}
		}
		return cases.stream();
	}
}
