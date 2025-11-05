package com.mansereok.server.domain.interpret.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mansereok.server.domain.interpret.dto.model.Element;
import com.mansereok.server.domain.interpret.dto.model.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.model.UnseongInfo;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DaeunCreateResponse {

	private int status;
	private SajuData data;

	@Data
	@NoArgsConstructor
	public static class SajuData {

		@JsonProperty("_대운수")
		private int daeunNumber;

		@JsonProperty("_대운간지")
		private String daeunGanji;

		@JsonProperty("_대운")
		private List<DaeunInfo> daeunList;

		@JsonProperty("_연운")
		private List<YeonunInfo> yeonunList;

		@JsonProperty("_월운")
		private List<WolunInfo> wolunList;
	}

	@Data
	@NoArgsConstructor
	public static class DaeunInfo {

		@JsonProperty("_간지")
		private GanjiInfo ganji;

		private int age;
		private int year;
		private int month;
	}

	@Data
	@NoArgsConstructor
	public static class YeonunInfo {

		@JsonProperty("_간지")
		private GanjiInfo ganji;

		private int age;
		private int year;
		private int month;
	}

	@Data
	@NoArgsConstructor
	public static class WolunInfo {

		@JsonProperty("_간지")
		private GanjiInfo ganji;

		private int age;
		private int year;
		private int month;
	}

	// GanjiInfo는 이제 Element를 직접 사용합니다.
	@Data
	@NoArgsConstructor
	public static class GanjiInfo {

		@JsonProperty("_천간")
		private Element cheongan; // 공통 Element 참조
		@JsonProperty("_지지")
		private Element jiji;     // 공통 Element 참조

		@JsonProperty("_지장간")
		private List<JijangganInfo> jijangganList;

		@JsonProperty("_운성")
		private UnseongInfo unseong;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class OhaengAnalysis { // 내부 클래스로 유지

		private int wood;
		private int fire;
		private int earth;
		private int metal;
		private int water;
		private String dominantElement;
		private String weakElement;
	}

	public static class SajuDataUtils {

		// 현재 대운 정보 조회
		public static DaeunInfo getCurrentDaeun(SajuData sajuData) {
			return sajuData.getDaeunList().stream()
				.filter(daeun -> daeun.getYear() <= 2025 && daeun.getAge() <= 28)
				.reduce((first, second) -> second) // 가장 최근 것
				.orElse(sajuData.getDaeunList().get(0));
		}

		// 현재 연운 조회 (2025년)
		public static YeonunInfo getCurrentYearFortune(SajuData sajuData) {
			return sajuData.getYeonunList().stream()
				.filter(yeonun -> yeonun.getYear() == 2025)
				.findFirst()
				.orElse(null);
		}

		// 현재 월운 조회 (9월)
		public static WolunInfo getCurrentMonthFortune(SajuData sajuData) {
			return sajuData.getWolunList().stream()
				.filter(wolun -> wolun.getMonth() == 9)
				.findFirst()
				.orElse(null);
		}

		// 오행 분석 (Element.getOhaeng().getName() 참조 방식 변경)
		public static OhaengAnalysis analyzeOhaeng(SajuData sajuData) {
			int wood = 0, fire = 0, earth = 0, metal = 0, water = 0;

			// 대운의 오행 카운트
			for (DaeunInfo daeun : sajuData.getDaeunList()) {
				// Element 클래스의 getOhaeng() 메서드를 직접 호출합니다.
				String elementOhaengName = daeun.getGanji().getCheongan().getOhaeng().getName();
				switch (elementOhaengName) {
					case "목" -> wood++;
					case "화" -> fire++;
					case "토" -> earth++;
					case "금" -> metal++;
					case "수" -> water++;
				}
			}

			return OhaengAnalysis.builder()
				.wood(wood).fire(fire).earth(earth).metal(metal).water(water)
				.dominantElement(findDominantElement(wood, fire, earth, metal, water))
				.weakElement(findWeakElement(wood, fire, earth, metal, water))
				.build();
		}

		private static String findDominantElement(int wood, int fire, int earth, int metal,
			int water) {
			int max = Math.max(Math.max(wood, fire), Math.max(Math.max(earth, metal), water));
			if (wood == max) {
				return "목";
			}
			if (fire == max) {
				return "화";
			}
			if (earth == max) {
				return "토";
			}
			if (metal == max) {
				return "금";
			}
			return "수";
		}

		private static String findWeakElement(int wood, int fire, int earth, int metal, int water) {
			int min = Math.min(Math.min(wood, fire), Math.min(Math.min(earth, metal), water));
			if (wood == min) {
				return "목";
			}
			if (fire == min) {
				return "화";
			}
			if (earth == min) {
				return "토";
			}
			if (metal == min) {
				return "금";
			}
			return "수";
		}

		// 간지 조합 문자열 생성
		public static String getGanjiString(GanjiInfo ganji) {
			return ganji.getCheongan().getName() + ganji.getJiji().getName();
		}

		// 십성 해석
		public static String getSipseongDescription(String sipseong) {
			return switch (sipseong) {
				case "정인" -> "학습과 발전, 명예";
				case "편인" -> "창의성과 직감";
				case "비견" -> "동료와 협력";
				case "겁재" -> "경쟁과 도전";
				case "식신" -> "표현력과 재능";
				case "상관" -> "변화와 혁신";
				case "편재" -> "유동적 재물운";
				case "정재" -> "안정적 재물운";
				case "편관" -> "변화와 도전";
				case "정관" -> "질서와 체계";
				default -> "안정";
			};
		}

		// 운성 해석
		public static String getUnseongDescription(String unseong) {
			return switch (unseong) {
				case "장생" -> "새로운 시작과 성장";
				case "목욕" -> "변화와 정화";
				case "관대" -> "성숙함과 책임";
				case "건록" -> "안정과 발전";
				case "제왕" -> "절정과 완성";
				case "쇠" -> "차분한 정리";
				case "병" -> "주의 깊은 관리";
				case "사" -> "끝과 새 시작";
				case "묘" -> "잠재력 축적";
				case "절" -> "극복과 재기";
				case "태" -> "새로운 준비";
				case "양" -> "성장과 발전";
				default -> "안정";
			};
		}
	}
}
