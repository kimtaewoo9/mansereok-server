package com.mansereok.server.domain.interpret.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManseryeokCalculationResponse {

	@Schema(description = "입력된 정보")
	private InputInfo input;

	@Schema(description = "계산된 사주팔자 정보")
	private SajuInfo saju;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
		public static class InputInfo {

		@JsonProperty("solar_date")
		private LocalDate solarDate;
			@JsonProperty("solar_time")
			private LocalTime solarTime;
			@JsonProperty("time_unknown")
			private Boolean timeUnknown;
			private String gender;
			@JsonProperty("is_lunar")
			private Boolean isLunar;
		}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class SajuInfo {

			@JsonProperty("big_fortune_number")
			private Integer bigFortuneNumber;
			@JsonProperty("big_fortune_number_min")
			private Integer bigFortuneNumberMin;
			@JsonProperty("big_fortune_number_max")
			private Integer bigFortuneNumberMax;
			@JsonProperty("big_fortune_start_year")
			private Integer bigFortuneStartYear;
			@JsonProperty("big_fortune_start_year_min")
			private Integer bigFortuneStartYearMin;
			@JsonProperty("big_fortune_start_year_max")
			private Integer bigFortuneStartYearMax;
			@JsonProperty("season_start_time")
			private String seasonStartTime;
			@JsonProperty("uncertainty_notes")
			private List<String> uncertaintyNotes;

		@JsonProperty("year_sky")
		private PillarElement yearSky;
		@JsonProperty("year_ground")
		private PillarElement yearGround;
		@JsonProperty("month_sky")
		private PillarElement monthSky;
		@JsonProperty("month_ground")
		private PillarElement monthGround;
		@JsonProperty("day_sky")
		private PillarElement daySky;
		@JsonProperty("day_ground")
		private PillarElement dayGround;
		@JsonProperty("time_sky")
		private PillarElement timeSky;
		@JsonProperty("time_ground")
		private PillarElement timeGround;

		@JsonProperty("sinsal_info")
		private Map<String, List<String>> sinsalInfo;
		@JsonProperty("has_goegang")
		private Boolean hasGoegang;
		@JsonProperty("has_baekho")
		private Boolean hasBaekho;
		@JsonProperty("gongmang")
		private List<String> gongmang;

		// [100점짜리 수정] 모든 지지/천간 관계를 리스트로 통합
		@JsonProperty("ground_relations")
		@Schema(description = "지지 관계 분석 (합, 충, 원진)", example = "[\"년지-월지: 충\", \"일지-월지: 원진\"]")
		private List<String> groundRelations;

		@JsonProperty("sky_relations")
		@Schema(description = "천간 관계 분석 (합, 충)", example = "[\"년간-월간: 천간합\"]")
		private List<String> skyRelations;

		@JsonProperty("samhap")
		private List<String> samhap;

		@JsonProperty("yongsin_info")
		private YongsinResult yongsinInfo;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PillarElement {

		private String chinese;
		private String korean;
		@JsonProperty("five_circle")
		private String fiveCircle;
		@JsonProperty("five_circle_color")
		private String fiveCircleColor;
		@JsonProperty("ten_star")
		private String tenStar;
		@JsonProperty("minus_plus")
		private String minusPlus;
		@JsonProperty("jijanggan")
		private JijangganInfo jijanggan;
		@JsonProperty("unseong")
		private String unseong;
		@JsonProperty("unseong_description")
		private String unseongDescription;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class JijangganInfo {

		private JijangganElement first;
		private JijangganElement second;
		private JijangganElement third;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class JijangganElement {

		private String chinese;
		private String korean;
		@JsonProperty("five_circle")
		private String fiveCircle;
		@JsonProperty("five_circle_color")
		private String fiveCircleColor;
		@JsonProperty("minus_plus")
		private String minusPlus;
		private Integer rate;
		@JsonProperty("ten_star")
		private String tenStar;
	}
}
