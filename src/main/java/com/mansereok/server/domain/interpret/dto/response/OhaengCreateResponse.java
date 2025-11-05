package com.mansereok.server.domain.interpret.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.interpret.dto.model.Element;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OhaengCreateResponse {

	private int status;
	private AnalysisData data;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class AnalysisData {

		@JsonProperty("_오행")
		private List<ElementInfo> ohaeng;

		@JsonProperty("_십성")
		private List<ElementInfo> sipseong;

		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class ElementInfo {

			private Element element; // 공통 Element 참조
			private double point;
			private double percent;
			private String description;
		}
	}
}
