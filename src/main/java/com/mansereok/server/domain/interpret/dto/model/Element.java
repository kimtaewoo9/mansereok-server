package com.mansereok.server.domain.interpret.dto.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Element {

	private int id;
	private String name;
	private String chinese;

	@JsonProperty("_음양")
	private EumYang eumyang;

	@JsonProperty("_오행")
	private Ohaeng ohaeng;

	@JsonProperty("_십성")
	private Sipseong sipseong;
}
