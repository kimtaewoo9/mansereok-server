package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 사주 강약 판정의 경계와 응답 JSON 에 나가는 이름을 확인한다.
 */
@DisplayName("사주 강약 판정")
class StrengthTest {

	@ParameterizedTest(name = "[{index}] 나의 힘 비율 {0} → {1}")
	@DisplayName("나의 힘 비율이 0.58 이상이면 신강, 0.42 이하면 신약, 그 사이면 중화다")
	@CsvSource(textBlock = """
		# 나의 힘 비율, 강약
		1.0,      STRONG
		0.58,     STRONG
		0.579999, BALANCED
		0.5,      BALANCED
		0.420001, BALANCED
		0.42,     WEAK
		0.0,      WEAK
		""")
	void judgesByRatio(double ratio, Strength expected) {
		// when
		Strength strength = Strength.of(ratio);

		// then
		assertThat(strength).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] {0} → \"{1}\"")
	@DisplayName("용신 결과를 JSON 으로 내보내면 strength 에 enum 이름이 아니라 한글·한자 이름이 나간다")
	@CsvSource(textBlock = """
		# 강약, JSON 의 strength 값
		STRONG,   신강(身强)
		BALANCED, 중화(中和)
		WEAK,     신약(身弱)
		""")
	void writesLabelToJson(Strength strength, String label) throws Exception {
		// given
		ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
		YongsinResult result = new YongsinResult(strength, 5.0, 10.0, "수", "설명", "EOKBU_JOHU_V1",
			"억부 중심 + 조후 보정");

		// when
		String json = objectMapper.writeValueAsString(result);

		// then
		assertThat(objectMapper.readTree(json).get("strength").asText()).isEqualTo(label);
	}
}
