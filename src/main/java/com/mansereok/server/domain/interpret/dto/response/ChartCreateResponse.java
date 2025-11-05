package com.mansereok.server.domain.interpret.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.interpret.dto.model.Element;
import com.mansereok.server.domain.interpret.dto.model.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.model.UnseongInfo;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChartCreateResponse {

	private int status;
	private BasicChartData data;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class BasicChartData {

		@JsonProperty("_기본명식")
		private SajuChart sajuChart;

		@JsonProperty("_신살")
		private SinsalInfo sinsal;

		private ProfileInfo profile;

		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class SajuChart {

			@JsonProperty("_세차")
			private Pillar yearPillar;

			@JsonProperty("_월건")
			private Pillar monthPillar;

			@JsonProperty("_일진")
			private Pillar dayPillar;

			@JsonProperty("_시진")
			private Pillar timePillar;
		}

		// Pillar 클래스는 천간/지지의 타입으로 공통 Element를 사용합니다.
		// JSON에 있는 _지장간, _운성은 Element 클래스 자체에 포함시키도록 변경했습니다.
		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class Pillar {

			// 천간 + 지지 -> 일간
			@JsonProperty("_천간")
			private Element cheongan;

			@JsonProperty("_지지")
			private Element jiji;

			@JsonProperty("_지장간")
			private List<JijangganInfo> jijangganList;

			@JsonProperty("_운성")
			private UnseongInfo unseong;
		}


		// 신살 정보
		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class SinsalInfo {

			@JsonProperty("_세차")
			private SinsalElement yearSinsal;

			@JsonProperty("_월건")
			private SinsalElement monthSinsal;

			@JsonProperty("_일진")
			private SinsalElement daySinsal;

			@JsonProperty("_시진")
			private SinsalElement timeSinsal;
		}

		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class SinsalElement {

			private int id;
			private String name;
			private String chinese;
		}

		// 프로필 정보
		@Data
		@NoArgsConstructor
		@AllArgsConstructor
		public static class ProfileInfo {

			private int index;
			private String avatar;
			private String sexagenaryCycle;
			private String sunBirth;
			private String lunBirth;
			private String adjustedBirth;
			private String location;
			private String adjusted;
		}
	}
}
