package com.mansereok.server.domain.interpret.calculator;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RelationCalculator {

	// 1. 삼합(三合)
	private static final List<SamhapGroup> SAMHAP_GROUPS = List.of(
		new SamhapGroup(Set.of("申", "子", "辰"), "수국(물)", "子"),
		new SamhapGroup(Set.of("寅", "午", "戌"), "화국(불)", "午"),
		new SamhapGroup(Set.of("巳", "酉", "丑"), "금국(쇠)", "酉"),
		new SamhapGroup(Set.of("亥", "卯", "未"), "목국(나무)", "卯")
	);

	// 2. 충(沖)
	private static final Map<String, String> CHUNG_MAP = Map.ofEntries(
		entry("子", "午"), entry("午", "子"),
		entry("丑", "未"), entry("未", "丑"),
		entry("寅", "申"), entry("申", "寅"),
		entry("卯", "酉"), entry("酉", "卯"),
		entry("辰", "戌"), entry("戌", "辰"),
		entry("巳", "亥"), entry("亥", "巳")
	);

	// 3. 원진(元嗔)
	private static final Map<String, String> WONJIN_MAP = Map.ofEntries(
		entry("子", "未"), entry("未", "子"),
		entry("丑", "午"), entry("午", "丑"),
		entry("寅", "酉"), entry("酉", "寅"),
		entry("卯", "申"), entry("申", "卯"),
		entry("辰", "亥"), entry("亥", "辰"),
		entry("巳", "戌"), entry("戌", "巳")
	);

	// 4. 천간합(天干合)
	private static final Map<String, String> SKY_HAP_MAP = Map.of(
		"甲", "己", "己", "甲",
		"乙", "庚", "庚", "乙",
		"丙", "辛", "辛", "丙",
		"丁", "壬", "壬", "丁",
		"戊", "癸", "癸", "戊"
	);

	// 5. 천간충(天干沖)
	private static final Map<String, String> SKY_CHUNG_MAP = Map.of(
		"甲", "庚", "庚", "甲",
		"乙", "辛", "辛", "乙",
		"丙", "壬", "壬", "丙",
		"丁", "癸", "癸", "丁"
	);

	// 지지 관계 분석
	public List<String> analyzeRelation(String jiji1, String jiji2) {
		List<String> relations = new ArrayList<>();
		if (jiji1 == null || jiji2 == null) {
			return relations;
		}

		if (jiji2.equals(CHUNG_MAP.get(jiji1))) {
			relations.add("충");
		}
		if (jiji2.equals(WONJIN_MAP.get(jiji1))) {
			relations.add("원진");
		}

		for (SamhapGroup group : SAMHAP_GROUPS) {
			if (group.members.contains(jiji1) && group.members.contains(jiji2)) {
				if (jiji1.equals(group.center) || jiji2.equals(group.center)) {
					relations.add("반합(" + group.name + ")");
				}
			}
		}
		return relations;
	}

	// 천간 관계 분석
	public List<String> analyzeSkyRelation(String sky1, String sky2) {
		List<String> relations = new ArrayList<>();
		if (sky1 == null || sky2 == null) {
			return relations;
		}

		if (sky2.equals(SKY_HAP_MAP.get(sky1))) {
			relations.add("천간합");
		}
		if (sky2.equals(SKY_CHUNG_MAP.get(sky1))) {
			relations.add("천간충");
		}

		return relations;
	}

	// 삼합 전체 스캔
	public List<String> findFullSamhap(List<String> allJijis) {
		List<String> result = new ArrayList<>();
		Set<String> myJijis = new HashSet<>(allJijis);

		for (SamhapGroup group : SAMHAP_GROUPS) {
			if (myJijis.containsAll(group.members)) {
				result.add("삼합(" + group.name + " 완성)");
			}
		}
		return result;
	}

	private static class SamhapGroup {

		Set<String> members;
		String name;
		String center;

		public SamhapGroup(Set<String> members, String name, String center) {
			this.members = members;
			this.name = name;
			this.center = center;
		}
	}
}
