package com.mansereok.server.domain.interpret.calculator;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class YongsinCalculator {

	/**
	 * 서비스 고정 용신 규칙셋
	 * - 억부를 기본으로 하되 조후를 우선 보정한다.
	 * - 종격/전왕 등 특수격 별도 분기는 현재 버전에서 미적용.
	 */
	private static final String RULESET_CODE = "EOKBU_JOHU_V1";
	private static final String RULESET_NAME = "억부 중심 + 조후 보정";

	// 오행 점수에 더하는 기둥별 가중치. 지지는 지장간(통근)을 우선 반영하고 월지에 가장 큰 가중치를 둔다
	private static final PillarWeights STEM_WEIGHTS = new PillarWeights(1.0, 1.4, 1.2, 0.8);
	private static final PillarWeights BRANCH_WEIGHTS = new PillarWeights(1.0, 1.8, 1.3, 0.9);
	// 지지에 일간과 같은 오행의 뿌리가 있을 때 나의 점수에 더하는 기둥별 가중치
	private static final PillarWeights ROOT_WEIGHTS = new PillarWeights(0.4, 0.9, 0.7, 0.4);
	// 지장간이 없는 지지는 지지 오행이 일간과 같을 때 뿌리를 이 비율로 본다
	private static final double ROOT_RATE_WITHOUT_HIDDEN_STEMS = 0.6;

	// 월지로 본 계절 보정. 더하는 값은 나의 점수에, 빼는 값은 절댓값을 남의 점수에 더한다
	private static final double IN_SEASON_BONUS = 1.6;
	private static final double SUPPORTED_SEASON_BONUS = 0.8;
	private static final double CONTROLLED_SEASON_PENALTY = -1.2;
	private static final double DRAINED_SEASON_PENALTY = -0.6;

	// 월령(득령) 강세 구간
	private static final Map<FiveElement, Set<String>> STRONG_MONTH_MAP = Map.of(
		FiveElement.WOOD, Set.of("寅", "卯", "辰"),
		FiveElement.FIRE, Set.of("巳", "午", "未"),
		FiveElement.EARTH, Set.of("辰", "戌", "丑", "未"),
		FiveElement.METAL, Set.of("申", "酉", "戌"),
		FiveElement.WATER, Set.of("亥", "子", "丑")
	);

	// 월령 보조(상생 계절)
	private static final Map<FiveElement, Set<String>> SUPPORT_MONTH_MAP = Map.of(
		FiveElement.WOOD, Set.of("亥", "子"),
		FiveElement.FIRE, Set.of("寅", "卯"),
		FiveElement.EARTH, Set.of("巳", "午"),
		FiveElement.METAL, Set.of("辰", "丑", "未"),
		FiveElement.WATER, Set.of("申", "酉")
	);

	// 월령 약세(극받는 계절)
	private static final Map<FiveElement, Set<String>> WEAK_MONTH_MAP = Map.of(
		FiveElement.WOOD, Set.of("申", "酉", "戌"),
		FiveElement.FIRE, Set.of("亥", "子", "丑"),
		FiveElement.EARTH, Set.of("寅", "卯"),
		FiveElement.METAL, Set.of("巳", "午", "未"),
		FiveElement.WATER, Set.of("辰", "戌", "丑", "未")
	);

	public YongsinResult analyzeYongsin(SajuInfo saju) {
		if (saju == null || saju.getDaySky() == null) {
			return null;
		}

		String dayStemElement = saju.getDaySky().getFiveCircle();
		if (dayStemElement == null || dayStemElement.isBlank()) {
			return null;
		}

		FiveElement ilgan = FiveElement.of(dayStemElement);
		String monthBranch = normalizeBranch(saju.getMonthGround());
		Map<FiveElement, Double> elementScores = calculateElementScores(saju);
		StrengthMetrics metrics = calculateStrengthMetrics(saju, ilgan, monthBranch, elementScores);
		YongsinDecision decision = decideYongsin(ilgan, monthBranch, elementScores, metrics);

		String desc = String.format("%s / 희신:%s / 행운색:%s, 방향:%s",
			decision.yongsinType(), decision.heesin().korean(), decision.yongsin().luckyColor(),
			decision.yongsin().luckyDirection());

		return new YongsinResult(
			metrics.strength(),
			roundOne(metrics.myScore()),
			roundOne(metrics.totalScore()),
			decision.yongsin().korean(),
			desc,
			RULESET_CODE,
			RULESET_NAME
		);
	}

	private Map<FiveElement, Double> calculateElementScores(SajuInfo saju) {
		Map<FiveElement, Double> scores = new EnumMap<>(FiveElement.class);
		for (FiveElement element : FiveElement.values()) {
			scores.put(element, 0.0);
		}

		addStemScore(scores, saju.getYearSky(), STEM_WEIGHTS.year());
		addStemScore(scores, saju.getMonthSky(), STEM_WEIGHTS.month());
		addStemScore(scores, saju.getDaySky(), STEM_WEIGHTS.day());
		addStemScore(scores, saju.getTimeSky(), STEM_WEIGHTS.time());

		addGroundScore(scores, saju.getYearGround(), BRANCH_WEIGHTS.year());
		addGroundScore(scores, saju.getMonthGround(), BRANCH_WEIGHTS.month());
		addGroundScore(scores, saju.getDayGround(), BRANCH_WEIGHTS.day());
		addGroundScore(scores, saju.getTimeGround(), BRANCH_WEIGHTS.time());

		return scores;
	}

	private StrengthMetrics calculateStrengthMetrics(SajuInfo saju, FiveElement ilgan,
		String monthBranch, Map<FiveElement, Double> elementScores) {
		// 나의 세력은 비겁(일간과 같은 오행)과 인성, 남의 세력은 관살·식상·재성이다
		double myScore = elementScores.get(ilgan) + elementScores.get(ilgan.generatedBy());
		double nonMyScore = elementScores.get(ilgan.controlledBy())
			+ elementScores.get(ilgan.generates())
			+ elementScores.get(ilgan.controls());

		// 득령 반영
		double seasonAdjustment = evaluateSeasonAdjustment(ilgan, monthBranch);
		if (seasonAdjustment >= 0) {
			myScore += seasonAdjustment;
		} else {
			nonMyScore += Math.abs(seasonAdjustment);
		}

		// 통근 반영 (지지 및 지장간에서 일간 뿌리 확인)
		myScore += calculateTonggeunBonus(saju, ilgan);

		double totalScore = myScore + nonMyScore;
		if (totalScore <= 0) {
			totalScore = 1.0;
		}

		return new StrengthMetrics(Strength.of(myScore / totalScore), myScore, totalScore);
	}

	private YongsinDecision decideYongsin(FiveElement ilgan, String monthBranch,
		Map<FiveElement, Double> elementScores, StrengthMetrics metrics) {
		FiveElement climateYongsin = determineClimateYongsin(monthBranch);

		return switch (metrics.strength()) {
			case STRONG -> decideForStrong(ilgan, climateYongsin, elementScores);
			case WEAK -> decideForWeak(ilgan, climateYongsin, elementScores);
			case BALANCED -> decideForBalanced(ilgan, climateYongsin, elementScores);
		};
	}

	/**
	 * 신강: 관살(제어)과 식상(설기) 중 점수가 낮은 쪽으로 강한 기운을 뺀다.
	 */
	private YongsinDecision decideForStrong(FiveElement ilgan, FiveElement climateYongsin,
		Map<FiveElement, Double> elementScores) {
		FiveElement control = ilgan.controlledBy();
		FiveElement drain = ilgan.generates();
		FiveElement balancingYongsin = chooseLowerScoreElement(elementScores, control, drain);
		FiveElement balancingHeesin = balancingYongsin == control ? drain : control;

		if (climateYongsin != null) {
			return new YongsinDecision(climateYongsin, balancingYongsin,
				"조후+억부용신(한난 조절 후 강한 기운 제어)");
		}
		return new YongsinDecision(balancingYongsin, balancingHeesin, "억부용신(신강 사주 제어)");
	}

	/**
	 * 신약: 인성(생조)과 비겁 중 점수가 낮은 쪽으로 약한 기운을 채운다.
	 */
	private YongsinDecision decideForWeak(FiveElement ilgan, FiveElement climateYongsin,
		Map<FiveElement, Double> elementScores) {
		FiveElement resource = ilgan.generatedBy();
		FiveElement self = ilgan;
		FiveElement balancingYongsin = chooseLowerScoreElement(elementScores, resource, self);
		FiveElement balancingHeesin = balancingYongsin == resource ? self : resource;

		if (climateYongsin != null) {
			return new YongsinDecision(climateYongsin, balancingYongsin,
				"조후+억부용신(한난 조절 후 약한 기운 보강)");
		}
		return new YongsinDecision(balancingYongsin, balancingHeesin, "억부용신(신약 사주 보강)");
	}

	/**
	 * 중화: 가장 모자란 오행으로 균형을 맞춘다.
	 */
	private YongsinDecision decideForBalanced(FiveElement ilgan, FiveElement climateYongsin,
		Map<FiveElement, Double> elementScores) {
		FiveElement balanceYongsin = findMostDeficientElement(elementScores);
		if (climateYongsin != null) {
			return new YongsinDecision(climateYongsin, balanceYongsin, "조후용신(한난 조절 우선)");
		}
		return new YongsinDecision(balanceYongsin, ilgan.generatedBy(), "중화용신(오행 균형)");
	}

	private double evaluateSeasonAdjustment(FiveElement ilgan, String monthBranch) {
		if (monthBranch == null) {
			return 0.0;
		}

		if (STRONG_MONTH_MAP.get(ilgan).contains(monthBranch)) {
			return IN_SEASON_BONUS;
		}
		if (SUPPORT_MONTH_MAP.get(ilgan).contains(monthBranch)) {
			return SUPPORTED_SEASON_BONUS;
		}
		if (WEAK_MONTH_MAP.get(ilgan).contains(monthBranch)) {
			return CONTROLLED_SEASON_PENALTY;
		}
		// 일간이 생하는 오행(식상)이 왕한 달이면 힘이 빠진다
		if (STRONG_MONTH_MAP.get(ilgan.generates()).contains(monthBranch)) {
			return DRAINED_SEASON_PENALTY;
		}
		return 0.0;
	}

	private double calculateTonggeunBonus(SajuInfo saju, FiveElement ilgan) {
		double bonus = 0.0;
		bonus += calculateGroundRootBonus(saju.getYearGround(), ilgan, ROOT_WEIGHTS.year());
		bonus += calculateGroundRootBonus(saju.getMonthGround(), ilgan, ROOT_WEIGHTS.month());
		bonus += calculateGroundRootBonus(saju.getDayGround(), ilgan, ROOT_WEIGHTS.day());
		bonus += calculateGroundRootBonus(saju.getTimeGround(), ilgan, ROOT_WEIGHTS.time());
		return bonus;
	}

	private double calculateGroundRootBonus(PillarElement ground, FiveElement ilgan, double weight) {
		if (ground == null) {
			return 0.0;
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			double totalRate = sumPositiveRate(jijanggan.getFirst()) + sumPositiveRate(
				jijanggan.getSecond()) + sumPositiveRate(jijanggan.getThird());
			if (totalRate > 0) {
				double myRate = sumMatchingRate(jijanggan.getFirst(), ilgan)
					+ sumMatchingRate(jijanggan.getSecond(), ilgan)
					+ sumMatchingRate(jijanggan.getThird(), ilgan);
				return weight * (myRate / totalRate);
			}
		}

		if (isElement(ground.getFiveCircle(), ilgan)) {
			return weight * ROOT_RATE_WITHOUT_HIDDEN_STEMS;
		}
		return 0.0;
	}

	private FiveElement determineClimateYongsin(String monthBranch) {
		if (monthBranch == null) {
			return null;
		}
		if (Set.of("巳", "午", "未").contains(monthBranch)) {
			return FiveElement.WATER; // 여름 조후
		}
		if (Set.of("亥", "子", "丑").contains(monthBranch)) {
			return FiveElement.FIRE; // 겨울 조후
		}
		return null;
	}

	/**
	 * 점수가 가장 낮은 오행. 같은 점수면 목·화·토·금·수 순서에서 앞선 오행을 고른다.
	 */
	private FiveElement findMostDeficientElement(Map<FiveElement, Double> elementScores) {
		FiveElement result = FiveElement.WOOD;
		double min = Double.MAX_VALUE;
		for (FiveElement element : FiveElement.values()) {
			double score = elementScores.get(element);
			if (score < min) {
				min = score;
				result = element;
			}
		}
		return result;
	}

	private FiveElement chooseLowerScoreElement(Map<FiveElement, Double> elementScores, FiveElement a,
		FiveElement b) {
		return elementScores.get(a) <= elementScores.get(b) ? a : b;
	}

	private void addStemScore(Map<FiveElement, Double> scores, PillarElement element, double weight) {
		if (element != null && element.getFiveCircle() != null) {
			scores.merge(FiveElement.of(element.getFiveCircle()), weight, Double::sum);
		}
	}

	private void addGroundScore(Map<FiveElement, Double> scores, PillarElement ground, double weight) {
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
			scores.merge(FiveElement.of(ground.getFiveCircle()), weight, Double::sum);
		}
	}

	private void addHiddenScore(Map<FiveElement, Double> scores, JijangganElement hidden, double weight,
		double totalRate) {
		if (hidden == null || hidden.getFiveCircle() == null || hidden.getRate() == null
			|| hidden.getRate() <= 0 || totalRate <= 0) {
			return;
		}
		double ratio = hidden.getRate() / totalRate;
		scores.merge(FiveElement.of(hidden.getFiveCircle()), weight * ratio, Double::sum);
	}

	private double sumPositiveRate(JijangganElement hidden) {
		if (hidden == null || hidden.getRate() == null || hidden.getRate() <= 0) {
			return 0.0;
		}
		return hidden.getRate();
	}

	private double sumMatchingRate(JijangganElement hidden, FiveElement target) {
		if (hidden == null || hidden.getRate() == null || hidden.getRate() <= 0) {
			return 0.0;
		}
		if (!isElement(hidden.getFiveCircle(), target)) {
			return 0.0;
		}
		return hidden.getRate();
	}

	/**
	 * 오행 칸(한글 이름)이 비어 있지 않고 element 와 같은지. 모르는 이름이면 FiveElement.of 가 예외를 던진다.
	 */
	private static boolean isElement(String korean, FiveElement element) {
		return korean != null && FiveElement.of(korean) == element;
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

	@lombok.Data
	@lombok.AllArgsConstructor
	public static class YongsinResult {

		private Strength strength;
		private double myScore;
		private double totalScore;
		private String yongsin;
		private String description;
		private String appliedRuleCode;
		private String appliedRuleName;
	}

	private record StrengthMetrics(Strength strength, double myScore, double totalScore) {

	}

	/**
	 * 연·월·일·시 기둥에 주는 가중치.
	 */
	private record PillarWeights(double year, double month, double day, double time) {

	}

	private record YongsinDecision(FiveElement yongsin, FiveElement heesin, String yongsinType) {

	}
}
