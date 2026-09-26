package com.mansereok.server.domain.interpret.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import com.mansereok.server.domain.interpret.client.ReasoningEffort;
import com.mansereok.server.domain.interpret.client.Verbosity;
import java.util.Map;
import lombok.Getter;

/**
 * OpenAI Responses API 요청 본문. 만드는 길은 {@link #of} 하나뿐이다.
 *
 * <p>시스템 지시와 사용자 프롬프트를 String 이 아닌 각자의 타입({@link SystemInstruction}, {@link UserPrompt})으로 받는다.
 * 둘 다 String 이면 순서를 뒤바꿔 넘겨도 컴파일이 통과하고, 그러면 사용자 프롬프트가 신뢰 채널인 instructions 로 올라가
 * 시스템 지시와 사용자 입력의 경계가 무너진다. 모델·토큰 상한·추론 강도·출력 길이도 티어({@link ModelTier}) 하나로 받아,
 * 호출부가 네 값을 풀어서 넘기다 순서를 바꿀 여지를 없앤다.
 */
@Getter
public class Gpt5Request {

	private final String model;

	/**
	 * Responses API 의 최상위 시스템 지시 필드. 사용자 입력과 다른 채널로 전달되므로
	 * input 에 섞인 문장이 이 지시를 덮어쓰기 어렵다.
	 */
	private final String instructions;

	private final String input; // 사용자 프롬프트만 담는다. 시스템 지시는 instructions 로 간다.
	@JsonProperty("max_output_tokens")
	private final int maxOutputTokens;
	private final Reasoning reasoning;
	private final Text text;

	private Gpt5Request(ModelTier tier, SystemInstruction instructions, UserPrompt input,
		Map<String, Object> outputFormat) {
		this.model = tier.model();
		this.instructions = instructions.text();
		this.input = input.text();
		this.maxOutputTokens = tier.maxOutputTokens();
		this.reasoning = new Reasoning(tier.reasoningEffort());
		this.text = new Text(tier.verbosity(), outputFormat);
	}

	/**
	 * 티어 설정으로 요청을 만든다. instructions 에는 서버가 만든 시스템 지시만, input 에는 사용자 프롬프트만 들어간다.
	 * outputFormat 에 json_schema 포맷을 넘기면 모델 출력이 스키마에 맞춰진다(Structured Outputs). 없으면 null 을 넘긴다.
	 */
	public static Gpt5Request of(ModelTier tier, SystemInstruction instructions, UserPrompt input,
		Map<String, Object> outputFormat) {
		if (tier == null || instructions == null || input == null) {
			throw new IllegalArgumentException("티어, 시스템 지시, 사용자 프롬프트는 모두 있어야 합니다.");
		}
		return new Gpt5Request(tier, instructions, input, outputFormat);
	}

	/** 서버가 정한 시스템 지시. 요청 본문의 instructions 로만 간다. */
	public record SystemInstruction(String text) {

		public SystemInstruction {
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("시스템 지시는 비어 있을 수 없습니다.");
			}
		}
	}

	/** 사용자 데이터가 들어간 프롬프트. 요청 본문의 input 으로만 간다. */
	public record UserPrompt(String text) {

		public UserPrompt {
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("사용자 프롬프트는 비어 있을 수 없습니다.");
			}
		}
	}

	@Getter
	public static class Reasoning {

		private final ReasoningEffort effort;

		private Reasoning(ReasoningEffort effort) {
			this.effort = effort;
		}
	}

	@Getter
	public static class Text {

		private final Verbosity verbosity;

		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final Map<String, Object> format; // Responses API structured outputs (json_schema)

		private Text(Verbosity verbosity, Map<String, Object> format) {
			this.verbosity = verbosity;
			this.format = format;
		}
	}
}
