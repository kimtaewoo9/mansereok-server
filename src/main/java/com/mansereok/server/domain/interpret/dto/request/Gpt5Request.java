package com.mansereok.server.domain.interpret.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class Gpt5Request {

	private String model;
	private String input; // ⚠️ 수정: input 문자열을 직접 받음
	@JsonProperty("max_output_tokens")
	private int maxOutputTokens;
	private Reasoning reasoning;
	private Text text;
	
	public Gpt5Request(String model, String input, int maxOutputTokens, String effort,
		String verbosity) {
		this.model = model;
		this.input = input;
		this.maxOutputTokens = maxOutputTokens;
		this.reasoning = new Reasoning(effort);
		this.text = new Text(verbosity);
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

		public Text(String verbosity) {
			this.verbosity = verbosity;
		}
	}
}
