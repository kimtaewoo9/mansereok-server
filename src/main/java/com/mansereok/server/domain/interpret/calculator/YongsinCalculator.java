package com.mansereok.server.domain.interpret.calculator;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class YongsinCalculator {

	/**
	 * 생(生) 관계: 일간을 생해주는 오행(인성)
	 */
	private static final Map<String, String> RESOURCE_MAP = Map.of(
		"목", "수",
		"화", "목",
		"토", "화",
		"금", "토",
		"수", "금"
	);
	/**
	 * 설(洩) 관계: 일간이 생하는 오행(식상)
	 */
	private static final Map<String, String> DRAIN_MAP = Map.of(
		"목", "화",
		"화", "토",
		"토", "금",
		"금", "수",
		"수", "목"
	);
	/**
	 * 극(剋) 관계: 일간을 제어하는 오행(관살)
	 */
	private static final Map<String, String> CONTROL_MAP = Map.of(
		"목", "금",
		"화", "수",
		"토", "목",
		"금", "화",
		"수", "토"
	);
	/**
	 * 재성: 일간이 극하는 오행
	 */
	private static final Map<String, String> WEALTH_MAP = Map.of(
		"목", "토",
		"화", "금",
		"토", "수",
		"금", "목",
		"수", "화"
	);

	// 월령(득령) 강세 구간
	private static final Map<String, Set<String>> STRONG_MONTH_MAP = Map.of(
		"목", Set.of("寅", "卯", "辰"),
		"화", Set.of("巳", "午", "未"),
		"토", Set.of("辰", "戌", "丑", "未"),
		"금", Set.of("申", "酉", "戌"),
		"수", Set.of("亥", "子", "丑")
	);

	// 월령 보조(상생 계절)
	private static final Map<String, Set<String>> SUPPORT_MONTH_MAP = Map.of(
		"목", Set.of("亥", "子"),
		"화", Set.of("寅", "卯"),
		"토", Set.of("巳", "午"),
		"금", Set.of("辰", "丑", "未"),
		"수", Set.of("申", "酉")
	);

	// 월령 약세(극받는 계절)
	private static final Map<String, Set<String>> WEAK_MONTH_MAP = Map.of(
		"목", Set.of("申", "酉", "戌"),
		"화", Set.of("亥", "子", "丑"),
		"토", Set.of("寅", "卯"),
		"금", Set.of("巳", "午", "未"),
		"수", Set.of("辰", "戌", "丑", "未")
	);

	public YongsinResult analyzeYongsin(SajuInfo saju) {
		if (saju == null || saju.getDaySky() == null) {
			return null;
		}

		String ilganOhaeng = saju.getDaySky().getFiveCircle();
		if (ilganOhaeng == null || ilganOhaeng.isBlank()) {
			return null;
		}

		String monthBranch = normalizeBranch(saju.getMonthGround());
		Map<String, Double> elementScores = calculateElementScores(saju);
		StrengthMetrics metrics = calculateStrengthMetrics(saju, ilganOhaeng, monthBranch,
			elementScores);
		YongsinDecision decision = decideYongsin(ilganOhaeng, monthBranch, elementScores, metrics);

		String luckyColor = getLuckyColor(decision.yongsin);
		String luckyDirection = getLuckyDirection(decision.yongsin);
		String desc = String.format("%s / 희신:%s / 행운색:%s, 방향:%s",
			decision.yongsinType, decision.heesin, luckyColor, luckyDirection);

		return new YongsinResult(
			metrics.strengthLabel,
			roundOne(metrics.myScore),
			roundOne(metrics.totalScore),
			decision.yongsin,
			desc
		);
	}

	private Map<String, Double> calculateElementScores(SajuInfo saju) {
		Map<String, Double> scores = new HashMap<>();
		for (String o : new String[]{"목", "화", "토", "금", "수"}) {
			scores.put(o, 0.0);
		}

		addStemScore(scores, saju.getYearSky(), 1.0);
		addStemScore(scores, saju.getMonthSky(), 1.4);
		addStemScore(scores, saju.getDaySky(), 1.2);
		addStemScore(scores, saju.getTimeSky(), 0.8);

		// 지지는 지장간(통근)을 우선 반영, 월지에 가장 큰 가중치
		addGroundScore(scores, saju.getYearGround(), 1.0);
		addGroundScore(scores, saju.getMonthGround(), 1.8);
		addGroundScore(scores, saju.getDayGround(), 1.3);
		addGroundScore(scores, saju.getTimeGround(), 0.9);

		return scores;
	}

	private StrengthMetrics calculateStrengthMetrics(SajuInfo saju, String ilganOhaeng,
		String monthBranch, Map<String, Double> elementScores) {
		String resource = RESOURCE_MAP.get(ilganOhaeng);
		String control = CONTROL_MAP.get(ilganOhaeng);
		String drain = DRAIN_MAP.get(ilganOhaeng);
		String wealth = WEALTH_MAP.get(ilganOhaeng);

		double myScore = elementScores.getOrDefault(ilganOhaeng, 0.0)
			+ elementScores.getOrDefault(resource, 0.0);
		double nonMyScore = elementScores.getOrDefault(control, 0.0)
			+ elementScores.getOrDefault(drain, 0.0)
			+ elementScores.getOrDefault(wealth, 0.0);

		// 득령 반영
		double seasonAdjustment = evaluateSeasonAdjustment(ilganOhaeng, monthBranch);
		if (seasonAdjustment >= 0) {
			myScore += seasonAdjustment;
		} else {
			nonMyScore += Math.abs(seasonAdjustment);
		}

		// 통근 반영 (지지 및 지장간에서 일간 뿌리 확인)
		myScore += calculateTonggeunBonus(saju, ilganOhaeng);

		double totalScore = myScore + nonMyScore;
		if (totalScore <= 0) {
			totalScore = 1.0;
		}

		double ratio = myScore / totalScore;
		String strength = ratio >= 0.58 ? "신강(身强)" : ratio <= 0.42 ? "신약(身弱)" : "중화(中和)";

		return new StrengthMetrics(strength, myScore, totalScore, ratio);
	}

	private YongsinDecision decideYongsin(String ilganOhaeng, String monthBranch,
		Map<String, Double> elementScores, StrengthMetrics metrics) {
		String climateYongsin = determineClimateYongsin(monthBranch);

		if (metrics.ratio >= 0.58) { // 신강: 제어/설기
			String control = CONTROL_MAP.get(ilganOhaeng);
			String drain = DRAIN_MAP.get(ilganOhaeng);
			String balancingYongsin = chooseLowerScoreElement(elementScores, control, drain);
			String balancingHeesin = balancingYongsin.equals(control) ? drain : control;

			if (climateYongsin != null) {
				return new YongsinDecision(climateYongsin, balancingYongsin,
					"조후+억부용신(한난 조절 후 강한 기운 제어)");
			}
			return new YongsinDecision(balancingYongsin, balancingHeesin, "억부용신(신강 사주 제어)");
		}

		if (metrics.ratio <= 0.42) { // 신약: 생조/비겁
			String resource = RESOURCE_MAP.get(ilganOhaeng);
			String self = ilganOhaeng;
			String balancingYongsin = chooseLowerScoreElement(elementScores, resource, self);
			String balancingHeesin = balancingYongsin.equals(resource) ? self : resource;

			if (climateYongsin != null) {
				return new YongsinDecision(climateYongsin, balancingYongsin,
					"조후+억부용신(한난 조절 후 약한 기운 보강)");
			}
			return new YongsinDecision(balancingYongsin, balancingHeesin, "억부용신(신약 사주 보강)");
		}

		// 중화: 결핍된 오행으로 균형
		String balanceYongsin = findMostDeficientElement(elementScores);
		if (climateYongsin != null) {
			return new YongsinDecision(climateYongsin, balanceYongsin, "조후용신(한난 조절 우선)");
		}
		return new YongsinDecision(balanceYongsin, RESOURCE_MAP.get(ilganOhaeng), "중화용신(오행 균형)");
	}

	private double evaluateSeasonAdjustment(String ilganOhaeng, String monthBranch) {
		if (monthBranch == null) {
			return 0.0;
		}

		if (STRONG_MONTH_MAP.getOrDefault(ilganOhaeng, Set.of()).contains(monthBranch)) {
			return 1.6;
		}
		if (SUPPORT_MONTH_MAP.getOrDefault(ilganOhaeng, Set.of()).contains(monthBranch)) {
			return 0.8;
		}
		if (WEAK_MONTH_MAP.getOrDefault(ilganOhaeng, Set.of()).contains(monthBranch)) {
			return -1.2;
		}

		String drain = DRAIN_MAP.get(ilganOhaeng);
		if (STRONG_MONTH_MAP.getOrDefault(drain, Set.of()).contains(monthBranch)) {
			return -0.6;
		}
		return 0.0;
	}

	private double calculateTonggeunBonus(SajuInfo saju, String ilganOhaeng) {
		double bonus = 0.0;
		bonus += calculateGroundRootBonus(saju.getYearGround(), ilganOhaeng, 0.4);
		bonus += calculateGroundRootBonus(saju.getMonthGround(), ilganOhaeng, 0.9);
		bonus += calculateGroundRootBonus(saju.getDayGround(), ilganOhaeng, 0.7);
		bonus += calculateGroundRootBonus(saju.getTimeGround(), ilganOhaeng, 0.4);
		return bonus;
	}

	private double calculateGroundRootBonus(PillarElement ground, String ilganOhaeng, double weight) {
		if (ground == null) {
			return 0.0;
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			double totalRate = sumPositiveRate(jijanggan.getFirst()) + sumPositiveRate(
				jijanggan.getSecond()) + sumPositiveRate(jijanggan.getThird());
			if (totalRate > 0) {
				double myRate = sumMatchingRate(jijanggan.getFirst(), ilganOhaeng)
					+ sumMatchingRate(jijanggan.getSecond(), ilganOhaeng)
					+ sumMatchingRate(jijanggan.getThird(), ilganOhaeng);
				return weight * (myRate / totalRate);
			}
		}

		if (ilganOhaeng.equals(ground.getFiveCircle())) {
			return weight * 0.6;
		}
		return 0.0;
	}

	private String determineClimateYongsin(String monthBranch) {
		if (monthBranch == null) {
			return null;
		}
		if (Set.of("巳", "午", "未").contains(monthBranch)) {
			return "수"; // 여름 조후
		}
		if (Set.of("亥", "子", "丑").contains(monthBranch)) {
			return "화"; // 겨울 조후
		}
		return null;
	}

	private String findMostDeficientElement(Map<String, Double> elementScores) {
		String result = "목";
		double min = Double.MAX_VALUE;
		for (String element : new String[]{"목", "화", "토", "금", "수"}) {
			double score = elementScores.getOrDefault(element, 0.0);
			if (score < min) {
				min = score;
				result = element;
			}
		}
		return result;
	}

	private String chooseLowerScoreElement(Map<String, Double> elementScores, String a, String b) {
		double aScore = elementScores.getOrDefault(a, 0.0);
		double bScore = elementScores.getOrDefault(b, 0.0);
		return aScore <= bScore ? a : b;
	}

	private void addStemScore(Map<String, Double> scores, PillarElement element, double weight) {
		if (element != null && element.getFiveCircle() != null) {
			String ohaeng = element.getFiveCircle();
			scores.put(ohaeng, scores.getOrDefault(ohaeng, 0.0) + weight);
		}
	}

	private void addGroundScore(Map<String, Double> scores, PillarElement ground, double weight) {
		if (ground == null) {
			return;
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			double totalRate = sumPositiveRate(jijanggan.getFirst()) + sumPositiveRate(
				jijanggan.getSecond()) + sumPositiveRate(jijanggan.getThird());
			if (totalRate > 0) {
				addHiddenScore(scores, jijanggan.getFirst(), weight, totalRate);
				addHiddenScore(scores, jijanggan.getSecond(), weight, totalRate);
				addHiddenScore(scores, jijanggan.getThird(), weight, totalRate);
				return;
			}
		}

		if (ground.getFiveCircle() != null) {
			String ohaeng = ground.getFiveCircle();
			scores.put(ohaeng, scores.getOrDefault(ohaeng, 0.0) + weight);
		}
	}

	private void addHiddenScore(Map<String, Double> scores, JijangganElement hidden, double weight,
		double totalRate) {
		if (hidden == null || hidden.getFiveCircle() == null || hidden.getRate() == null
			|| hidden.getRate() <= 0 || totalRate <= 0) {
			return;
		}
		String ohaeng = hidden.getFiveCircle();
		double ratio = hidden.getRate() / totalRate;
		scores.put(ohaeng, scores.getOrDefault(ohaeng, 0.0) + (weight * ratio));
	}

	private double sumPositiveRate(JijangganElement hidden) {
		if (hidden == null || hidden.getRate() == null || hidden.getRate() <= 0) {
			return 0.0;
		}
		return hidden.getRate();
	}

	private double sumMatchingRate(JijangganElement hidden, String targetOhaeng) {
		if (hidden == null || hidden.getRate() == null || hidden.getRate() <= 0) {
			return 0.0;
		}
		if (!targetOhaeng.equals(hidden.getFiveCircle())) {
			return 0.0;
		}
		return hidden.getRate();
	}

	private String normalizeBranch(PillarElement monthGround) {
		if (monthGround == null) {
			return null;
		}
		String raw = monthGround.getChinese();
		if (raw == null || raw.isBlank()) {
			raw = monthGround.getKorean();
		}
		if (raw == null || raw.isBlank()) {
			return null;
		}

		String first = raw.trim().substring(0, 1);
		return switch (first) {
			case "자" -> "子";
			case "축" -> "丑";
			case "인" -> "寅";
			case "묘" -> "卯";
			case "진" -> "辰";
			case "사" -> "巳";
			case "오" -> "午";
			case "미" -> "未";
			case "신" -> "申";
			case "유" -> "酉";
			case "술" -> "戌";
			case "해" -> "亥";
			default -> first;
		};
	}

	private double roundOne(double value) {
		return Math.round(value * 10.0) / 10.0;
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

	private static class StrengthMetrics {

		final String strengthLabel;
		final double myScore;
		final double totalScore;
		final double ratio;

		StrengthMetrics(String strengthLabel, double myScore, double totalScore, double ratio) {
			this.strengthLabel = strengthLabel;
			this.myScore = myScore;
			this.totalScore = totalScore;
			this.ratio = ratio;
		}
	}

	private static class YongsinDecision {

		final String yongsin;
		final String heesin;
		final String yongsinType;

		YongsinDecision(String yongsin, String heesin, String yongsinType) {
			this.yongsin = yongsin;
			this.heesin = heesin == null ? "-" : heesin;
			this.yongsinType = yongsinType;
		}
	}
}
