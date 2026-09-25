package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	static void appendJijangganLine(StringBuilder prompt, String pillarName,
		PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return;
		}
		JijangganInfo jijanggan = pillar.getJijanggan();
		prompt.append("""
			- %s(%s):\s\
			"""
			.formatted(pillarName, pillar.getKorean()));

		List<String> jijangganElements = new ArrayList<>();

		if (jijanggan.getFirst() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getFirst().getKorean(),
				jijanggan.getFirst().getFiveCircle(),
				orUnknown(jijanggan.getFirst().getTenStar()),
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
				orUnknown(jijanggan.getThird().getTenStar()),
				// ⭐ 십성 추가
				jijanggan.getThird().getRate()));
		}

		prompt.append(String.join(", ", jijangganElements) + "\n");
	}

	static void appendJijangganInline(StringBuilder prompt, PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			prompt.append("정보 없음");
			return;
		}

		JijangganInfo ji = pillar.getJijanggan();
		List<String> elements = new ArrayList<>();

		if (ji.getFirst() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				orUnknown(ji.getFirst().getKorean()),
				orUnknown(ji.getFirst().getFiveCircle()),
				orUnknown(ji.getFirst().getTenStar()),
				ji.getFirst().getRate() != null ? ji.getFirst().getRate() : 0));
		}
		if (ji.getSecond() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				orUnknown(ji.getSecond().getKorean()),
				orUnknown(ji.getSecond().getFiveCircle()),
				orUnknown(ji.getSecond().getTenStar()),
				ji.getSecond().getRate() != null ? ji.getSecond().getRate() : 0));
		}
		if (ji.getThird() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				orUnknown(ji.getThird().getKorean()),
				orUnknown(ji.getThird().getFiveCircle()),
				orUnknown(ji.getThird().getTenStar()),
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

	private static void addElementCount(Map<String, Double> ohaengCounts,
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

	private static void addGroundWithJijanggan(Map<String, Double> ohaengCounts,
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

	private static void addJijangganElement(Map<String, Double> ohaengCounts,
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
