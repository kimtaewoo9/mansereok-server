package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 사주 원국·월운·대운을 프롬프트용 텍스트로 옮기는 상세 데이터 블록.
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

		prompt.append("""
			### ⚠️ [매우 중요] 일간 확인 ###
			**%s님의 일간(日干)은 %s%s입니다.**
			절대로 다른 천간(戊, 丁, 丙 등)과 혼동하지 마세요.
			모든 분석은 반드시 이 일간을 기준으로 작성해야 합니다.

			"""
			.formatted(name, saju.getDaySky().getKorean(), saju.getDaySky().getFiveCircle()));

		// 1. 기본 정보
		// 이름은 사용자 입력이라 줄 첫머리에 그대로 두지 않는다. 라벨과 따옴표로 값임을 못박는다.
		prompt.append("""
			### 기본 정보 ###
			이름: '%s' | %s | %s %s | 현재 %d년

			"""
			.formatted(
				name,
				"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
				input.getSolarDate(),
				input.getSolarTime(),
				targetYear));

		// 2. 사주 팔자
		prompt.append("""
			### 사주팔자 ###
			년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s

			"""
			.formatted(
				saju.getYearSky().getKorean(),
				saju.getYearGround().getKorean(),
				saju.getMonthSky().getKorean(),
				saju.getMonthGround().getKorean(),
				saju.getDaySky().getKorean(),
				saju.getDayGround().getKorean(),
				SajuElementSections.koreanOrUnknown(saju.getTimeSky()),
				SajuElementSections.koreanOrUnknown(saju.getTimeGround())));

		// 3. 일간 정보
		prompt.append("""
			### 일간 ###
			%s%s (%s) - 본질적 성향의 뿌리

			"""
			.formatted(saju.getDaySky().getKorean(), saju.getDaySky().getFiveCircle(), saju.getDaySky().getTenStar()));

		// 4. 오행, 십성
		prompt.append("""
			**오행 분포(점수)**
			""");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		SajuElementSections.calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
		ohaengCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %.1f\n", key, value)));
		prompt.append("\n");

		prompt.append("""
			**십성 분포(개수)**
			""");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d\n", key, value)));
		prompt.append("\n");

		// 5. 12운성
		prompt.append("""
			**12운성 (에너지 리듬)**
			- 년주: %s | 월주: %s | 일주: %s | 시주: %s

			"""
			.formatted(
				SajuElementSections.orDash(saju.getYearGround().getUnseong()),
				SajuElementSections.orDash(saju.getMonthGround().getUnseong()),
				SajuElementSections.orDash(saju.getDayGround().getUnseong()),
				SajuElementSections.unseongOrDash(saju.getTimeGround())));

		prompt.append("""
			**지장간 (숨겨진 DNA)**
			""");
		SajuElementSections.appendJijangganLine(prompt, "년지", saju.getYearGround());
		SajuElementSections.appendJijangganLine(prompt, "월지", saju.getMonthGround());
		SajuElementSections.appendJijangganLine(prompt, "일지", saju.getDayGround());
		if (saju.getTimeGround() != null) {
			SajuElementSections.appendJijangganLine(prompt, "시지", saju.getTimeGround());
		}
		prompt.append("\n");

		// 7. 신살
		prompt.append("""
			### 신살 ###
			""");
		SajuKeywordSections.appendSinsalFull(prompt, saju);
		prompt.append("\n");

		// 8. 대운
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
			DaewoonSections.appendDaewoonPeriods(prompt, saju, input.getGender(), birthYear, targetYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append("""
				시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)
				※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.
				"""
				.formatted(
					saju.getBigFortuneNumberMin(),
					saju.getBigFortuneNumberMax(),
					DaewoonSections.getDaewoonDirection(saju, input.getGender())));
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

		if (saju.getMonthlyFortunes() != null && !saju.getMonthlyFortunes().isEmpty()) {
			prompt.append("""
				### 월운 (향후 12개월) ###
				""");
			saju.getMonthlyFortunes().forEach(monthly -> {
				String monthSky =
					SajuElementSections.koreanOrUnknown(monthly.getMonthSky());
				String monthGround =
					SajuElementSections.koreanOrUnknown(monthly.getMonthGround());
				String monthSkyTenStar =
					monthly.getMonthSky() != null ? monthly.getMonthSky().getTenStar() : "?";
				String monthGroundTenStar = monthly.getMonthGround() != null
					? monthly.getMonthGround().getTenStar() : "?";
				String season = SajuElementSections.orDash(monthly.getSeason());
				String periodStart = formatMonthPeriod(monthly.getPeriodStart());
				String periodEnd = monthly.getPeriodEnd() != null
					? formatMonthPeriod(monthly.getPeriodEnd())
					: "다음 절입 직전";

				prompt.append("""
					- %d년 %d월(%s): %s%s (천간십성:%s, 지지십성:%s) | 적용구간:%s ~ %s
					"""
					.formatted(
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
		prompt.append("""
			### 지지 관계성 (합/충/원진) ###
			""");
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			saju.getGroundRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("""
				- 특이사항 없음
				""");
		}

		// 10. 천간 관계 (업그레이드 버전)
		prompt.append("""
			### 천간 관계 (정신적 조화) ###
			""");
		if (saju.getSkyRelations() != null && !saju.getSkyRelations().isEmpty()) {
			saju.getSkyRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("""
				- 특이사항 없음
				""");
		}

		// 11. 삼합
		if (saju.getSamhap() != null && !saju.getSamhap().isEmpty()) {
			prompt.append("""
				### 특수 국(局) ###
				""");
			prompt.append("- " + String.join(", ", saju.getSamhap()) + "\n");
		}
		prompt.append("\n");

		// 12. 사주 강약 및 용신 (핵심 업그레이드)
		prompt.append("""
			### 사주 강약 및 용신 (핵심) ###
			""");
		if (saju.getYongsinInfo() != null) {
			prompt.append("""
				- 강약 판단: %s (내 세력 %.1f vs 남의 세력 %.1f)
				- 적용 규칙: %s (%s)
				- 추천 용신: %s (%s)
				※ 이 용신 정보를 바탕으로 사용자에게 행운의 조언을 해주세요.
				"""
				.formatted(
					saju.getYongsinInfo().getStrength(),
					saju.getYongsinInfo().getMyScore(),
					(saju.getYongsinInfo().getTotalScore() - saju.getYongsinInfo().getMyScore()),
					saju.getYongsinInfo().getAppliedRuleName(),
					saju.getYongsinInfo().getAppliedRuleCode(),
					saju.getYongsinInfo().getYongsin(),
					saju.getYongsinInfo().getDescription()));
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

		prompt.append("""
			- 생년월일: %s %s (양력/음력 구분: %s)
			- 성별: %s
			"""
			.formatted(input.getSolarDate(), input.getSolarTime(), input.getIsLunar() ? "음력" : "양력", input.getGender()));

		// 사주팔자 (천간/지지/십성/오행)
		prompt.append("""
			- 사주팔자:
			""");
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

	private static void appendPillarLine(StringBuilder prompt, String label,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append("""
				  %s: (정보 없음)
				"""
				.formatted(label));
			return;
		}

		prompt.append("""
			  %s: %s%s (천간:%s/오행:%s, 지지:%s/오행:%s)
			"""
			.formatted(
				label,
				SajuElementSections.orUnknown(sky.getChinese()),
				SajuElementSections.orUnknown(ground.getChinese()),
				SajuElementSections.orUnknown(sky.getTenStar()),
				SajuElementSections.orUnknown(sky.getFiveCircle()),
				SajuElementSections.orUnknown(ground.getTenStar()),
				SajuElementSections.orUnknown(ground.getFiveCircle())));
	}

	/**
	 * 프롬프트에 상세한 사주 기둥(Pillar) 정보를 추가하는 헬퍼 메서드 [FIXED] JijangganElement에서 .getTenStar() 호출을 제거하여
	 * DTO와 일치시킴
	 */
	private static void appendDetailedPillarInfo(StringBuilder prompt, String pillarName, String meaning,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append("""
				- **%s**: (정보 없음)
				"""
				.formatted(pillarName));
			return;
		}

		prompt.append("""
			- **%s (%s)**: %s%s
			"""
			.formatted(
				pillarName,
				meaning,
				SajuElementSections.orUnknown(sky.getKorean()),
				SajuElementSections.orUnknown(ground.getKorean())));

		prompt.append("""
			  - 천간: %s%s (십성: %s)
			"""
			.formatted(
				SajuElementSections.orUnknown(sky.getKorean()),
				SajuElementSections.orUnknown(sky.getFiveCircle()),
				SajuElementSections.orUnknown(sky.getTenStar())));

		prompt.append("""
			  - 지지: %s%s (십성: %s)
			"""
			.formatted(
				SajuElementSections.orUnknown(ground.getKorean()),
				SajuElementSections.orUnknown(ground.getFiveCircle()),
				SajuElementSections.orUnknown(ground.getTenStar())));

		if (ground.getUnseong() != null) {
			prompt.append("""
				  - 12운성: %s
				"""
				.formatted(ground.getUnseong()));
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			prompt.append("""
				  - 지장간:
				""");
			if (jijanggan.getFirst() != null) {
				prompt.append("""
					    - 초기(%d%%): %s%s (십성: %s)
					"""
					.formatted(
						// ⭐ 십성 추가 jijanggan.getFirst().getRate() != null ? jijanggan.getFirst().getRate() : 0,
						SajuElementSections.orUnknown(jijanggan.getFirst().getKorean()),
						SajuElementSections.orUnknown(jijanggan.getFirst().getFiveCircle()),
						SajuElementSections.orUnknown(jijanggan.getFirst().getTenStar())));
			}
			if (jijanggan.getSecond() != null) {
				prompt.append("""
					    - 중기(%d%%): %s%s (십성: %s)
					"""
					.formatted(
						jijanggan.getSecond().getRate() != null ? jijanggan.getSecond().getRate() : 0,
						SajuElementSections.orUnknown(jijanggan.getSecond().getKorean()),
						SajuElementSections.orUnknown(jijanggan.getSecond().getFiveCircle()),
						SajuElementSections.orUnknown(jijanggan.getSecond().getTenStar())));
			}
			if (jijanggan.getThird() != null) {
				prompt.append("""
					    - 말기(%d%%): %s%s (십성: %s)
					"""
					.formatted(
						jijanggan.getThird().getRate() != null ? jijanggan.getThird().getRate() : 0,
						SajuElementSections.orUnknown(jijanggan.getThird().getKorean()),
						SajuElementSections.orUnknown(jijanggan.getThird().getFiveCircle()),
						SajuElementSections.orUnknown(jijanggan.getThird().getTenStar())));
			}
		}
	}

	private static String formatMonthPeriod(LocalDateTime value) {
		if (value == null) {
			return "-";
		}
		return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
	}
}
