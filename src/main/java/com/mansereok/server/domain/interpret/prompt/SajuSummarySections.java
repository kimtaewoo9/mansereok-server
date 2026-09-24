package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 궁합 프롬프트가 쓰는 인물 요약 블록. 십성·오행 분포를 표로 접어 넣는다.
 */
final class SajuSummarySections {

	private SajuSummarySections() {
	}

	// 기본 궁합 프롬프트용 요약 정보 주입
	static void appendPersonInfoToPrompt(StringBuilder prompt, String name,
		ManseryeokCalculationResponse manseResponse) {

		if (manseResponse == null || manseResponse.getSaju() == null) {
			prompt.append("""
				%s님 정보 로드 오류

				"""
				.formatted(name));
			return;
		}

		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();
		ManseryeokCalculationResponse.InputInfo input = manseResponse.getInput();
		int referenceYear = java.time.LocalDate.now().getYear();

		// ===== 1. 기본 정보 =====
		// 이름은 사용자 입력이라 줄 첫머리에 그대로 두지 않는다. 라벨과 따옴표로 값임을 못박는다.
		prompt.append("""
			### 기본 정보 ###
			이름: '%s' | %s | %s %s | 현재 %d년

			"""
			.formatted(name, "MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성", input.getSolarDate(), input.getSolarTime(), referenceYear));

		// ===== 2. 사주팔자 =====
		prompt.append("""
			### 사주팔자 ###
			년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s

			"""
			.formatted(saju.getYearSky() != null ? saju.getYearSky().getKorean() : "?", saju.getYearGround() != null ? saju.getYearGround().getKorean() : "?", saju.getMonthSky() != null ? saju.getMonthSky().getKorean() : "?", saju.getMonthGround() != null ? saju.getMonthGround().getKorean() : "?", saju.getDaySky() != null ? saju.getDaySky().getKorean() : "?", saju.getDayGround() != null ? saju.getDayGround().getKorean() : "?", saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?", saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"));

		// ===== 3. 일간 정보 =====
		prompt.append("""
			### 일간 ###
			""");
		if (saju.getDaySky() != null) {
			prompt.append("""
				%s%s (%s) - 본질적 성향의 뿌리

				"""
				.formatted(saju.getDaySky().getKorean() != null ? saju.getDaySky().getKorean() : "?", saju.getDaySky().getFiveCircle() != null ? saju.getDaySky().getFiveCircle() : "?", saju.getDaySky().getTenStar() != null ? saju.getDaySky().getTenStar() : "?"));
		} else {
			prompt.append("""
				일간 정보 없음

				""");
		}

		// ===== 4. 오행 분포 =====
		prompt.append("""
			### 오행 분포 ###
			""");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();

		if (saju.getYearSky() != null && saju.getMonthSky() != null && saju.getDaySky() != null) {
			SajuElementSections.calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

			String ilganOhaeng = saju.getDaySky() != null ? saju.getDaySky().getFiveCircle() : "";
			prompt.append(formatOhaengAsTable(ohaengCounts, ilganOhaeng));

			// ===== 5. 십성 분포 =====
			prompt.append("""
				### 십성 분포 ###
				""");
			prompt.append(formatSipseongAsTable(sipseongCounts));
		} else {
			prompt.append("""
				오행/십성: 계산 불가 - 필수 정보 누락

				""");
		}

		// ===== 6. 12운성 =====
		prompt.append("""
			### 12운성 ###
			년:%s 월:%s 일:%s 시:%s

			"""
			.formatted(saju.getYearGround() != null && saju.getYearGround().getUnseong() != null
				? saju.getYearGround().getUnseong() : "-", saju.getMonthGround() != null && saju.getMonthGround().getUnseong() != null
				? saju.getMonthGround().getUnseong() : "-", saju.getDayGround() != null && saju.getDayGround().getUnseong() != null
				? saju.getDayGround().getUnseong() : "-", saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null
				? saju.getTimeGround().getUnseong() : "-"));

		// ===== 7. 지장간 =====
		prompt.append("""
			### 지장간 ###
			일지(배우자궁):\s\
			""");
		if (saju.getDayGround() != null) {
			SajuElementSections.appendJijangganDetailSimple(prompt, saju.getDayGround());
		} else {
			prompt.append("정보 없음");
		}
		prompt.append("\n");

		prompt.append("년지: " + SajuElementSections.getJijangganSummary(saju.getYearGround()) + " | ");
		prompt.append("월지: " + SajuElementSections.getJijangganSummary(saju.getMonthGround()) + " | ");
		if (saju.getTimeGround() != null) {
			prompt.append("시지: " + SajuElementSections.getJijangganSummary(saju.getTimeGround()));
		}
		prompt.append("\n\n");

		// ===== 8. 신살 (전체) =====
		prompt.append("""
			### 신살 ###
			""");
		SajuKeywordSections.appendSinsalFull(prompt, saju);

		// ===== 9. 대운 (간략) =====
		prompt.append("""
			### 대운 ###
			""");
		if (saju.getBigFortuneNumber() != null) {
			prompt.append("""
				시작:%d세 | 방향:%s
				"""
				.formatted(saju.getBigFortuneNumber(), DaewoonSections.getDaewoonDirection(saju, input.getGender())));

			// [수정] birthYear 전달!
			int birthYear = input.getSolarDate().getYear();
			DaewoonSections.appendDaewoonSimple(prompt, saju, input.getGender(), birthYear, referenceYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append("""
				시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)
				※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.
				"""
				.formatted(saju.getBigFortuneNumberMin(), saju.getBigFortuneNumberMax(), DaewoonSections.getDaewoonDirection(saju, input.getGender())));
		} else {
			prompt.append("""
				대운 정보 없음
				""");
		}
		if (saju.getUncertaintyNotes() != null && !saju.getUncertaintyNotes().isEmpty()) {
			saju.getUncertaintyNotes().forEach(note -> prompt.append("- " + note + "\n"));
		}
		prompt.append("""
			※ 대운은 위 계산 결과를 절대 재계산/수정하지 말고 그대로 분석에 사용하세요.

			""");
	}

	private static String formatSipseongAsTable(Map<String, Integer> sipseongCounts) {
		List<String> sipseongOrder = Arrays.asList(
			"비견", "겁재", "식신", "상관", "편재",
			"정재", "편관", "정관", "편인", "정인"
		);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sipseongOrder.size(); i++) {
			String sipseong = sipseongOrder.get(i);
			int count = sipseongCounts.getOrDefault(sipseong, 0);
			sb.append(sipseong).append(":").append(count);

			if (i == 4) {
				sb.append("\n");
			} else if (i < sipseongOrder.size() - 1) {
				sb.append(" | ");
			}
		}
		sb.append("\n\n");
		return sb.toString();
	}

	private static String formatOhaengAsTable(Map<String, Double> ohaengCounts, String ilganOhaeng) {
		StringBuilder sb = new StringBuilder();
		sb.append("목:").append(String.format("%.1f", ohaengCounts.getOrDefault("목", 0.0)));
		if ("목".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("화:").append(String.format("%.1f", ohaengCounts.getOrDefault("화", 0.0)));
		if ("화".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("토:").append(String.format("%.1f", ohaengCounts.getOrDefault("토", 0.0)));
		if ("토".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("금:").append(String.format("%.1f", ohaengCounts.getOrDefault("금", 0.0)));
		if ("금".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("수:").append(String.format("%.1f", ohaengCounts.getOrDefault("수", 0.0)));
		if ("수".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append("""
			 (★=일간)

			""");

		return sb.toString();
	}
}
