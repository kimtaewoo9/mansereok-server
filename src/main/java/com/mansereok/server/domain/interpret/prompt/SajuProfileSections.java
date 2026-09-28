package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 사주 원국·월운·대운을 프롬프트용 텍스트로 옮기는 상세 데이터 블록.
 */
final class SajuProfileSections {

	/**
	 * 출생시간을 비워 보낸 사람의 시각 자리에 쓰는 문구. 출생시간은 요청에서 선택값이라
	 * 만세력 계산 결과의 solarTime 이 null 로 올 수 있다. 데이터 줄에만 쓰고 문장에는 넣지 않는다.
	 */
	private static final String UNKNOWN_BIRTH_TIME = "시간 모름";

	private static final DateTimeFormatter BIRTH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
	private static final DateTimeFormatter BIRTH_TIME_FORMAT = DateTimeFormatter.ofPattern("HH시 mm분");
	private static final DateTimeFormatter BIRTH_TIME_SHORT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private SajuProfileSections() {
	}

	/**
	 * 시작 문장("…에 태어나신")에 넣는 출생 일시를 "2001년 06월 12일 11시 12분" 꼴로 쓴다.
	 * 출생시간을 모르면(null) 날짜만 써서 "2001년 06월 12일에 태어나신" 으로 이어지게 한다.
	 * 모델이 이 문장을 결과 첫 문장으로 그대로 옮기므로 데이터 줄과 달리 {@value #UNKNOWN_BIRTH_TIME} 을 넣지 않는다.
	 */
	static String birthDateTimePhrase(LocalDate solarDate, LocalTime solarTime) {
		String date = solarDate.format(BIRTH_DATE_FORMAT);
		return solarTime == null ? date : date + " " + solarTime.format(BIRTH_TIME_FORMAT);
	}

	/**
	 * 시작 문장에 넣는 출생 일시를 숫자 꼴 "2001-06-12 11:12" 로 쓴다. 성격 분석(2)의 시작 문장이 이 꼴을 쓴다.
	 * 출생시간을 모르면(null) 날짜만 쓴다. 이유는 {@link #birthDateTimePhrase} 와 같다.
	 */
	static String birthDateTimeDigitPhrase(LocalDate solarDate, LocalTime solarTime) {
		String date = solarDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
		return solarTime == null ? date : date + " " + solarTime.format(BIRTH_TIME_SHORT_FORMAT);
	}

	/**
	 * 출생시각을 데이터 줄에 쓰는 "11:12" 꼴로 쓴다. 출생시간을 모르면(null) {@value #UNKNOWN_BIRTH_TIME} 이라고 쓴다.
	 * 기본 정보 줄처럼 생년월일을 "2001-06-12" 로 적는 곳과 짝을 맞춘다.
	 */
	static String birthTimeShortText(LocalTime solarTime) {
		return solarTime == null ? UNKNOWN_BIRTH_TIME : solarTime.format(BIRTH_TIME_SHORT_FORMAT);
	}

	/**
	 * 한 사람의 원국·대운·월운 상세 데이터를 붙인다.
	 *
	 * @param referenceYear 기본 정보 줄의 "현재 연도" 이자 대운 "현재" 칸을 고르는 기준 연도. 대부분의 상품은 팩토리가
	 *                      한국 시각으로 정한 오늘의 연도를 넘기고, 2026년을 두고 푸는 상품(18, 101, 102, 106)은 2026 을 넘긴다.
	 */
	static void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response, int referenceYear) {
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

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
				birthTimeShortText(input.getSolarTime()),
				referenceYear));

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
			DaewoonSections.appendDaewoonPeriods(prompt, saju, input.getGender(), birthYear, referenceYear);

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
					saju.getYongsinInfo().getStrength().label(),
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
			.formatted(input.getSolarDate(), birthTimeShortText(input.getSolarTime()),
				input.getIsLunar() ? "음력" : "양력", input.getGender()));

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

	private static String formatMonthPeriod(LocalDateTime value) {
		if (value == null) {
			return "-";
		}
		return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
	}
}
