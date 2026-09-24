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
 * 사주 원국·월운·대운을 프롬프트용 텍스트로 옮기는 조각. 인물 한 명의 데이터 블록을 만든다.
 */
final class SajuProfileSections {

	private SajuProfileSections() {
	}

	// ==================== 공통 유틸리티 메서드 (기존 유지) ====================
	static void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response) {
		appendPersonDetailInfo(prompt, name, response, null);
	}

	static void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response, Integer referenceYear) {
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();
		int targetYear =
			referenceYear != null ? referenceYear : java.time.LocalDate.now().getYear();

		prompt.append("### ⚠️ [매우 중요] 일간 확인 ###\n");
		prompt.append(String.format("**%s님의 일간(日干)은 %s%s입니다.**\n",
			name,
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle()));
		prompt.append("절대로 다른 천간(戊, 丁, 丙 등)과 혼동하지 마세요.\n");
		prompt.append("모든 분석은 반드시 이 일간을 기준으로 작성해야 합니다.\n\n");

		// 1. 기본 정보
		// 이름은 사용자 입력이라 줄 첫머리에 그대로 두지 않는다. 라벨과 따옴표로 값임을 못박는다.
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("이름: '%s' | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			targetYear));

		// 2. 사주 팔자
		prompt.append("### 사주팔자 ###\n");
		prompt.append(String.format("년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s\n\n",
			saju.getYearSky().getKorean(), saju.getYearGround().getKorean(),
			saju.getMonthSky().getKorean(), saju.getMonthGround().getKorean(),
			saju.getDaySky().getKorean(), saju.getDayGround().getKorean(),
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"));

		// 3. 일간 정보
		prompt.append("### 일간 ###\n");
		prompt.append(String.format("%s%s (%s) - 본질적 성향의 뿌리\n\n",
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle(),
			saju.getDaySky().getTenStar()));

		// 4. 오행, 십성
		prompt.append("**오행 분포(점수)**\n");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		SajuElementSections.calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
		ohaengCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %.1f\n", key, value)));
		prompt.append("\n");

		prompt.append("**십성 분포(개수)**\n");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d\n", key, value)));
		prompt.append("\n");

		// 5. 12운성
		prompt.append("**12운성 (에너지 리듬)**\n");
		prompt.append(String.format("- 년주: %s | 월주: %s | 일주: %s | 시주: %s\n\n",
			saju.getYearGround().getUnseong() != null ? saju.getYearGround().getUnseong() : "-",
			saju.getMonthGround().getUnseong() != null ? saju.getMonthGround().getUnseong() : "-",
			saju.getDayGround().getUnseong() != null ? saju.getDayGround().getUnseong() : "-",
			saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null ?
				saju.getTimeGround().getUnseong() : "-"));

		prompt.append("**지장간 (숨겨진 DNA)**\n");
		SajuElementSections.appendJijangganDetail(prompt, "년지", saju.getYearGround());
		SajuElementSections.appendJijangganDetail(prompt, "월지", saju.getMonthGround());
		SajuElementSections.appendJijangganDetail(prompt, "일지", saju.getDayGround());
		if (saju.getTimeGround() != null) {
			SajuElementSections.appendJijangganDetail(prompt, "시지", saju.getTimeGround());
		}
		prompt.append("\n");

		// 7. 신살
		prompt.append("### 신살 ###\n");
		SajuKeywordSections.appendSinsalFull(prompt, saju);
		prompt.append("\n");

		// 8. 대운
		prompt.append("### 대운 ###\n");
		if (saju.getBigFortuneNumber() != null) {
			prompt.append(String.format("시작:%d세 | 방향:%s\n",
				saju.getBigFortuneNumber(),
				DaewoonSections.getDaewoonDirection(saju, input.getGender())));

			// [수정] birthYear 전달!
			int birthYear = input.getSolarDate().getYear();
			DaewoonSections.appendDaewoonSimple(prompt, saju, input.getGender(), birthYear, targetYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append(String.format("시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)\n",
				saju.getBigFortuneNumberMin(),
				saju.getBigFortuneNumberMax(),
				DaewoonSections.getDaewoonDirection(saju, input.getGender())));
			prompt.append("※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.\n");
		} else {
			prompt.append("대운 정보 없음\n");
		}
		if (saju.getUncertaintyNotes() != null && !saju.getUncertaintyNotes().isEmpty()) {
			saju.getUncertaintyNotes().forEach(note -> prompt.append("- " + note + "\n"));
		}
		prompt.append("※ 대운은 위 계산 결과를 절대 재계산/수정하지 말고 그대로 분석에 사용하세요.\n");
		prompt.append("\n");

		if (saju.getMonthlyFortunes() != null && !saju.getMonthlyFortunes().isEmpty()) {
			prompt.append("### 월운 (향후 12개월) ###\n");
			saju.getMonthlyFortunes().forEach(monthly -> {
				String monthSky =
					monthly.getMonthSky() != null ? monthly.getMonthSky().getKorean() : "?";
				String monthGround =
					monthly.getMonthGround() != null ? monthly.getMonthGround().getKorean() : "?";
				String monthSkyTenStar =
					monthly.getMonthSky() != null ? monthly.getMonthSky().getTenStar() : "?";
				String monthGroundTenStar = monthly.getMonthGround() != null
					? monthly.getMonthGround().getTenStar() : "?";
				String season = monthly.getSeason() != null ? monthly.getSeason() : "-";
				String periodStart = formatMonthPeriod(monthly.getPeriodStart());
				String periodEnd = monthly.getPeriodEnd() != null
					? formatMonthPeriod(monthly.getPeriodEnd())
					: "다음 절입 직전";

				prompt.append(String.format(
					"- %d년 %d월(%s): %s%s (천간십성:%s, 지지십성:%s) | 적용구간:%s ~ %s\n",
					monthly.getYear(),
					monthly.getMonth(),
					season,
					monthSky,
					monthGround,
					monthSkyTenStar,
					monthGroundTenStar,
					periodStart,
					periodEnd));
			});
			prompt.append("\n");
		}

		// 9. 관계성 분석 (업그레이드 버전)
		prompt.append("### 지지 관계성 (합/충/원진) ###\n");
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			saju.getGroundRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("- 특이사항 없음\n");
		}

		// 10. 천간 관계 (업그레이드 버전)
		prompt.append("### 천간 관계 (정신적 조화) ###\n");
		if (saju.getSkyRelations() != null && !saju.getSkyRelations().isEmpty()) {
			saju.getSkyRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("- 특이사항 없음\n");
		}

		// 11. 삼합
		if (saju.getSamhap() != null && !saju.getSamhap().isEmpty()) {
			prompt.append("### 특수 국(局) ###\n");
			prompt.append("- " + String.join(", ", saju.getSamhap()) + "\n");
		}
		prompt.append("\n");

		// 12. 사주 강약 및 용신 (핵심 업그레이드)
		prompt.append("### 사주 강약 및 용신 (핵심) ###\n");
		if (saju.getYongsinInfo() != null) {
			prompt.append(String.format("- 강약 판단: %s (내 세력 %.1f vs 남의 세력 %.1f)\n",
				saju.getYongsinInfo().getStrength(),
				saju.getYongsinInfo().getMyScore(),
				(saju.getYongsinInfo().getTotalScore() - saju.getYongsinInfo().getMyScore())
			));
			prompt.append(String.format("- 적용 규칙: %s (%s)\n",
				saju.getYongsinInfo().getAppliedRuleName(),
				saju.getYongsinInfo().getAppliedRuleCode()));
			prompt.append(String.format("- 추천 용신: %s (%s)\n",
				saju.getYongsinInfo().getYongsin(),
				saju.getYongsinInfo().getDescription()));
			prompt.append("※ 이 용신 정보를 바탕으로 사용자에게 행운의 조언을 해주세요.\n");
		}
		prompt.append("\n");
	}

	/**
	 * ManseryeokCalculationResponse 정보를 프롬프트 포맷으로 변환
	 */
	static void appendPersonCalculationInfo(StringBuilder prompt,
		ManseryeokCalculationResponse response) {
		ManseryeokCalculationResponse.InputInfo input = response.getInput();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		prompt.append(String.format("- 생년월일: %s %s (양력/음력 구분: %s)\n",
			input.getSolarDate(), input.getSolarTime(), input.getIsLunar() ? "음력" : "양력"));
		prompt.append(String.format("- 성별: %s\n", input.getGender()));

		// 사주팔자 (천간/지지/십성/오행)
		prompt.append("- 사주팔자:\n");
		appendPillarLine(prompt, "년주", saju.getYearSky(), saju.getYearGround());
		appendPillarLine(prompt, "월주", saju.getMonthSky(), saju.getMonthGround());
		appendPillarLine(prompt, "일주", saju.getDaySky(), saju.getDayGround());
		appendPillarLine(prompt, "시주", saju.getTimeSky(), saju.getTimeGround());

		// 관계 정보 (합, 충, 원진 등) - 재회운에서 중요
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			prompt.append("- 지지 관계(합/충/형): ").append(String.join(", ", saju.getGroundRelations()))
				.append("\n");
		}
		if (saju.getSkyRelations() != null && !saju.getSkyRelations().isEmpty()) {
			prompt.append("- 천간 관계(합/충): ").append(String.join(", ", saju.getSkyRelations()))
				.append("\n");
		}

		// 신살 정보
		if (saju.getSinsalInfo() != null) {
			prompt.append("- 주요 신살: ");
			saju.getSinsalInfo().values().forEach(list ->
				prompt.append(String.join(", ", list)).append(" ")
			);
			prompt.append("\n");
		}
	}

	static void appendPillarLine(StringBuilder prompt, String label,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append(String.format("  %s: (정보 없음)\n", label));
			return;
		}

		prompt.append(String.format("  %s: %s%s (천간:%s/오행:%s, 지지:%s/오행:%s)\n",
			label,
			sky.getChinese() != null ? sky.getChinese() : "?",
			ground.getChinese() != null ? ground.getChinese() : "?",
			sky.getTenStar() != null ? sky.getTenStar() : "?",
			sky.getFiveCircle() != null ? sky.getFiveCircle() : "?",
			ground.getTenStar() != null ? ground.getTenStar() : "?",
			ground.getFiveCircle() != null ? ground.getFiveCircle() : "?"
		));
	}

	/**
	 * 프롬프트에 상세한 사주 기둥(Pillar) 정보를 추가하는 헬퍼 메서드 [FIXED] JijangganElement에서 .getTenStar() 호출을 제거하여
	 * DTO와 일치시킴
	 */
	static void appendDetailedPillarInfo(StringBuilder prompt, String pillarName, String meaning,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append(String.format("- **%s**: (정보 없음)\n", pillarName));
			return;
		}

		prompt.append(String.format("- **%s (%s)**: %s%s\n", pillarName, meaning,
			sky.getKorean() != null ? sky.getKorean() : "?",
			ground.getKorean() != null ? ground.getKorean() : "?"));

		prompt.append(String.format("  - 천간: %s%s (십성: %s)\n",
			sky.getKorean() != null ? sky.getKorean() : "?",
			sky.getFiveCircle() != null ? sky.getFiveCircle() : "?",
			sky.getTenStar() != null ? sky.getTenStar() : "?"));

		prompt.append(String.format("  - 지지: %s%s (십성: %s)\n",
			ground.getKorean() != null ? ground.getKorean() : "?",
			ground.getFiveCircle() != null ? ground.getFiveCircle() : "?",
			ground.getTenStar() != null ? ground.getTenStar() : "?"));

		if (ground.getUnseong() != null) {
			prompt.append(String.format("  - 12운성: %s\n", ground.getUnseong()));
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			prompt.append("  - 지장간:\n");
			if (jijanggan.getFirst() != null) {
				prompt.append(String.format("    - 초기(%d%%): %s%s (십성: %s)\n",  // ⭐ 십성 추가
					jijanggan.getFirst().getRate() != null ? jijanggan.getFirst().getRate() : 0,
					jijanggan.getFirst().getKorean() != null ? jijanggan.getFirst().getKorean()
						: "?",
					jijanggan.getFirst().getFiveCircle() != null ? jijanggan.getFirst()
						.getFiveCircle() : "?",
					jijanggan.getFirst().getTenStar() != null ? jijanggan.getFirst().getTenStar()
						: "?"));
			}
			if (jijanggan.getSecond() != null) {
				prompt.append(String.format("    - 중기(%d%%): %s%s (십성: %s)\n",
					jijanggan.getSecond().getRate() != null ? jijanggan.getSecond().getRate() : 0,
					jijanggan.getSecond().getKorean() != null ? jijanggan.getSecond().getKorean()
						: "?",
					jijanggan.getSecond().getFiveCircle() != null ? jijanggan.getSecond()
						.getFiveCircle() : "?",
					jijanggan.getSecond().getTenStar() != null ? jijanggan.getSecond().getTenStar()
						: "?"));
			}
			if (jijanggan.getThird() != null) {
				prompt.append(String.format("    - 말기(%d%%): %s%s (십성: %s)\n",
					jijanggan.getThird().getRate() != null ? jijanggan.getThird().getRate() : 0,
					jijanggan.getThird().getKorean() != null ? jijanggan.getThird().getKorean()
						: "?",
					jijanggan.getThird().getFiveCircle() != null ? jijanggan.getThird()
						.getFiveCircle() : "?",
					jijanggan.getThird().getTenStar() != null ? jijanggan.getThird().getTenStar()
						: "?"));
			}
		}
	}

	// 기본 궁합 프롬프트용 요약 정보 주입
	static void appendPersonInfoToPrompt(StringBuilder prompt, String name,
		ManseryeokCalculationResponse manseResponse) {

		if (manseResponse == null || manseResponse.getSaju() == null) {
			prompt.append(String.format("%s님 정보 로드 오류\n\n", name));
			return;
		}

		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();
		ManseryeokCalculationResponse.InputInfo input = manseResponse.getInput();
		int referenceYear = java.time.LocalDate.now().getYear();

		// ===== 1. 기본 정보 =====
		// 이름은 사용자 입력이라 줄 첫머리에 그대로 두지 않는다. 라벨과 따옴표로 값임을 못박는다.
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("이름: '%s' | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			referenceYear));

		// ===== 2. 사주팔자 =====
		prompt.append("### 사주팔자 ###\n");
		prompt.append(String.format("년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s\n\n",
			saju.getYearSky() != null ? saju.getYearSky().getKorean() : "?",
			saju.getYearGround() != null ? saju.getYearGround().getKorean() : "?",
			saju.getMonthSky() != null ? saju.getMonthSky().getKorean() : "?",
			saju.getMonthGround() != null ? saju.getMonthGround().getKorean() : "?",
			saju.getDaySky() != null ? saju.getDaySky().getKorean() : "?",
			saju.getDayGround() != null ? saju.getDayGround().getKorean() : "?",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"));

		// ===== 3. 일간 정보 =====
		prompt.append("### 일간 ###\n");
		if (saju.getDaySky() != null) {
			prompt.append(String.format("%s%s (%s) - 본질적 성향의 뿌리\n\n",
				saju.getDaySky().getKorean() != null ? saju.getDaySky().getKorean() : "?",
				saju.getDaySky().getFiveCircle() != null ? saju.getDaySky().getFiveCircle() : "?",
				saju.getDaySky().getTenStar() != null ? saju.getDaySky().getTenStar() : "?"));
		} else {
			prompt.append("일간 정보 없음\n\n");
		}

		// ===== 4. 오행 분포 =====
		prompt.append("### 오행 분포 ###\n");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();

		if (saju.getYearSky() != null && saju.getMonthSky() != null && saju.getDaySky() != null) {
			SajuElementSections.calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

			String ilganOhaeng = saju.getDaySky() != null ? saju.getDaySky().getFiveCircle() : "";
			prompt.append(formatOhaengAsTable(ohaengCounts, ilganOhaeng));

			// ===== 5. 십성 분포 =====
			prompt.append("### 십성 분포 ###\n");
			prompt.append(formatSipseongAsTable(sipseongCounts));
		} else {
			prompt.append("오행/십성: 계산 불가 - 필수 정보 누락\n\n");
		}

		// ===== 6. 12운성 =====
		prompt.append("### 12운성 ###\n");
		prompt.append(String.format("년:%s 월:%s 일:%s 시:%s\n\n",
			saju.getYearGround() != null && saju.getYearGround().getUnseong() != null
				? saju.getYearGround().getUnseong() : "-",
			saju.getMonthGround() != null && saju.getMonthGround().getUnseong() != null
				? saju.getMonthGround().getUnseong() : "-",
			saju.getDayGround() != null && saju.getDayGround().getUnseong() != null
				? saju.getDayGround().getUnseong() : "-",
			saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null
				? saju.getTimeGround().getUnseong() : "-"));

		// ===== 7. 지장간 =====
		prompt.append("### 지장간 ###\n");
		prompt.append("일지(배우자궁): ");
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
		prompt.append("### 신살 ###\n");
		SajuKeywordSections.appendSinsalFull(prompt, saju);

		// ===== 9. 대운 (간략) =====
		prompt.append("### 대운 ###\n");
		if (saju.getBigFortuneNumber() != null) {
			prompt.append(String.format("시작:%d세 | 방향:%s\n",
				saju.getBigFortuneNumber(),
				DaewoonSections.getDaewoonDirection(saju, input.getGender())));

			// [수정] birthYear 전달!
			int birthYear = input.getSolarDate().getYear();
			DaewoonSections.appendDaewoonSimple(prompt, saju, input.getGender(), birthYear, referenceYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append(String.format("시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)\n",
				saju.getBigFortuneNumberMin(),
				saju.getBigFortuneNumberMax(),
				DaewoonSections.getDaewoonDirection(saju, input.getGender())));
			prompt.append("※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.\n");
		} else {
			prompt.append("대운 정보 없음\n");
		}
		if (saju.getUncertaintyNotes() != null && !saju.getUncertaintyNotes().isEmpty()) {
			saju.getUncertaintyNotes().forEach(note -> prompt.append("- " + note + "\n"));
		}
		prompt.append("※ 대운은 위 계산 결과를 절대 재계산/수정하지 말고 그대로 분석에 사용하세요.\n");
		prompt.append("\n");
	}

	static String formatSipseongAsTable(Map<String, Integer> sipseongCounts) {
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

	static String formatOhaengAsTable(Map<String, Double> ohaengCounts, String ilganOhaeng) {
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
		sb.append(" (★=일간)\n\n");

		return sb.toString();
	}

	static String formatMonthPeriod(LocalDateTime value) {
		if (value == null) {
			return "-";
		}
		return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
	}
}
