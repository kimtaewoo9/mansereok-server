package com.mansereok.server.domain.interpret.calculator;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class YongsinCalculator {

	// 오행의 상생 관계 (나를 돕는 오행)
	// 목<-수, 화<-목, 토<-화, 금<-토, 수<-금
	private static final Map<String, String> SUPPORT_MAP = Map.of(
		"목", "수", "화", "목", "토", "화", "금", "토", "수", "금"
	);

	// 오행의 극 관계 (나를 극하는 오행)
	private static final Map<String, String> ATTACK_MAP = Map.of(
		"목", "금", "화", "수", "토", "목", "금", "화", "수", "토"
	);

	// 오행의 설기 관계 (내가 생하는 오행 - 힘을 뺌)
	private static final Map<String, String> DRAIN_MAP = Map.of(
		"목", "화", "화", "토", "토", "금", "금", "수", "수", "목"
	);

	/**
	 * 용신 분석 결과 반환
	 */
	public YongsinResult analyzeYongsin(SajuInfo saju) {
		// 1. 일간(나)의 오행 확인
		String ilganOhaeng = saju.getDaySky().getFiveCircle(); // 예: "목"

		// 2. 전체 오행 점수 계산 (월지에 가중치 3.0, 일지/시지 1.5, 천간 1.0)
		Map<String, Double> scores = calculateOhaengScores(saju);

		// 3. 내 편(인성+비겁) vs 남의 편(식상+재성+관성) 세력 비교
		String resource = SUPPORT_MAP.get(ilganOhaeng); // 인성 (나를 생함)

		double myPower = scores.getOrDefault(ilganOhaeng, 0.0) + scores.getOrDefault(resource, 0.0);
		double totalPower = scores.values().stream().mapToDouble(Double::doubleValue).sum();

		// 4. 신강/신약 판단 (내 편이 45% 이상이면 신강으로 간주 - 약간의 조정 가능)
		boolean isSinGang = myPower >= (totalPower * 0.45);

		// 5. 용신(가장 필요한 오행) 찾기 (억부법 기준)
		String yongsin;
		String yongsinType; // 억부, 조후, 통관 등

		if (isSinGang) {
			// 신강하면: 힘을 빼야 함 (식상/재성/관성 중 가장 점수가 낮은 것 or 유력한 것)
			// 간단한 로직: 식상(설기) -> 재성(결과) -> 관성(통제) 순으로 고려
			String sik = DRAIN_MAP.get(ilganOhaeng); // 식상
			String gwan = ATTACK_MAP.get(
				ilganOhaeng); // 관성 (나를 극하는게 아니라 내가 극당하는거라 좀 다름, 여기선 편의상 억제자로 봄)
			// 정확히는 관성이 일간을 극하므로 신강할 때 씀.
			// 여기서는 단순화하여: 너무 강하면 '설기(식상)'하거나 '극(관성)'을 쓴다.

			// 식상이 너무 약하면 관성을 쓰고, 관성도 약하면 재성을 쓴다 등등 복잡하지만
			// 가장 일반적인 '설기(식상)'를 1순위로 둡니다.
			yongsin = sik;
			yongsinType = "설기용신(강한 기운을 표출)";
		} else {
			// 신약하면: 힘을 보태야 함 (인성/비겁)
			// 인성(나를 생함)을 최우선으로 봅니다.
			yongsin = resource;
			yongsinType = "부조용신(약한 기운을 보완)";
		}

		return new YongsinResult(
			isSinGang ? "신강(身强)" : "신약(身弱)",
			myPower,
			totalPower,
			yongsin,
			yongsinType
		);
	}

	private Map<String, Double> calculateOhaengScores(SajuInfo saju) {
		Map<String, Double> scores = new HashMap<>();
		// 초기화
		for (String o : new String[]{"목", "화", "토", "금", "수"}) {
			scores.put(o, 0.0);
		}

		// 가중치 정의
		// 월지: 3.0 (가장 중요, 계절)
		// 일지: 2.0
		// 년지/시지: 1.0
		// 천간: 1.0

		addScore(scores, saju.getYearSky(), 1.0);
		addScore(scores, saju.getYearGround(), 1.0);

		addScore(scores, saju.getMonthSky(), 1.0);
		addScore(scores, saju.getMonthGround(), 3.0); // 월지 가중치

		addScore(scores, saju.getDaySky(), 1.0); // 일간
		addScore(scores, saju.getDayGround(), 2.0); // 일지 가중치

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

	@lombok.Data
	@lombok.AllArgsConstructor
	public static class YongsinResult {

		private String strength; // 신강/신약
		private double myScore;
		private double totalScore;
		private String yongsin; // 용신 오행
		private String description; // 설명
	}
}
