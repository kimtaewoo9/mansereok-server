package com.mansereok.server.domain.interpret.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Getter;

@Getter
public class Gpt5Request {

	private String model;

	/**
	 * Responses API 의 최상위 시스템 지시 필드. 사용자 입력과 다른 채널로 전달되므로
	 * input 에 섞인 문장이 이 지시를 덮어쓰기 어렵다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String instructions;

	private String input; // 사용자 프롬프트만 담는다. 시스템 지시는 instructions 로 간다.
	@JsonProperty("max_output_tokens")
	private int maxOutputTokens;
	private Reasoning reasoning;
	private Text text;

	public Gpt5Request(String model, String input, int maxOutputTokens, String effort,
		String verbosity) {
		this(model, input, maxOutputTokens, effort, verbosity, null);
	}

	/**
	 * Structured Outputs 지원 생성자. outputFormat에 json_schema 포맷을 넘기면
	 * 모델 출력이 스키마에 강제되어, 프롬프트의 JSON 문법 지시와 잘린 JSON 복구가 불필요해진다.
	 */
	public Gpt5Request(String model, String input, int maxOutputTokens, String effort,
		String verbosity, Map<String, Object> outputFormat) {
		this(model, null, input, maxOutputTokens, effort, verbosity, outputFormat);
	}

	/**
	 * 시스템 지시와 사용자 입력을 분리하는 생성자.
	 * instructions 에는 서버가 만든 시스템 지시만, input 에는 사용자 프롬프트만 넣는다.
	 */
	public Gpt5Request(String model, String instructions, String input, int maxOutputTokens,
		String effort, String verbosity, Map<String, Object> outputFormat) {
		this.model = model;
		this.instructions = instructions;
		this.input = input;
		this.maxOutputTokens = maxOutputTokens;
		this.reasoning = new Reasoning(effort);
		this.text = new Text(verbosity, outputFormat);
	}

	@Getter
	public static class Reasoning {

		private String effort; // minimal, low, medium, high

		public Reasoning(String effort) {
			this.effort = effort;
		}
	}

	@Getter
	public static class Text {

		private String verbosity; // low, medium, high

		@JsonInclude(JsonInclude.Include.NON_NULL)
		private Map<String, Object> format; // Responses API structured outputs (json_schema)

		public Text(String verbosity) {
			this(verbosity, null);
		}

		public Text(String verbosity, Map<String, Object> format) {
			this.verbosity = verbosity;
			this.format = format;
		}
	}
}
