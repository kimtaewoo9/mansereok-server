package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Gpt5Request - 시스템 지시와 사용자 입력 분리 직렬화")
class Gpt5RequestTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("instructions 를 넣으면 최상위에 instructions 가 나오고 input 에는 사용자 프롬프트만 담긴다")
	void shouldSerializeInstructionsAtTopLevel() throws Exception {
		Gpt5Request request = Gpt5Request.withSystemInstruction(
			"gpt-5", "너는 사주명리학자다", "[사용자 입력]\n이름: 김태우", 1024, "high", "medium", null);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

		assertThat(json.has("instructions")).isTrue();
		assertThat(json.get("instructions").asText()).isEqualTo("너는 사주명리학자다");
		assertThat(json.get("input").asText()).isEqualTo("[사용자 입력]\n이름: 김태우");
		assertThat(json.get("input").asText()).doesNotContain("너는 사주명리학자다");
		assertThat(json.get("model").asText()).isEqualTo("gpt-5");
		assertThat(json.get("max_output_tokens").asInt()).isEqualTo(1024);
	}

	@Test
	@DisplayName("instructions 가 null 이면 직렬화에서 키 자체가 빠진다")
	void shouldOmitNullInstructions() throws Exception {
		Gpt5Request request = new Gpt5Request(
			"gpt-5", "사용자 프롬프트", 1024, "high", "medium");

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

		assertThat(json.has("instructions")).isFalse();
		assertThat(json.get("input").asText()).isEqualTo("사용자 프롬프트");
	}

	@Test
	@DisplayName("instructions 를 명시적으로 null 로 준 팩터리도 키를 만들지 않는다")
	void shouldOmitExplicitNullInstructions() throws Exception {
		Gpt5Request request = Gpt5Request.withSystemInstruction(
			"gpt-5", null, "사용자 프롬프트", 1024, "high", "medium", null);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

		assertThat(json.has("instructions")).isFalse();
	}

	@Test
	@DisplayName("기존 생성자는 그대로 동작해 호환을 깨지 않는다")
	void shouldKeepLegacyConstructor() {
		Gpt5Request request = new Gpt5Request("gpt-5", "프롬프트", 2048, "low", "low");

		assertThat(request.getModel()).isEqualTo("gpt-5");
		assertThat(request.getInput()).isEqualTo("프롬프트");
		assertThat(request.getInstructions()).isNull();
		assertThat(request.getMaxOutputTokens()).isEqualTo(2048);
		assertThat(request.getReasoning().getEffort()).isEqualTo("low");
		assertThat(request.getText().getVerbosity()).isEqualTo("low");
	}
}
