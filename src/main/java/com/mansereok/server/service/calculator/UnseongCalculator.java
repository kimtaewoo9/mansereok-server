package com.mansereok.server.service.calculator;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 12운성(十二運星) 계산 서비스
 * <p>
 * 12운성: 장생(長生), 목욕(沐浴), 관대(冠帶), 건록(建祿), 제왕(帝旺), 쇠(衰), 병(病), 사(死), 묘(墓), 절(絶), 태(胎), 양(養)
 */
@Component
@Slf4j
public class UnseongCalculator {

	// 일간별 12운성 매핑 테이블
	private static final Map<String, Map<String, String>> UNSEONG_TABLE = new HashMap<>();

	static {
		// 甲(갑) 일간
		Map<String, String> gapMap = new HashMap<>();
		gapMap.put("亥", "장생");
		gapMap.put("子", "목욕");
		gapMap.put("丑", "관대");
		gapMap.put("寅", "건록");
		gapMap.put("卯", "제왕");
		gapMap.put("辰", "쇠");
		gapMap.put("巳", "병");
		gapMap.put("午", "사");
		gapMap.put("未", "묘");
		gapMap.put("申", "절");
		gapMap.put("酉", "태");
		gapMap.put("戌", "양");
		UNSEONG_TABLE.put("甲", gapMap);

		// 乙(을) 일간
		Map<String, String> eulMap = new HashMap<>();
		eulMap.put("午", "장생");
		eulMap.put("巳", "목욕");
		eulMap.put("辰", "관대");
		eulMap.put("卯", "건록");
		eulMap.put("寅", "제왕");
		eulMap.put("丑", "쇠");
		eulMap.put("子", "병");
		eulMap.put("亥", "사");
		eulMap.put("戌", "묘");
		eulMap.put("酉", "절");
		eulMap.put("申", "태");
		eulMap.put("未", "양");
		UNSEONG_TABLE.put("乙", eulMap);

		// 丙(병) 일간
		Map<String, String> byeongMap = new HashMap<>();
		byeongMap.put("寅", "장생");
		byeongMap.put("卯", "목욕");
		byeongMap.put("辰", "관대");
		byeongMap.put("巳", "건록");
		byeongMap.put("午", "제왕");
		byeongMap.put("未", "쇠");
		byeongMap.put("申", "병");
		byeongMap.put("酉", "사");
		byeongMap.put("戌", "묘");
		byeongMap.put("亥", "절");
		byeongMap.put("子", "태");
		byeongMap.put("丑", "양");
		UNSEONG_TABLE.put("丙", byeongMap);

		// 丁(정) 일간
		Map<String, String> jeongMap = new HashMap<>();
		jeongMap.put("酉", "장생");
		jeongMap.put("申", "목욕");
		jeongMap.put("未", "관대");
		jeongMap.put("午", "건록");
		jeongMap.put("巳", "제왕");
		jeongMap.put("辰", "쇠");
		jeongMap.put("卯", "병");
		jeongMap.put("寅", "사");
		jeongMap.put("丑", "묘");
		jeongMap.put("子", "절");
		jeongMap.put("亥", "태");
		jeongMap.put("戌", "양");
		UNSEONG_TABLE.put("丁", jeongMap);

		// 戊(무) 일간
		Map<String, String> muMap = new HashMap<>();
		muMap.put("寅", "장생");
		muMap.put("卯", "목욕");
		muMap.put("辰", "관대");
		muMap.put("巳", "건록");
		muMap.put("午", "제왕");
		muMap.put("未", "쇠");
		muMap.put("申", "병");
		muMap.put("酉", "사");
		muMap.put("戌", "묘");
		muMap.put("亥", "절");
		muMap.put("子", "태");
		muMap.put("丑", "양");
		UNSEONG_TABLE.put("戊", muMap);

		// 己(기) 일간
		Map<String, String> giMap = new HashMap<>();
		giMap.put("酉", "장생");
		giMap.put("申", "목욕");
		giMap.put("未", "관대");
		giMap.put("午", "건록");
		giMap.put("巳", "제왕");
		giMap.put("辰", "쇠");
		giMap.put("卯", "병");
		giMap.put("寅", "사");
		giMap.put("丑", "묘");
		giMap.put("子", "절");
		giMap.put("亥", "태");
		giMap.put("戌", "양");
		UNSEONG_TABLE.put("己", giMap);

		// 庚(경) 일간
		Map<String, String> gyeongMap = new HashMap<>();
		gyeongMap.put("巳", "장생");
		gyeongMap.put("午", "목욕");
		gyeongMap.put("未", "관대");
		gyeongMap.put("申", "건록");
		gyeongMap.put("酉", "제왕");
		gyeongMap.put("戌", "쇠");
		gyeongMap.put("亥", "병");
		gyeongMap.put("子", "사");
		gyeongMap.put("丑", "묘");
		gyeongMap.put("寅", "절");
		gyeongMap.put("卯", "태");
		gyeongMap.put("辰", "양");
		UNSEONG_TABLE.put("庚", gyeongMap);

		// 辛(신) 일간
		Map<String, String> sinMap = new HashMap<>();
		sinMap.put("子", "장생");
		sinMap.put("亥", "목욕");
		sinMap.put("戌", "관대");
		sinMap.put("酉", "건록");
		sinMap.put("申", "제왕");
		sinMap.put("未", "쇠");
		sinMap.put("午", "병");
		sinMap.put("巳", "사");
		sinMap.put("辰", "묘");
		sinMap.put("卯", "절");
		sinMap.put("寅", "태");
		sinMap.put("丑", "양");
		UNSEONG_TABLE.put("辛", sinMap);

		// 壬(임) 일간
		Map<String, String> imMap = new HashMap<>();
		imMap.put("申", "장생");
		imMap.put("酉", "목욕");
		imMap.put("戌", "관대");
		imMap.put("亥", "건록");
		imMap.put("子", "제왕");
		imMap.put("丑", "쇠");
		imMap.put("寅", "병");
		imMap.put("卯", "사");
		imMap.put("辰", "묘");
		imMap.put("巳", "절");
		imMap.put("午", "태");
		imMap.put("未", "양");
		UNSEONG_TABLE.put("壬", imMap);

		// 癸(계) 일간
		Map<String, String> gyeMap = new HashMap<>();
		gyeMap.put("卯", "장생");
		gyeMap.put("寅", "목욕");
		gyeMap.put("丑", "관대");
		gyeMap.put("子", "건록");
		gyeMap.put("亥", "제왕");
		gyeMap.put("戌", "쇠");
		gyeMap.put("酉", "병");
		gyeMap.put("申", "사");
		gyeMap.put("未", "묘");
		gyeMap.put("午", "절");
		gyeMap.put("巳", "태");
		gyeMap.put("辰", "양");
		UNSEONG_TABLE.put("癸", gyeMap);
	}

	/**
	 * 운성 계산
	 *
	 * @param ilganChinese 일간 한자 (예: "甲")
	 * @param jijiChinese  지지 한자 (예: "寅")
	 * @return 운성명 (예: "건록")
	 */
	public String calculate(String ilganChinese, String jijiChinese) {
		if (ilganChinese == null || jijiChinese == null) {
			return null;
		}

		Map<String, String> unseongMap = UNSEONG_TABLE.get(ilganChinese);
		if (unseongMap == null) {
			log.warn("일간 {}에 대한 운성 매핑을 찾을 수 없습니다.", ilganChinese);
			return null;
		}

		String unseong = unseongMap.get(jijiChinese);
		if (unseong == null) {
			log.warn("일간 {} + 지지 {}에 대한 운성을 찾을 수 없습니다.", ilganChinese, jijiChinese);
		}

		return unseong;
	}

	/**
	 * 운성의 의미 설명
	 */
	public String getUnseongDescription(String unseong) {
		if (unseong == null) {
			return "";
		}

		return switch (unseong) {
			case "장생" -> "새로운 시작, 성장의 기운";
			case "목욕" -> "정화와 변화, 불안정";
			case "관대" -> "성장과 발전, 책임감";
			case "건록" -> "왕성한 활동, 안정과 번영";
			case "제왕" -> "최고의 전성기, 완성";
			case "쇠" -> "쇠퇴의 시작, 정리";
			case "병" -> "어려움, 주의 필요";
			case "사" -> "끝과 새 시작의 준비";
			case "묘" -> "잠재력 저장, 휴식";
			case "절" -> "극복과 인내, 재기";
			case "태" -> "잉태, 새로운 준비";
			case "양" -> "양육, 성장 준비";
			default -> "";
		};
	}
}
