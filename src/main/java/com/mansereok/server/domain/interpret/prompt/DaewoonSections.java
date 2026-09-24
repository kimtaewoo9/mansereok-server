package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 대운과 60갑자 계산 조각. 오늘 일진도 여기서 구한다.
 */
@Slf4j
final class DaewoonSections {

	private DaewoonSections() {
	}

	private static final List<String> GAPJA_CYCLE_KOR = new ArrayList<>();

	// 60갑자 순서 정의 (대운 계산용)
	private static final List<String> HEAVENLY_STEMS = Arrays.asList("甲", "乙", "丙", "丁", "戊",
		"己", "庚", "辛", "壬", "癸");

	private static final List<String> EARTHLY_BRANCHES = Arrays.asList("子", "丑", "寅", "卯", "辰",
		"巳", "午", "未", "申", "酉", "戌", "亥");

	private static final List<String> HEAVENLY_STEMS_KOR = Arrays.asList("갑", "을", "병", "정", "무",
		"기", "경", "신", "임", "계");

	private static final List<String> EARTHLY_BRANCHES_KOR = Arrays.asList("자", "축", "인", "묘", "진",
		"사", "오", "미", "신", "유", "술", "해");

	private static final List<String> GAPJA_CYCLE = new ArrayList<>();

	static {
		for (int i = 0; i < 60; i++) {
			GAPJA_CYCLE.add(HEAVENLY_STEMS.get(i % 10) + EARTHLY_BRANCHES.get(i % 12));

			GAPJA_CYCLE_KOR.add(HEAVENLY_STEMS_KOR.get(i % 10) + EARTHLY_BRANCHES_KOR.get(i % 12));
		}
	}

	static String getDaewoonDirection(SajuInfo saju, String gender) {
		if (saju.getYearSky() == null || saju.getYearSky().getMinusPlus() == null) {
			return "?";
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		return "MALE".equalsIgnoreCase(gender) ?
			("양".equals(yearSkyMinusPlus) ? "순행" : "역행") :
			("양".equals(yearSkyMinusPlus) ? "역행" : "순행");
	}

	/**
	 * 대운 계산 (선형 탐색을 통한 100% 정확한 인덱스 매칭)
	 */
	static void appendDaewoonSimple(StringBuilder prompt, SajuInfo saju, String gender,
		int birthYear, int referenceYear) {
		// 1. 필수 데이터 검증
		if (saju.getYearSky() == null || saju.getMonthSky() == null
			|| saju.getMonthGround() == null || saju.getBigFortuneNumber() == null) {
			prompt.append("""
				대운 정보 없음 (필수 데이터 누락)
				""");
			return;
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		if (yearSkyMinusPlus == null) {
			prompt.append("""
				대운 정보 없음 (음양 정보 누락)
				""");
			return;
		}

		// 2. 대운 방향 결정
		boolean isForward = "MALE".equalsIgnoreCase(gender)
			? "양".equals(yearSkyMinusPlus)
			: "음".equals(yearSkyMinusPlus);

		String flowDirection = isForward ? "순행" : "역행";
		int startAge = saju.getBigFortuneNumber();

		// 3. 월주 인덱스 추출
		String skyChar = extractFirstChar(saju.getMonthSky().getChinese());
		String groundChar = extractFirstChar(saju.getMonthGround().getChinese());

		int skyIndex = HEAVENLY_STEMS.indexOf(skyChar);
		int groundIndex = EARTHLY_BRANCHES.indexOf(groundChar);

		// Fallback: 한글로 재시도
		if (skyIndex == -1 || groundIndex == -1) {
			skyChar = extractFirstChar(saju.getMonthSky().getKorean());
			groundChar = extractFirstChar(saju.getMonthGround().getKorean());
			skyIndex = HEAVENLY_STEMS_KOR.indexOf(skyChar);
			groundIndex = EARTHLY_BRANCHES_KOR.indexOf(groundChar);
		}

		if (skyIndex == -1 || groundIndex == -1) {
			log.error("대운 계산 실패: 천간={}, 지지={}", skyChar, groundChar);
			prompt.append("""
				대운 정보 없음 (월주 매칭 실패)
				""");
			return;
		}

		// 4. ✅ [핵심 수정] 선형 탐색으로 정확한 60갑자 인덱스 찾기
		int monthGapjaIndex;
		try {
			monthGapjaIndex = findGapjaIndex(skyIndex, groundIndex);
		} catch (IllegalArgumentException e) {
			log.error("존재할 수 없는 간지 조합: 천간인덱스={}, 지지인덱스={}", skyIndex, groundIndex);
			prompt.append("""
				대운 정보 오류 (잘못된 간지 조합)
				""");
			return;
		}

		// 5. 기준 연도의 대운 위치
		int currentDaewoonIndex;
		if (saju.getBigFortuneStartYear() != null) {
			currentDaewoonIndex = Math.max(0, (referenceYear - saju.getBigFortuneStartYear()) / 10);
		} else {
			int currentAge = referenceYear - birthYear + 1; // 세는 나이 (fallback)
			currentDaewoonIndex = Math.max(0, (currentAge - startAge) / 10);
		}

		prompt.append("""
			대운 시작: %d세 | 흐름: %s
			"""
			.formatted(startAge, flowDirection));
		String currentDaewoonKor = null;
		String currentDaewoonChi = null;
		int currentStartAge = -1;
		int currentStartYear = -1;

		// 6. 대운 출력 루프
		for (int i = currentDaewoonIndex; i < currentDaewoonIndex + 3 && i < 9; i++) {
			int age = startAge + (i * 10);
			if (age > 120) {
				break;
			}

			// 월주 다음부터 1대운 시작 (i + 1)
			int nextIndex;
			if (isForward) {
				nextIndex = (monthGapjaIndex + (i + 1)) % 60;
			} else {
				// 자바 음수 나머지 연산 안전 처리
				nextIndex = ((monthGapjaIndex - (i + 1)) % 60 + 60) % 60;
			}

			String daewoonKor = GAPJA_CYCLE_KOR.get(nextIndex);
			String daewoonChi = GAPJA_CYCLE.get(nextIndex);
			String daewoonStr = String.format("%s(%s)", daewoonKor, daewoonChi);
			int daewoonStartYear =
				saju.getBigFortuneStartYear() != null
					? saju.getBigFortuneStartYear() + (i * 10)
					: birthYear + age;

			if (i == currentDaewoonIndex) {
				prompt.append("""
					▶ %d~%d세: %s (현재)
					"""
					.formatted(age, age + 9, daewoonStr));
				currentDaewoonKor = daewoonKor;
				currentDaewoonChi = daewoonChi;
				currentStartAge = age;
				currentStartYear = daewoonStartYear;
			} else {
				prompt.append("""
					  %d~%d세: %s
					"""
					.formatted(age, age + 9, daewoonStr));
			}
		}

		if (currentDaewoonChi != null) {
			prompt.append("""
				[대운 고정값] 기준연도=%d, 현재대운=%s(%s), 구간=%d~%d세, 시작연도=%d
				"""
				.formatted(referenceYear, currentDaewoonKor, currentDaewoonChi, currentStartAge, currentStartAge + 9, currentStartYear));
		}
	}

	/**
	 * ✅ [완벽한 방법] 0~59를 순회하며 천간/지지가 일치하는 인덱스를 찾음 수학 공식 오류 가능성을 원천 차단함.
	 */
	private static int findGapjaIndex(int skyIndex, int groundIndex) {
		for (int i = 0; i < 60; i++) {
			// i번째 간지의 천간 인덱스는 i % 10
			// i번째 간지의 지지 인덱스는 i % 12
			if ((i % 10) == skyIndex && (i % 12) == groundIndex) {
				return i;
			}
		}
		// 60번을 다 돌았는데도 없으면, 사주적으로 불가능한 조합(예: 갑축)이 들어온 것임
		throw new IllegalArgumentException("유효하지 않은 간지 조합입니다.");
	}

	/**
	 * 문자열 첫 글자 추출 (안전)
	 */
	private static String extractFirstChar(String str) {
		if (str == null || str.isEmpty()) {
			return "";
		}
		return str.substring(0, 1);
	}

	static String calculateTodayDayPillar(java.time.LocalDate today) {
		// 기준일: 1900-01-01 = 甲戌日 (60갑자 중 10번째)
		java.time.LocalDate baseDate = java.time.LocalDate.of(1900, 1, 1);
		int baseDayPillarIndex = 10; // 갑술(甲戌)의 인덱스

		// 경과일수 계산
		long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(baseDate, today);

		// 60갑자 순환 계산
		int todayIndex = (int) ((baseDayPillarIndex + daysBetween) % 60);
		if (todayIndex < 0) {
			todayIndex += 60; // 음수 방지
		}

		// 60갑자에서 해당 인덱스의 간지 가져오기
		String chineseGapja = GAPJA_CYCLE.get(todayIndex);

		// 한글 변환
		int stemIndex = todayIndex % 10;
		int branchIndex = todayIndex % 12;
		String koreanStem = HEAVENLY_STEMS_KOR.get(stemIndex);
		String koreanBranch = EARTHLY_BRANCHES_KOR.get(branchIndex);

		return String.format("%s%s(%s)", koreanStem, koreanBranch, chineseGapja);
	}
}
