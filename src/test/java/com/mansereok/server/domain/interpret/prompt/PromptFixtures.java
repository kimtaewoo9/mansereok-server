package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.InputInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.MonthlyFortune;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프롬프트 빌더 테스트가 공유하는 고정 입력. 기대 결과 파일과 1:1 로 묶여 있으므로
 * 값을 바꾸면 기대 결과 파일도 다시 만들어야 한다.
 */
public final class PromptFixtures {

	private PromptFixtures() {
	}

	/** 남성, 시간 있음, 관계/신살/대운 정보가 모두 채워진 샘플. */
	public static ManseryeokCalculationResponse person1() {
		SajuInfo saju = SajuInfo.builder()
			.bigFortuneNumber(5)
			.bigFortuneNumberMin(4)
			.bigFortuneNumberMax(6)
			.bigFortuneStartYear(1995)
			.bigFortuneStartYearMin(1994)
			.bigFortuneStartYearMax(1996)
			.seasonStartTime("1990-01-06 05:33")
			.uncertaintyNotes(List.of("절기 경계 근처라 월주가 흔들릴 수 있음"))
			.yearSky(pillar("甲", "갑", "목", "식신", "양", "건록", "자기 힘으로 서는 자리",
				jijanggan("壬", "임", "수", "양", 30, "비견", "癸", "계", "수", "음", 70, "겁재")))
			.yearGround(pillar("子", "자", "수", "겁재", "양", "제왕", "기운이 가장 센 자리",
				jijanggan("壬", "임", "수", "양", 30, "비견", "癸", "계", "수", "음", 70, "겁재")))
			.monthSky(pillar("乙", "을", "목", "상관", "음", "목욕", "다듬어지는 자리",
				jijanggan("甲", "갑", "목", "양", 100, "식신")))
			.monthGround(pillar("丑", "축", "토", "정관", "음", "쇠", "정점을 지난 자리",
				jijanggan("癸", "계", "수", "음", 30, "겁재", "辛", "신", "금", "음", 30, "정인",
					"己", "기", "토", "음", 40, "정관")))
			.daySky(pillar("壬", "임", "수", "비견", "양", "태", "다시 시작하는 자리",
				jijanggan("壬", "임", "수", "양", 100, "비견")))
			.dayGround(pillar("午", "오", "화", "정재", "양", "장생", "새로 나는 자리",
				jijanggan("丙", "병", "화", "양", 30, "편재", "己", "기", "토", "음", 30, "정관",
					"丁", "정", "화", "음", 40, "정재")))
			.timeSky(pillar("丁", "정", "화", "정재", "음", "절", "끊어지는 자리",
				jijanggan("丁", "정", "화", "음", 100, "정재")))
			.timeGround(pillar("酉", "유", "금", "정인", "음", "욕", "씻기는 자리",
				jijanggan("庚", "경", "금", "양", 30, "편인", "辛", "신", "금", "음", 70, "정인")))
			.sinsalInfo(sinsal())
			.hasGoegang(Boolean.TRUE)
			.hasBaekho(Boolean.FALSE)
			.gongmang(List.of("寅", "卯"))
			.groundRelations(List.of("년지-월지: 충", "일지-시지: 원진"))
			.skyRelations(List.of("년간-월간: 천간합"))
			.samhap(List.of("사유축 금국"))
			.yongsinInfo(yongsin())
			.monthlyFortunes(monthlyFortunes())
			.build();

		InputInfo input = InputInfo.builder()
			.solarDate(LocalDate.of(1990, 1, 1))
			.solarTime(LocalTime.of(12, 0))
			.gender("MALE")
			.isLunar(Boolean.FALSE)
			.timeUnknown(Boolean.FALSE)
			.build();

		return ManseryeokCalculationResponse.builder().input(input).saju(saju).build();
	}

	/** 여성, 다른 팔자를 가진 샘플. 궁합 프롬프트의 두 번째 인물로 쓴다. */
	public static ManseryeokCalculationResponse person2() {
		SajuInfo saju = SajuInfo.builder()
			.bigFortuneNumber(3)
			.bigFortuneNumberMin(2)
			.bigFortuneNumberMax(4)
			.bigFortuneStartYear(1998)
			.bigFortuneStartYearMin(1997)
			.bigFortuneStartYearMax(1999)
			.seasonStartTime("1995-08-08 09:12")
			.uncertaintyNotes(List.of())
			.yearSky(pillar("乙", "을", "목", "정인", "음", "병", "기운이 꺾이는 자리",
				jijanggan("甲", "갑", "목", "양", 100, "편인")))
			.yearGround(pillar("亥", "해", "수", "상관", "음", "절", "끊어지는 자리",
				jijanggan("戊", "무", "토", "양", 30, "편관", "甲", "갑", "목", "양", 70, "편인")))
			.monthSky(pillar("甲", "갑", "목", "편인", "양", "사", "쉬어가는 자리",
				jijanggan("甲", "갑", "목", "양", 100, "편인")))
			.monthGround(pillar("申", "신", "금", "겁재", "양", "왕", "가장 단단한 자리",
				jijanggan("戊", "무", "토", "양", 30, "편관", "壬", "임", "수", "양", 30, "식신",
					"庚", "경", "금", "양", 40, "비견")))
			.daySky(pillar("庚", "경", "금", "비견", "양", "건록", "자기 힘으로 서는 자리",
				jijanggan("庚", "경", "금", "양", 100, "비견")))
			.dayGround(pillar("寅", "인", "목", "편재", "양", "쇠", "정점을 지난 자리",
				jijanggan("戊", "무", "토", "양", 30, "편인", "丙", "병", "화", "양", 30, "편관",
					"甲", "갑", "목", "양", 40, "편재")))
			.timeSky(pillar("丙", "병", "화", "편관", "양", "양", "품어지는 자리",
				jijanggan("丙", "병", "화", "양", 100, "편관")))
			.timeGround(pillar("戌", "술", "토", "편인", "양", "묘", "갈무리하는 자리",
				jijanggan("辛", "신", "금", "음", 30, "겁재", "丁", "정", "화", "음", 30, "정관",
					"戊", "무", "토", "양", 40, "편인")))
			.sinsalInfo(sinsal())
			.hasGoegang(Boolean.FALSE)
			.hasBaekho(Boolean.TRUE)
			.gongmang(List.of("辰", "巳"))
			.groundRelations(List.of("일지-월지: 충"))
			.skyRelations(List.of("월간-일간: 천간충"))
			.samhap(List.of("인오술 화국"))
			.yongsinInfo(yongsin())
			.monthlyFortunes(monthlyFortunes())
			.build();

		InputInfo input = InputInfo.builder()
			.solarDate(LocalDate.of(1995, 8, 15))
			.solarTime(LocalTime.of(3, 30))
			.gender("FEMALE")
			.isLunar(Boolean.FALSE)
			.timeUnknown(Boolean.FALSE)
			.build();

		return ManseryeokCalculationResponse.builder().input(input).saju(saju).build();
	}

	/**
	 * 대운이 역행하는 샘플. person1(남성·년간 양)과 person2(여성·년간 음)는 둘 다 순행이라
	 * 역행 분기가 기대 결과 파일에 한 번도 잡히지 않았다. 여기서는 남성 + 년간 음으로 역행을 태운다.
	 *
	 * <p>월주를 을축(乙丑, 60갑자 1번)으로 잡아 둔 것이 핵심이다. 역행은 월주 인덱스에서 빼 나가므로
	 * 1 - (i + 1) 이 음수가 되고, 자바 음수 나머지를 보정하는 {@code ((x % 60) + 60) % 60} 경로를
	 * 반드시 지나간다. 월주를 큰 인덱스로 잡으면 뺄셈 결과가 양수라 그 보정이 실행되지 않는다.
	 *
	 * <p>대운 시작 연도를 2000년으로 둬서 기준연도 2026 인 상품(18, 101, 106)에서는
	 * 대운 구간이 (2026 - 2000) / 10 = 2 로 고정된다. 기준연도가 고정된 상품만 기대 결과 파일로 만들어 두면
	 * 해가 바뀌어도 이 기대 결과 파일은 흔들리지 않는다.
	 */
	public static ManseryeokCalculationResponse personReverseDaewoon() {
		SajuInfo saju = SajuInfo.builder()
			.bigFortuneNumber(7)
			.bigFortuneNumberMin(6)
			.bigFortuneNumberMax(8)
			.bigFortuneStartYear(2000)
			.bigFortuneStartYearMin(1999)
			.bigFortuneStartYearMax(2001)
			.seasonStartTime("1993-02-04 10:38")
			.uncertaintyNotes(List.of())
			.yearSky(pillar("癸", "계", "수", "정인", "음", "목욕", "다듬어지는 자리",
				jijanggan("癸", "계", "수", "음", 100, "정인")))
			.yearGround(pillar("卯", "묘", "목", "겁재", "음", "제왕", "기운이 가장 센 자리",
				jijanggan("甲", "갑", "목", "양", 30, "비견", "乙", "을", "목", "음", 70, "겁재")))
			.monthSky(pillar("乙", "을", "목", "겁재", "음", "관대", "자리를 얻는 자리",
				jijanggan("乙", "을", "목", "음", 100, "겁재")))
			.monthGround(pillar("丑", "축", "토", "정재", "음", "관대", "자리를 얻는 자리",
				jijanggan("癸", "계", "수", "음", 30, "정인", "辛", "신", "금", "음", 30, "정관",
					"己", "기", "토", "음", 40, "정재")))
			.daySky(pillar("甲", "갑", "목", "비견", "양", "양", "품어지는 자리",
				jijanggan("甲", "갑", "목", "양", 100, "비견")))
			.dayGround(pillar("戌", "술", "토", "편재", "양", "양", "품어지는 자리",
				jijanggan("辛", "신", "금", "음", 30, "정관", "丁", "정", "화", "음", 30, "상관",
					"戊", "무", "토", "양", 40, "편재")))
			.timeSky(pillar("丙", "병", "화", "식신", "양", "병", "기운이 꺾이는 자리",
				jijanggan("丙", "병", "화", "양", 100, "식신")))
			.timeGround(pillar("寅", "인", "목", "비견", "양", "건록", "자기 힘으로 서는 자리",
				jijanggan("戊", "무", "토", "양", 30, "편재", "丙", "병", "화", "양", 30, "식신",
					"甲", "갑", "목", "양", 40, "비견")))
			.sinsalInfo(sinsal())
			.hasGoegang(Boolean.FALSE)
			.hasBaekho(Boolean.FALSE)
			.gongmang(List.of("申", "酉"))
			.groundRelations(List.of("월지-일지: 형"))
			.skyRelations(List.of("년간-시간: 천간충"))
			.samhap(List.of("인오술 화국"))
			.yongsinInfo(yongsin())
			.monthlyFortunes(monthlyFortunes())
			.build();

		InputInfo input = InputInfo.builder()
			.solarDate(LocalDate.of(1993, 2, 10))
			.solarTime(LocalTime.of(8, 20))
			.gender("MALE")
			.isLunar(Boolean.FALSE)
			.timeUnknown(Boolean.FALSE)
			.build();

		return ManseryeokCalculationResponse.builder().input(input).saju(saju).build();
	}

	/**
	 * 값이 비거나 없는 경로를 태우는 샘플. 시간 모름, 여성, 지장간/신살/용신/월운 없음.
	 * 프롬프트 빌더의 null 분기가 기대 결과 파일에 잡히도록 일부러 비워 둔다.
	 */
	public static ManseryeokCalculationResponse personEdge() {
		SajuInfo saju = SajuInfo.builder()
			.yearSky(simplePillar("丙", "병", "화", "편관", "양"))
			.yearGround(simplePillar("寅", "인", "목", "식신", "양"))
			.monthSky(simplePillar("戊", "무", "토", "정재", "양"))
			.monthGround(simplePillar("辰", "진", "토", "편재", "양"))
			.daySky(simplePillar("甲", "갑", "목", "비견", "양"))
			.dayGround(simplePillar("申", "신", "금", "편관", "양"))
			.timeSky(simplePillar("壬", "임", "수", "편인", "양"))
			.timeGround(simplePillar("子", "자", "수", "정인", "양"))
			.gongmang(List.of())
			.groundRelations(List.of())
			.skyRelations(List.of())
			.samhap(List.of())
			.monthlyFortunes(List.of())
			.build();

		InputInfo input = InputInfo.builder()
			.solarDate(LocalDate.of(2001, 6, 12))
			.solarTime(LocalTime.of(0, 0))
			.gender("FEMALE")
			.isLunar(Boolean.TRUE)
			.timeUnknown(Boolean.TRUE)
			.build();

		return ManseryeokCalculationResponse.builder().input(input).saju(saju).build();
	}

	private static YongsinResult yongsin() {
		return new YongsinResult("신약", 32.5, 100.0, "금", "일간을 받쳐주는 금 기운이 필요합니다.", "R-04",
			"억부용신");
	}

	private static Map<String, List<String>> sinsal() {
		Map<String, List<String>> sinsalInfo = new LinkedHashMap<>();
		sinsalInfo.put("년주", List.of("역마살"));
		sinsalInfo.put("월주", List.of("도화살", "화개살"));
		sinsalInfo.put("일주", List.of("천을귀인"));
		sinsalInfo.put("시주", List.of("양인살"));
		return sinsalInfo;
	}

	private static List<MonthlyFortune> monthlyFortunes() {
		String[][] pillars = {
			{"己", "기", "토", "정관", "음", "卯", "묘", "목", "상관", "음"},
			{"庚", "경", "금", "편인", "양", "辰", "진", "토", "편관", "양"},
			{"辛", "신", "금", "정인", "음", "巳", "사", "화", "편재", "음"},
			{"壬", "임", "수", "비견", "양", "午", "오", "화", "정재", "양"},
			{"癸", "계", "수", "겁재", "음", "未", "미", "토", "정관", "음"},
			{"甲", "갑", "목", "식신", "양", "申", "신", "금", "편인", "양"},
			{"乙", "을", "목", "상관", "음", "酉", "유", "금", "정인", "음"},
			{"丙", "병", "화", "편재", "양", "戌", "술", "토", "편관", "양"},
			{"丁", "정", "화", "정재", "음", "亥", "해", "수", "비견", "음"},
			{"戊", "무", "토", "편관", "양", "子", "자", "수", "겁재", "양"},
			{"己", "기", "토", "정관", "음", "丑", "축", "토", "정관", "음"},
			{"庚", "경", "금", "편인", "양", "寅", "인", "목", "식신", "양"}
		};
		String[] seasons = {"경칩", "청명", "입하", "망종", "소서", "입추", "백로", "한로", "입동", "대설", "소한",
			"입춘"};

		List<MonthlyFortune> fortunes = new ArrayList<>();
		LocalDateTime cursor = LocalDateTime.of(2026, 3, 5, 11, 0);
		for (int i = 0; i < pillars.length; i++) {
			String[] p = pillars[i];
			LocalDateTime start = cursor;
			LocalDateTime end = start.plusDays(30).minusSeconds(1);
			fortunes.add(MonthlyFortune.builder()
				.year(start.getYear())
				.month(start.getMonthValue())
				.season(seasons[i])
				.periodStart(start)
				.periodEnd(end)
				.monthSky(simplePillar(p[0], p[1], p[2], p[3], p[4]))
				.monthGround(simplePillar(p[5], p[6], p[7], p[8], p[9]))
				.build());
			cursor = end.plusSeconds(1);
		}
		return fortunes;
	}

	private static JijangganInfo jijanggan(String c1, String k1, String o1, String mp1, int r1,
		String t1) {
		return JijangganInfo.builder().first(element(c1, k1, o1, mp1, r1, t1)).build();
	}

	private static JijangganInfo jijanggan(String c1, String k1, String o1, String mp1, int r1,
		String t1, String c2, String k2, String o2, String mp2, int r2, String t2) {
		return JijangganInfo.builder()
			.first(element(c1, k1, o1, mp1, r1, t1))
			.second(element(c2, k2, o2, mp2, r2, t2))
			.build();
	}

	private static JijangganInfo jijanggan(String c1, String k1, String o1, String mp1, int r1,
		String t1, String c2, String k2, String o2, String mp2, int r2, String t2,
		String c3, String k3, String o3, String mp3, int r3, String t3) {
		return JijangganInfo.builder()
			.first(element(c1, k1, o1, mp1, r1, t1))
			.second(element(c2, k2, o2, mp2, r2, t2))
			.third(element(c3, k3, o3, mp3, r3, t3))
			.build();
	}

	private static JijangganElement element(String chinese, String korean, String fiveCircle,
		String minusPlus, int rate, String tenStar) {
		return JijangganElement.builder()
			.chinese(chinese)
			.korean(korean)
			.fiveCircle(fiveCircle)
			.fiveCircleColor("#000000")
			.minusPlus(minusPlus)
			.rate(rate)
			.tenStar(tenStar)
			.build();
	}

	private static PillarElement pillar(String chinese, String korean, String fiveCircle,
		String tenStar, String minusPlus, String unseong, String unseongDescription,
		JijangganInfo jijanggan) {
		return PillarElement.builder()
			.chinese(chinese)
			.korean(korean)
			.fiveCircle(fiveCircle)
			.fiveCircleColor("#000000")
			.tenStar(tenStar)
			.minusPlus(minusPlus)
			.jijanggan(jijanggan)
			.unseong(unseong)
			.unseongDescription(unseongDescription)
			.build();
	}

	private static PillarElement simplePillar(String chinese, String korean, String fiveCircle,
		String tenStar, String minusPlus) {
		return PillarElement.builder()
			.chinese(chinese)
			.korean(korean)
			.fiveCircle(fiveCircle)
			.fiveCircleColor("#000000")
			.tenStar(tenStar)
			.minusPlus(minusPlus)
			.build();
	}
}
