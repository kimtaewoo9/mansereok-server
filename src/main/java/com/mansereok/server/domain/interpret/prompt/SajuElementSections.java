package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.calculator.FiveElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 지장간과 오행 분포를 다루는 조각.
 */
final class SajuElementSections {

	private SajuElementSections() {
	}

	/**
	 * 기둥 글자의 한글 표기. 글자가 없으면 기존 출력과 같게 "?" 를 쓴다.
	 */
	static String koreanOrUnknown(PillarElement pillar) {
		return pillar != null ? pillar.getKorean() : "?";
	}

	/**
	 * 값이 없을 때 "?" 로 대체한다. 프롬프트에서 빈칸 대신 쓰던 표기를 한 곳으로 모은 것이다.
	 */
	static String orUnknown(String value) {
		return value != null ? value : "?";
	}

	/**
	 * 기둥의 12운성. 기둥이나 값이 없으면 기존 출력과 같게 "-" 를 쓴다.
	 */
	static String unseongOrDash(PillarElement pillar) {
		return pillar != null && pillar.getUnseong() != null ? pillar.getUnseong() : "-";
	}

	/**
	 * 값이 없을 때 "-" 로 대체한다. 12운성·12신살처럼 줄표를 쓰던 자리에 쓴다.
	 */
	static String orDash(String value) {
		return value != null ? value : "-";
	}

	/**
	 * 지지 하나의 지장간을 "- 년지(자): 임수(비견,30%), 계수(겁재,70%)" 처럼 한 줄로 적는다. 빈 칸은 건너뛰고, 칸 안의 빈 값은 "?" 로
	 * 적는다.
	 */
	static void appendJijangganLine(StringBuilder prompt, String pillarName,
		PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return;
		}
		prompt.append("""
			- %s(%s):\s\
			"""
			.formatted(pillarName, pillar.getKorean()));

		List<String> jijangganElements = new ArrayList<>();
		for (JijangganElement element : presentElements(pillar.getJijanggan())) {
			jijangganElements.add(formatJijangganElement(element));
		}

		prompt.append(String.join(", ", jijangganElements) + "\n");
	}

	/**
	 * 지장간 한 칸을 "임수(비견,30%)" 처럼 적는다. 한글 이름·오행·십성·비율 중 빈 값은 "?" 로 적어 프롬프트에 "null" 이 나가지 않게 한다.
	 */
	private static String formatJijangganElement(JijangganElement element) {
		String rate = element.getRate() != null ? element.getRate() + "%" : "?";
		return "%s%s(%s,%s)".formatted(
			orUnknown(element.getKorean()),
			orUnknown(element.getFiveCircle()),
			orUnknown(element.getTenStar()),
			rate);
	}

	/**
	 * 지장간 세 칸 중 값이 있는 칸만 first·second·third 순서로 돌려준다. 지지마다 지장간이 한 개에서 세 개라 빈 칸이 있다.
	 */
	private static List<JijangganElement> presentElements(JijangganInfo jijanggan) {
		return Stream.of(jijanggan.getFirst(), jijanggan.getSecond(), jijanggan.getThird())
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * 천간·지지는 1점씩, 지장간은 비율(rate / 100)만큼 오행 점수를 센다. 십성도 함께 센다.
	 */
	static ElementDistribution calculateDistributionWithJijanggan(
		ManseryeokCalculationResponse.SajuInfo saju) {
		EnumMap<FiveElement, Double> ohaengCounts = new EnumMap<>(FiveElement.class);
		for (FiveElement element : FiveElement.values()) {
			ohaengCounts.put(element, 0.0);
		}
		Map<String, Integer> sipseongCounts = new HashMap<>();

		addElementCount(ohaengCounts, sipseongCounts, saju.getYearSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getMonthSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getDaySky(), 1.0);
		if (saju.getTimeSky() != null) {
			addElementCount(ohaengCounts, sipseongCounts, saju.getTimeSky(), 1.0);
		}

		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getYearGround());
		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getMonthGround());
		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getDayGround());
		if (saju.getTimeGround() != null) {
			addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getTimeGround());
		}
		return new ElementDistribution(ohaengCounts, sipseongCounts);
	}

	private static void addElementCount(Map<FiveElement, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts,
		PillarElement element, double weight) {
		if (element == null) {
			return;
		}
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null) {
			ohaengCounts.merge(FiveElement.of(ohaeng), weight, Double::sum);
		}
		String sipseong = element.getTenStar();
		if (sipseong != null) {
			sipseongCounts.compute(sipseong, (k, v) -> (v == null ? 0 : v) + 1);
		}
	}

	private static void addGroundWithJijanggan(Map<FiveElement, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, PillarElement ground) {

		if (ground == null) {
			return;
		}

		// 지지 자체의 오행과 십성 (1점)
		addElementCount(ohaengCounts, sipseongCounts, ground, 1.0);

		// 지장간 계산 (가중치 적용)
		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			for (JijangganElement element : presentElements(jijanggan)) {
				addJijangganElement(ohaengCounts, sipseongCounts, element);
			}
		}
	}

	private static void addJijangganElement(Map<FiveElement, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, JijangganElement element) {

		// 오행 카운팅 (기존)
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			ohaengCounts.merge(FiveElement.of(ohaeng), weight, Double::sum);
		}

		// 십성 카운팅
		String tenStar = element.getTenStar();
		if (tenStar != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			// 비율을 반올림해 정수로 센다. 0.5 미만은 0 이 되므로 rate 50 이상인 지장간만 1개로 센다.
			// 만세력 계산의 지장간 rate 는 한 지지 합이 30 이라, 실제 계산 결과에서는 여기에 닿는 지장간이 없다
			int intWeight = (int) Math.round(weight);
			if (intWeight > 0) {
				sipseongCounts.compute(tenStar, (k, v) -> (v == null ? 0 : v) + intWeight);
			}
		}
	}
}
