package com.mansereok.server.domain.interpret.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GptCompatibilityResponse {

	private Integer score;
	private String interpretation;
	private String summary;
}
