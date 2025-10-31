package com.mansereok.server.service.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GptSajuResponse {

	private String fullAnalysis;
	private String summary;
}
