package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * 지장간과 오행 분포를 다루는 조각.
 */
final class SajuElementSections {

	private SajuElementSections() {
	}

	static void appendJijangganDetail(StringBuilder prompt, String pillarName,
		PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return;
		}
		JijangganInfo jijanggan = pillar.getJijanggan();
		prompt.append(String.format("- %s(%s): ", pillarName, pillar.getKorean()));

		List<String> jijangganElements = new ArrayList<>();

		if (jijanggan.getFirst() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getFirst().getKorean(),
				jijanggan.getFirst().getFiveCircle(),
				jijanggan.getFirst().getTenStar() != null ? jijanggan.getFirst().getTenStar() : "?",
				// ⭐ 십성 추가
				jijanggan.getFirst().getRate()));
		}
		if (jijanggan.getSecond() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getSecond().getKorean(),
				jijanggan.getSecond().getFiveCircle(),
				jijanggan.getSecond().getTenStar() != null ? jijanggan.getSecond().getTenStar()
					: "?",  // ⭐ 십성 추가
				jijanggan.getSecond().getRate()));
		}
		if (jijanggan.getThird() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getThird().getKorean(),
				jijanggan.getThird().getFiveCircle(),
				jijanggan.getThird().getTenStar() != null ? jijanggan.getThird().getTenStar() : "?",
				// ⭐ 십성 추가
				jijanggan.getThird().getRate()));
		}

		prompt.append(String.join(", ", jijangganElements) + "\n");
	}

	static void appendJijangganDetailSimple(StringBuilder prompt, PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			prompt.append("정보 없음");
			return;
		}

		JijangganInfo ji = pillar.getJijanggan();
		List<String> elements = new ArrayList<>();

		if (ji.getFirst() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getFirst().getKorean() != null ? ji.getFirst().getKorean() : "?",
				ji.getFirst().getFiveCircle() != null ? ji.getFirst().getFiveCircle() : "?",
				ji.getFirst().getTenStar() != null ? ji.getFirst().getTenStar() : "?",
				ji.getFirst().getRate() != null ? ji.getFirst().getRate() : 0));
		}
		if (ji.getSecond() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getSecond().getKorean() != null ? ji.getSecond().getKorean() : "?",
				ji.getSecond().getFiveCircle() != null ? ji.getSecond().getFiveCircle() : "?",
				ji.getSecond().getTenStar() != null ? ji.getSecond().getTenStar() : "?",
				ji.getSecond().getRate() != null ? ji.getSecond().getRate() : 0));
		}
		if (ji.getThird() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getThird().getKorean() != null ? ji.getThird().getKorean() : "?",
				ji.getThird().getFiveCircle() != null ? ji.getThird().getFiveCircle() : "?",
				ji.getThird().getTenStar() != null ? ji.getThird().getTenStar() : "?",
				ji.getThird().getRate() != null ? ji.getThird().getRate() : 0));
		}

		prompt.append(String.join(", ", elements));
	}

	static String getJijangganSummary(PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return "?";
		}
		JijangganInfo ji = pillar.getJijanggan();

		List<String> elements = new ArrayList<>();
		if (ji.getFirst() != null && ji.getFirst().getKorean() != null
			&& ji.getFirst().getFiveCircle() != null && ji.getFirst().getRate() != null) {
			elements.add(ji.getFirst().getKorean() + ji.getFirst().getFiveCircle()
				+ "(" + ji.getFirst().getRate() + "%)");
		}
		if (ji.getSecond() != null && ji.getSecond().getKorean() != null
			&& ji.getSecond().getFiveCircle() != null && ji.getSecond().getRate() != null) {
			elements.add(ji.getSecond().getKorean() + ji.getSecond().getFiveCircle()
				+ "(" + ji.getSecond().getRate() + "%)");
		}
		if (ji.getThird() != null && ji.getThird().getKorean() != null
			&& ji.getThird().getFiveCircle() != null && ji.getThird().getRate() != null) {
			elements.add(ji.getThird().getKorean() + ji.getThird().getFiveCircle()
				+ "(" + ji.getThird().getRate() + "%)");
		}

		return elements.isEmpty() ? "?" : String.join(", ", elements);
	}

	static void calculateDistributionWithJijanggan(
		ManseryeokCalculationResponse.SajuInfo saju,
		Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts
	) {
		ohaengCounts.put("목", 0.0);
		ohaengCounts.put("화", 0.0);
		ohaengCounts.put("토", 0.0);
		ohaengCounts.put("금", 0.0);
		ohaengCounts.put("수", 0.0);

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
	}

	static void addElementCount(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts,
		PillarElement element, double weight) {
		if (element == null) {
			return;
		}
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null) {
			ohaengCounts.compute(ohaeng, (k, v) -> (v == null ? 0 : v) + weight);
		}
		String sipseong = element.getTenStar();
		if (sipseong != null) {
			sipseongCounts.compute(sipseong, (k, v) -> (v == null ? 0 : v) + 1);
		}
	}

	static void addGroundWithJijanggan(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, PillarElement ground) {

		if (ground == null) {
			return;
		}

		// 지지 자체의 오행과 십성 (1점)
		addElementCount(ohaengCounts, sipseongCounts, ground, 1.0);

		// 지장간 계산 (가중치 적용)
		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			if (jijanggan.getFirst() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts,
					jijanggan.getFirst());  // ⭐ 십성 카운트 추가
			}
			if (jijanggan.getSecond() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts, jijanggan.getSecond());
			}
			if (jijanggan.getThird() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts, jijanggan.getThird());
			}
		}
	}

	static void addJijangganElement(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, JijangganElement element) {

		// 오행 카운팅 (기존)
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			ohaengCounts.compute(ohaeng, (k, v) -> (v == null ? 0 : v) + weight);
		}

		// ⭐ 십성 카운팅 (추가)
		String tenStar = element.getTenStar();
		if (tenStar != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			// 반올림하여 정수로 카운트 (0.1개 이상이면 카운팅)
			int intWeight = (int) Math.round(weight);
			if (intWeight > 0) {
				sipseongCounts.compute(tenStar, (k, v) -> (v == null ? 0 : v) + intWeight);
			}
		}
	}
}
