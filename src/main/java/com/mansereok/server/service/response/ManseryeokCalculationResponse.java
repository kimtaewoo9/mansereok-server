package com.mansereok.server.service.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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

		@JsonProperty("big_fortune_start_year")
		private Integer bigFortuneStartYear;

		@JsonProperty("season_start_time")
		private String seasonStartTime;

		@JsonProperty("year_sky")
		private PillarElement yearSky;

		@JsonProperty("year_ground")
		@Schema(description = "년지 정보")
		private PillarElement yearGround;

		@JsonProperty("month_sky")
		@Schema(description = "월간 정보")
		private PillarElement monthSky;

		@JsonProperty("month_ground")
		@Schema(description = "월지 정보")
		private PillarElement monthGround;

		@JsonProperty("day_sky")
		@Schema(description = "일간 정보 (본인의 핵심)")
		private PillarElement daySky;

		@JsonProperty("day_ground")
		@Schema(description = "일지 정보")
		private PillarElement dayGround;

		@JsonProperty("time_sky")
		@Schema(description = "시간 정보")
		private PillarElement timeSky;

		@JsonProperty("time_ground")
		@Schema(description = "시지 정보")
		private PillarElement timeGround;

		@JsonProperty("sinsal_info")
		@Schema(description = "신살 정보")
		private Map<String, List<String>> sinsalInfo;

		@JsonProperty("has_goegang")
		@Schema(description = "괴강살 유무")
		private Boolean hasGoegang;

		@JsonProperty("has_baekho")
		@Schema(description = "백호대살 유무")
		private Boolean hasBaekho;

		@JsonProperty("gongmang")
		@Schema(description = "공망 지지 목록")
		private List<String> gongmang;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PillarElement {

		@Schema(description = "한자", example = "甲")
		private String chinese;

		@Schema(description = "한글", example = "갑")
		private String korean;

		@JsonProperty("five_circle")
		@Schema(description = "오행", example = "목")
		private String fiveCircle;

		@JsonProperty("five_circle_color")
		@Schema(description = "오행 색상", example = "#4CAF50")
		private String fiveCircleColor;

		@JsonProperty("ten_star")
		@Schema(description = "십성", example = "비견")
		private String tenStar;

		@JsonProperty("minus_plus")
		@Schema(description = "음양", example = "양")
		private String minusPlus;

		@JsonProperty("jijanggan")
		@Schema(description = "지장간 (지지에만 존재)")
		private JijangganInfo jijanggan;

		// 운성 정보 추가 .
		@JsonProperty("unseong")
		@Schema(description = "12운성 (지지에만 존재)", example = "제왕")
		private String unseong;

		@JsonProperty("unseong_description")
		@Schema(description = "운성 설명", example = "최고의 전성기, 완성")
		private String unseongDescription;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class JijangganInfo {

		@Schema(description = "첫 번째 지장간")
		private JijangganElement first;

		@Schema(description = "두 번째 지장간 (없을 수 있음)")
		private JijangganElement second;

		@Schema(description = "세 번째 지장간")
		private JijangganElement third;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class JijangganElement {

		@Schema(description = "한자", example = "壬")
		private String chinese;

		@Schema(description = "한글", example = "임")
		private String korean;

		@JsonProperty("five_circle")
		@Schema(description = "오행", example = "수")
		private String fiveCircle;

		@JsonProperty("five_circle_color")
		@Schema(description = "오행 색상", example = "#039BE5")
		private String fiveCircleColor;

		@JsonProperty("minus_plus")
		@Schema(description = "음양", example = "양")
		private String minusPlus;

		@Schema(description = "비율", example = "10")
		private Integer rate;
	}
}
