package com.mansereok.server.domain.interpret.calculator;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class YongsinCalculator {

	private static final Map<String, String> SUPPORT_MAP = Map.of(
		"목", "수", "화", "목", "토", "화", "금", "토", "수", "금"
	);
	private static final Map<String, String> DRAIN_MAP = Map.of(
		"목", "화", "화", "토", "토", "금", "금", "수", "수", "목"
	);

	public YongsinResult analyzeYongsin(SajuInfo saju) {
		if (saju.getDaySky() == null) {
			return null;
		}

		String ilganOhaeng = saju.getDaySky().getFiveCircle();
		String monthOhaeng =
			saju.getMonthGround() != null ? saju.getMonthGround().getFiveCircle() : "";

		Map<String, Double> scores = calculateOhaengScores(saju);
		String resource = SUPPORT_MAP.get(ilganOhaeng);

		double myPower = scores.getOrDefault(ilganOhaeng, 0.0) + scores.getOrDefault(resource, 0.0);
		double totalPower = scores.values().stream().mapToDouble(Double::doubleValue).sum();
		boolean isSinGang = myPower >= (totalPower * 0.45); // 45% 이상이면 신강

		String yongsin;
		String yongsinType;

		// 1. 조후 용신 (계절적 균형 - 여름/겨울생 필수)
		// 여름(화)생인데 내가 물이 아니면 -> 물(수)이 용신
		if ("화".equals(monthOhaeng) && !"수".equals(ilganOhaeng)) {
			yongsin = "수";
			yongsinType = "조후용신(더위를 식히는 물)";
		}
		// 겨울(수)생인데 내가 불이 아니면 -> 불(화)이 용신
		else if ("수".equals(monthOhaeng) && !"화".equals(ilganOhaeng)) {
			yongsin = "화";
			yongsinType = "조후용신(추위를 녹이는 불)";
		}
		// 2. 억부 용신 (강약 조절)
		else if (isSinGang) {
			yongsin = DRAIN_MAP.get(ilganOhaeng); // 식상으로 설기
			yongsinType = "설기용신(넘치는 힘을 표현)";
		} else {
			yongsin = resource; // 인성으로 보완
			yongsinType = "부조용신(약한 기운을 도움)";
		}

		// 3. 행운 정보
		String luckyColor = getLuckyColor(yongsin);
		String luckyDirection = getLuckyDirection(yongsin);
		String desc = String.format("%s / 행운색:%s, 방향:%s", yongsinType, luckyColor, luckyDirection);

		return new YongsinResult(
			isSinGang ? "신강(身强)" : "신약(身弱)",
			myPower,
			totalPower,
			yongsin,
			desc
		);
	}

	private Map<String, Double> calculateOhaengScores(SajuInfo saju) {
		Map<String, Double> scores = new HashMap<>();
		for (String o : new String[]{"목", "화", "토", "금", "수"}) {
			scores.put(o, 0.0);
		}

		addScore(scores, saju.getYearSky(), 1.0);
		addScore(scores, saju.getYearGround(), 1.0);
		addScore(scores, saju.getMonthSky(), 1.0);
		addScore(scores, saju.getMonthGround(), 3.0); // 월지 가중치 3배
		addScore(scores, saju.getDaySky(), 1.0);
		addScore(scores, saju.getDayGround(), 2.0);   // 일지 가중치 2배
		if (saju.getTimeSky() != null) {
			addScore(scores, saju.getTimeSky(), 1.0);
		}
		if (saju.getTimeGround() != null) {
			addScore(scores, saju.getTimeGround(), 1.0);
		}

		return scores;
	}

	private void addScore(Map<String, Double> scores, PillarElement element, double weight) {
		if (element != null && element.getFiveCircle() != null) {
			String ohaeng = element.getFiveCircle();
			scores.put(ohaeng, scores.get(ohaeng) + weight);
		}
	}

	private String getLuckyColor(String ohaeng) {
		if (ohaeng == null) {
			return "-";
		}
		return switch (ohaeng) {
			case "목" -> "청색, 녹색";
			case "화" -> "적색, 분홍";
			case "토" -> "황색, 베이지";
			case "금" -> "백색, 은색";
			case "수" -> "검정, 남색";
			default -> "-";
		};
	}

	private String getLuckyDirection(String ohaeng) {
		if (ohaeng == null) {
			return "-";
		}
		return switch (ohaeng) {
			case "목" -> "동쪽";
			case "화" -> "남쪽";
			case "토" -> "중앙, 거주지 근처";
			case "금" -> "서쪽";
			case "수" -> "북쪽";
			default -> "-";
		};
	}

	@lombok.Data
	@lombok.AllArgsConstructor
	public static class YongsinResult {

		private String strength;
		private double myScore;
		private double totalScore;
		private String yongsin;
		private String description;
	}
}
