package com.mansereok.server.domain.interpret.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import com.mansereok.server.domain.interpret.client.ReasoningEffort;
import com.mansereok.server.domain.interpret.client.Verbosity;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request.SystemInstruction;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request.UserPrompt;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Gpt5Request - 시스템 지시와 사용자 입력 분리 직렬화")
class Gpt5RequestTest {

	private static final ModelTier TIER =
		new ModelTier("gpt-5", 1024, ReasoningEffort.HIGH, Verbosity.MEDIUM);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private JsonNode serialize(Gpt5Request request) throws Exception {
		return objectMapper.readTree(objectMapper.writeValueAsString(request));
	}

	@Nested
	@DisplayName("요청 본문 직렬화")
	class Serialization {

		@Test
		@DisplayName("시스템 지시는 최상위 instructions 로, 사용자 프롬프트는 input 으로만 나간다")
		void shouldSerializeInstructionsAtTopLevel() throws Exception {
			// when
			JsonNode json = serialize(Gpt5Request.of(TIER,
				new SystemInstruction("너는 사주명리학자다"), new UserPrompt("[사용자 입력]\n이름: 김태우"), null));

			// then
			assertThat(json.get("instructions").asText()).isEqualTo("너는 사주명리학자다");
			assertThat(json.get("input").asText()).isEqualTo("[사용자 입력]\n이름: 김태우");
		}

		@Test
		@DisplayName("티어의 모델·출력 토큰 상한·추론 강도·출력 길이가 OpenAI 가 받는 소문자 값 그대로 실린다")
		void shouldSerializeTierValues() throws Exception {
			// when
			JsonNode json = serialize(Gpt5Request.of(TIER,
				new SystemInstruction("지시"), new UserPrompt("프롬프트"), null));

			// then
			assertThat(json.get("model").asText()).isEqualTo("gpt-5");
			assertThat(json.get("max_output_tokens").asInt()).isEqualTo(1024);
			assertThat(json.toString())
				.contains("\"reasoning\":{\"effort\":\"high\"}")
				.contains("\"verbosity\":\"medium\"");
		}

		@Test
		@DisplayName("outputFormat 을 주면 text.format 으로 실리고, 없으면 format 키 자체가 빠진다")
		void shouldIncludeFormatOnlyWhenGiven() throws Exception {
			// when
			JsonNode withFormat = serialize(Gpt5Request.of(TIER, new SystemInstruction("지시"),
				new UserPrompt("프롬프트"), Map.of("type", "json_schema")));
			JsonNode withoutFormat = serialize(Gpt5Request.of(TIER, new SystemInstruction("지시"),
				new UserPrompt("프롬프트"), null));

			// then
			assertThat(withFormat.at("/text/format/type").asText()).isEqualTo("json_schema");
			assertThat(withoutFormat.get("text").has("format")).isFalse();
		}

		@Test
		@DisplayName("요청을 만든 뒤 넘겨준 outputFormat Map 을 바꿔도 요청 본문의 format 은 그대로다")
		void keepsOwnCopyOfOutputFormat() throws Exception {
			// given
			Map<String, Object> outputFormat = new HashMap<>(Map.of("type", "json_schema"));
			Gpt5Request request = Gpt5Request.of(TIER, new SystemInstruction("지시"),
				new UserPrompt("프롬프트"), outputFormat);

			// when
			outputFormat.put("type", "text");

			// then
			assertThat(serialize(request).at("/text/format/type").asText()).isEqualTo("json_schema");
		}
	}

	@Nested
	@DisplayName("티어만 바꾼 사본(withTier)")
	class WithTier {

		@Test
		@DisplayName("시스템 지시·사용자 프롬프트·출력 형식은 그대로 두고 모델·토큰 상한·추론 강도·출력 길이만 바꾼다")
		void changesOnlyTierValues() throws Exception {
			// given
			Gpt5Request original = Gpt5Request.of(TIER, new SystemInstruction("지시"),
				new UserPrompt("프롬프트"), Map.of("type", "json_schema"));
			ModelTier fallback = new ModelTier("gpt-5.2", 32_768, ReasoningEffort.MEDIUM, Verbosity.LOW);

			// when
			JsonNode json = serialize(original.withTier(fallback));

			// then
			assertThat(json.get("model").asText()).isEqualTo("gpt-5.2");
			assertThat(json.get("max_output_tokens").asInt()).isEqualTo(32_768);
			assertThat(json.at("/reasoning/effort").asText()).isEqualTo("medium");
			assertThat(json.at("/text/verbosity").asText()).isEqualTo("low");
			assertThat(json.get("instructions").asText()).isEqualTo("지시");
			assertThat(json.get("input").asText()).isEqualTo("프롬프트");
			assertThat(json.at("/text/format/type").asText()).isEqualTo("json_schema");
		}

		@Test
		@DisplayName("티어 없이 부르면 만들지 못한다")
		void rejectsMissingTier() {
			// given
			Gpt5Request original = Gpt5Request.of(TIER, new SystemInstruction("지시"),
				new UserPrompt("프롬프트"), null);

			// when & then
			assertThatThrownBy(() -> original.withTier(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("티어는 있어야 합니다.");
		}
	}

	@Nested
	@DisplayName("요청을 만드는 길")
	class Creation {

		@Test
		@DisplayName("공개 생성자가 없어 of 를 거치지 않고는 요청을 만들 수 없다")
		void hasNoPublicConstructor() {
			// when
			Constructor<?>[] constructors = Gpt5Request.class.getDeclaredConstructors();

			// then
			assertThat(Arrays.stream(constructors).map(Constructor::getModifiers))
				.as("Gpt5Request 생성자의 접근 제한자")
				.noneMatch(Modifier::isPublic);
		}

		@ParameterizedTest(name = "[{index}] 시스템 지시 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("시스템 지시가 비어 있으면 만들지 못한다")
		void rejectsBlankSystemInstruction(String text) {
			assertThatThrownBy(() -> new SystemInstruction(text))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("시스템 지시는 비어 있을 수 없습니다.");
		}

		@ParameterizedTest(name = "[{index}] 사용자 프롬프트 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("사용자 프롬프트가 비어 있으면 만들지 못한다")
		void rejectsBlankUserPrompt(String text) {
			assertThatThrownBy(() -> new UserPrompt(text))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("사용자 프롬프트는 비어 있을 수 없습니다.");
		}

		@ParameterizedTest(name = "[{index}] {0} 없음")
		@MethodSource("requestsMissingOnePart")
		@DisplayName("티어·시스템 지시·사용자 프롬프트 중 하나라도 빠뜨리고 of 를 부르면 만들지 못한다")
		void rejectsMissingPart(String missingPart, ModelTier tier, SystemInstruction instructions,
			UserPrompt input) {
			assertThatThrownBy(() -> Gpt5Request.of(tier, instructions, input, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("티어, 시스템 지시, 사용자 프롬프트는 모두 있어야 합니다.");
		}

		static Stream<Arguments> requestsMissingOnePart() {
			return Stream.of(
				Arguments.of("티어", null, new SystemInstruction("지시"), new UserPrompt("프롬프트")),
				Arguments.of("시스템 지시", TIER, null, new UserPrompt("프롬프트")),
				Arguments.of("사용자 프롬프트", TIER, new SystemInstruction("지시"), null));
		}
	}
}
