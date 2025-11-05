package com.mansereok.server.domain.interpret.dto.request;

import java.util.List;
import lombok.Getter;

@Getter
public class GptRequest {

	private String model;
	private List<Message> messages;
	private int max_tokens;
	private double temperature;

	public GptRequest(String model, List<Message> messages, int max_tokens, double temperature) {
		this.model = model;
		this.messages = messages;
		this.max_tokens = max_tokens;
		this.temperature = temperature;
	}
}
