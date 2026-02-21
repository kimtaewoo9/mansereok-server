package com.mansereok.server.domain.interpret.calculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 신살(神殺) 계산 서비스
 * <p>
 * 포함된 신살:
 * - 도화살(桃花殺): 이성운, 인기
 * - 역마살(驛馬殺): 변화, 이동
 * - 화개살(華蓋殺): 예술, 학문
 * - 천을귀인(天乙貴人): 귀인의 도움
 * - 양인살(羊刃殺): 강한 추진력
 * - 괴강살(魁罡殺): 강한 성격
 * - 백호대살(白虎大殺): 강한 기운, 재난 주의
 * - 공망(空亡): 허무, 공허
 */
@Component
@Slf4j
public class SinsalCalculator {

	// 삼합 기준 도화살
	private static final Map<String, String> DOHWA_MAP = new HashMap<>();

	static {
		DOHWA_MAP.put("寅", "卯");
		DOHWA_MAP.put("午", "卯");
		DOHWA_MAP.put("戌", "卯");
		DOHWA_MAP.put("申", "酉");
		DOHWA_MAP.put("子", "酉");
		DOHWA_MAP.put("辰", "酉");
		DOHWA_MAP.put("巳", "午");
		DOHWA_MAP.put("酉", "午");
		DOHWA_MAP.put("丑", "午");
		DOHWA_MAP.put("亥", "子");
		DOHWA_MAP.put("卯", "子");
		DOHWA_MAP.put("未", "子");
	}

	// 삼합 기준 역마살
	private static final Map<String, String> YEOGMA_MAP = new HashMap<>();

	static {
		YEOGMA_MAP.put("寅", "申");
		YEOGMA_MAP.put("午", "申");
		YEOGMA_MAP.put("戌", "申");
		YEOGMA_MAP.put("申", "寅");
		YEOGMA_MAP.put("子", "寅");
		YEOGMA_MAP.put("辰", "寅");
		YEOGMA_MAP.put("巳", "亥");
		YEOGMA_MAP.put("酉", "亥");
		YEOGMA_MAP.put("丑", "亥");
		YEOGMA_MAP.put("亥", "巳");
		YEOGMA_MAP.put("卯", "巳");
		YEOGMA_MAP.put("未", "巳");
	}

	// 삼합 기준 화개살
	private static final Map<String, String> HWAGAE_MAP = new HashMap<>();

	static {
		HWAGAE_MAP.put("寅", "戌");
		HWAGAE_MAP.put("午", "戌");
		HWAGAE_MAP.put("戌", "戌");
		HWAGAE_MAP.put("申", "辰");
		HWAGAE_MAP.put("子", "辰");
		HWAGAE_MAP.put("辰", "辰");
		HWAGAE_MAP.put("巳", "丑");
		HWAGAE_MAP.put("酉", "丑");
		HWAGAE_MAP.put("丑", "丑");
		HWAGAE_MAP.put("亥", "未");
		HWAGAE_MAP.put("卯", "未");
		HWAGAE_MAP.put("未", "未");
	}

	// 일간 기준 천을귀인
	private static final Map<String, List<String>> CHEONUL_MAP = new HashMap<>();

	static {
		CHEONUL_MAP.put("甲", Arrays.asList("丑", "未"));
		CHEONUL_MAP.put("乙", Arrays.asList("子", "申"));
		CHEONUL_MAP.put("丙", Arrays.asList("亥", "酉"));
		CHEONUL_MAP.put("丁", Arrays.asList("亥", "酉"));
		CHEONUL_MAP.put("戊", Arrays.asList("丑", "未"));
		CHEONUL_MAP.put("己", Arrays.asList("子", "申"));
		CHEONUL_MAP.put("庚", Arrays.asList("丑", "未"));
		CHEONUL_MAP.put("辛", Arrays.asList("寅", "午"));
		CHEONUL_MAP.put("壬", Arrays.asList("卯", "巳"));
		CHEONUL_MAP.put("癸", Arrays.asList("卯", "巳"));
	}

	// 괴강살 (일주 기준)
	private static final List<String> GOEGANG_LIST = Arrays.asList(
		"庚辰", "庚戌", "壬辰", "戊戌"
	);

	// 백호대살 (일주 기준)
	private static final List<String> BAEKHO_LIST = Arrays.asList(
		"甲辰", "乙未", "丙戌", "丁丑", "戊辰", "壬戌", "癸丑"
	);

	// 양인살 (일간 기준, 양간만 해당)
	private static final Map<String, String> YANGIN_MAP = new HashMap<>();

	static {
		YANGIN_MAP.put("甲", "卯");
		YANGIN_MAP.put("丙", "午");
		YANGIN_MAP.put("戊", "午");
		YANGIN_MAP.put("庚", "酉");
		YANGIN_MAP.put("壬", "子");
	}

	private static final Map<String, String> HONGYEOM_MAP = new HashMap<>();

	static {
		HONGYEOM_MAP.put("甲", "午"); // 갑오
		HONGYEOM_MAP.put("乙", "午"); // 을오
		HONGYEOM_MAP.put("丙", "寅"); // 병인
		HONGYEOM_MAP.put("丁", "未"); // 정미
		HONGYEOM_MAP.put("戊", "辰"); // 무진
		HONGYEOM_MAP.put("己", "辰"); // 기진
		HONGYEOM_MAP.put("庚", "戌"); // 경술
		HONGYEOM_MAP.put("辛", "酉"); // 신유
		HONGYEOM_MAP.put("壬", "子"); // 임자
		HONGYEOM_MAP.put("癸", "申"); // 계신
	}

	// 공망 계산용 60갑자 순서
	private static final List<String> SIXTY_GAPJA = Arrays.asList(
		"甲子", "乙丑", "丙寅", "丁卯", "戊辰", "己巳", "庚午", "辛未", "壬申", "癸酉", // 갑자순
		"甲戌", "乙亥", "丙子", "丁丑", "戊寅", "己卯", "庚辰", "辛巳", "壬午", "癸未", // 갑술순
		"甲申", "乙酉", "丙戌", "丁亥", "戊子", "己丑", "庚寅", "辛卯", "壬辰", "癸巳", // 갑신순
		"甲午", "乙未", "丙申", "丁酉", "戊戌", "己亥", "庚子", "辛丑", "壬寅", "癸卯", // 갑오순
		"甲辰", "乙巳", "丙午", "丁未", "戊申", "己酉", "庚戌", "辛亥", "壬子", "癸丑", // 갑진순
		"甲寅", "乙卯", "丙辰", "丁巳", "戊午", "己未", "庚申", "辛酉", "壬戌", "癸亥"  // 갑인순
	);

	/**
	 * 전체 신살 분석 (연지와 일지를 모두 기준으로 계산)
	 *
	 * @param ilganChinese     일간 한자 (예: "壬")
	 * @param yearJijiChinese  년지 한자 (예: "午")
	 * @param monthJijiChinese 월지 한자 (예: "巳")
	 * @param dayJijiChinese   일지 한자 (예: "子")
	 * @param timeJijiChinese  시지 한자 (예: "未", null 가능)
	 * @return 각 기둥별 신살 목록
	 */
	public Map<String, List<String>> analyzeAllSinsal(
		String ilganChinese,
		String yearJijiChinese,
		String monthJijiChinese,
		String dayJijiChinese,
		String timeJijiChinese
	) {
		Map<String, List<String>> result = new HashMap<>();

		// 연지와 일지를 모두 기준으로 신살 계산
		result.put("년주", analyzePillarSinsal(ilganChinese, yearJijiChinese,
			yearJijiChinese, dayJijiChinese));
		result.put("월주", analyzePillarSinsal(ilganChinese, monthJijiChinese,
			yearJijiChinese, dayJijiChinese));
		result.put("일주", analyzePillarSinsal(ilganChinese, dayJijiChinese,
			yearJijiChinese, dayJijiChinese));

		if (timeJijiChinese != null) {
			result.put("시주", analyzePillarSinsal(ilganChinese, timeJijiChinese,
				yearJijiChinese, dayJijiChinese));
		}

		return result;
	}

	/**
	 * 개별 기둥의 신살 분석
	 *
	 * @param ilgan      일간 한자
	 * @param targetJiji 분석 대상 지지
	 * @param yearJiji   년지 (도화/역마/화개살 기준용)
	 * @param dayJiji    일지 (도화/역마/화개살 기준용)
	 * @return 해당 기둥의 신살 목록
	 */
	private List<String> analyzePillarSinsal(String ilgan, String targetJiji,
		String yearJiji, String dayJiji) {
		List<String> sinsalList = new ArrayList<>();

		// 도화살 체크 (연지 또는 일지 기준)
		String dohwaByYear = DOHWA_MAP.get(yearJiji);
		String dohwaByDay = DOHWA_MAP.get(dayJiji);
		if (targetJiji.equals(dohwaByYear) || targetJiji.equals(dohwaByDay)) {
			sinsalList.add("도화살");
		}

		// 역마살 체크 (연지 또는 일지 기준)
		String yeogmaByYear = YEOGMA_MAP.get(yearJiji);
		String yeogmaByDay = YEOGMA_MAP.get(dayJiji);
		if (targetJiji.equals(yeogmaByYear) || targetJiji.equals(yeogmaByDay)) {
			sinsalList.add("역마살");
		}

		// 화개살 체크 (연지 또는 일지 기준)
		String hwagaeByYear = HWAGAE_MAP.get(yearJiji);
		String hwagaeByDay = HWAGAE_MAP.get(dayJiji);
		if (targetJiji.equals(hwagaeByYear) || targetJiji.equals(hwagaeByDay)) {
			sinsalList.add("화개살");
		}

		// 천을귀인 체크 (일간 기준)
		List<String> cheonulList = CHEONUL_MAP.get(ilgan);
		if (cheonulList != null && cheonulList.contains(targetJiji)) {
			sinsalList.add("천을귀인");
		}

		// 양인살 체크 (일간 기준)
		String yangin = YANGIN_MAP.get(ilgan);
		if (targetJiji.equals(yangin)) {
			sinsalList.add("양인살");
		}

		String hongyeom = HONGYEOM_MAP.get(ilgan);
		if (targetJiji.equals(hongyeom)) {
			sinsalList.add("홍염살");
		}

		return sinsalList;
	}

	/**
	 * 괴강살 체크
	 *
	 * @param ilganChinese   일간 한자
	 * @param dayJijiChinese 일지 한자
	 * @return 괴강살 여부
	 */
	public boolean hasGoegang(String ilganChinese, String dayJijiChinese) {
		String ilju = ilganChinese + dayJijiChinese;
		return GOEGANG_LIST.contains(ilju);
	}

	/**
	 * 백호대살 체크
	 *
	 * @param ilganChinese   일간 한자
	 * @param dayJijiChinese 일지 한자
	 * @return 백호대살 여부
	 */
	public boolean hasBaekho(String ilganChinese, String dayJijiChinese) {
		String ilju = ilganChinese + dayJijiChinese;
		return BAEKHO_LIST.contains(ilju);
	}

	/**
	 * 공망(空亡) 계산 일주를 기준으로 해당 순(旬)에서 비어있는 두 지지를 찾음
	 * <p>
	 * 예: 壬子일주 -> 갑진순(甲辰~癸丑) -> 공망은 戌, 亥
	 *
	 * @param ilganChinese   일간 한자
	 * @param dayJijiChinese 일지 한자
	 * @return 공망 지지 목록 (2개)
	 */
	public List<String> calculateGongmang(String ilganChinese, String dayJijiChinese) {
		String ilju = ilganChinese + dayJijiChinese;
		int index = SIXTY_GAPJA.indexOf(ilju);

		if (index == -1) {
			log.warn("일주 {}를 60갑자에서 찾을 수 없습니다.", ilju);
			return new ArrayList<>();
		}

		// 해당 순(旬)의 시작 인덱스 (10개씩 묶음)
		int sunStart = (index / 10) * 10;

		// 순의 마지막 2개 지지가 공망
		// 예: 갑진순(40~49) -> 48번(壬戌)의 지지 戌, 49번(癸丑)의 지지 丑이 아닌
		// 60갑자에서 비어있는 戌(10번째), 亥(11번째)가 공망

		// 천간은 10개, 지지는 12개이므로 각 순마다 2개의 지지가 남음
		int gapjaStartInSun = sunStart % 60; // 해당 순의 60갑자 시작 위치

		// 공망은 해당 순에서 사용되지 않은 지지 2개
		// 간단히: 순의 시작 지지부터 +10, +11번째 지지
		List<String> earthlyBranches = Arrays.asList("子", "丑", "寅", "卯", "辰", "巳", "午", "未",
			"申", "酉", "戌", "亥");

		// 해당 순의 첫 일주에서 지지 인덱스 찾기
		String firstIljuInSun = SIXTY_GAPJA.get(sunStart);
		String firstJiji = firstIljuInSun.substring(1); // 지지 추출
		int jijiStartIndex = earthlyBranches.indexOf(firstJiji);

		// 공망 지지 계산 (순의 시작 지지 + 10, +11)
		String gongmang1 = earthlyBranches.get((jijiStartIndex + 10) % 12);
		String gongmang2 = earthlyBranches.get((jijiStartIndex + 11) % 12);

		return Arrays.asList(gongmang1, gongmang2);
	}

	/**
	 * 공망에 걸린 지지인지 확인
	 *
	 * @param jiji         확인할 지지
	 * @param gongmangList 공망 지지 목록
	 * @return 공망 여부
	 */
	public boolean isInGongmang(String jiji, List<String> gongmangList) {
		return gongmangList != null && gongmangList.contains(jiji);
	}

	/**
	 * 신살 설명
	 *
	 * @param sinsal 신살명
	 * @return 신살 설명
	 */
	public String getSinsalDescription(String sinsal) {
		if (sinsal == null) {
			return "";
		}

		return switch (sinsal) {
			case "도화살" -> "이성운, 인기, 예술적 재능 (과하면 색정 주의)";
			case "역마살" -> "변화, 이동, 활동성 (직업이동, 이사 多)";
			case "화개살" -> "예술, 종교, 학문적 재능 (고독한 성향)";
			case "천을귀인" -> "귀인의 도움, 위기 탈출 (대길신)";
			case "양인살" -> "강한 추진력, 독립성 (과격함 조절 필요)";
			case "괴강살" -> "강한 성격, 독립성, 돌파력";
			case "백호대살" -> "강한 기운, 재난 주의 (조심 필요)";
			case "공망" -> "허무, 공허, 현실화 어려움";
			case "홍염살" -> "붉은 매력, 이성에게 어필하는 치명적인 매력";
			default -> "";
		};
	}
}
