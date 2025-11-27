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

	// 1. 삼합(三合) 데이터 정의: {구성글자리스트, 국(오행), 왕지(중심)}
	// 순서: 생지, 왕지, 묘지
	private static final List<SamhapGroup> SAMHAP_GROUPS = List.of(
		new SamhapGroup(Set.of("申", "子", "辰"), "수국(물)", "子"),
		new SamhapGroup(Set.of("寅", "午", "戌"), "화국(불)", "午"),
		new SamhapGroup(Set.of("巳", "酉", "丑"), "금국(쇠)", "酉"),
		new SamhapGroup(Set.of("亥", "卯", "未"), "목국(나무)", "卯")
	);

	// 2. 충(沖) 데이터
	private static final Map<String, String> CHUNG_MAP = Map.ofEntries(
		entry("子", "午"), entry("午", "子"),
		entry("丑", "未"), entry("未", "丑"),
		entry("寅", "申"), entry("申", "寅"),
		entry("卯", "酉"), entry("酉", "卯"),
		entry("辰", "戌"), entry("戌", "辰"),
		entry("巳", "亥"), entry("亥", "巳")
	);

	// 3. 원진(元嗔) 데이터 (이것도 12쌍이므로 똑같이 변경해야 함)
	private static final Map<String, String> WONJIN_MAP = Map.ofEntries(
		entry("子", "未"), entry("未", "子"),
		entry("丑", "午"), entry("午", "丑"),
		entry("寅", "酉"), entry("酉", "寅"),
		entry("卯", "申"), entry("申", "卯"),
		entry("辰", "亥"), entry("亥", "辰"),
		entry("巳", "戌"), entry("戌", "巳")
	);

	/**
	 * 두 지지 간의 관계 분석 (단일 관계)
	 */
	public List<String> analyzeRelation(String jiji1, String jiji2) {
		List<String> relations = new ArrayList<>();
		if (jiji1 == null || jiji2 == null) {
			return relations;
		}

		// 충 체크
		if (jiji2.equals(CHUNG_MAP.get(jiji1))) {
			relations.add("충(" + jiji1 + jiji2 + "충)");
		}

		// 원진 체크
		if (jiji2.equals(WONJIN_MAP.get(jiji1))) {
			relations.add("원진살");
		}

		// 반합 체크 (두 글자만으로 성립하는지)
		for (SamhapGroup group : SAMHAP_GROUPS) {
			if (group.members.contains(jiji1) && group.members.contains(jiji2)) {
				// 두 글자가 같은 삼합 그룹에 속함
				if (jiji1.equals(group.center) || jiji2.equals(group.center)) {
					// 둘 중 하나가 왕지(Center)라면 반합 인정
					relations.add("반합(" + group.name + ")");
				} else {
					// 왕지가 없는 결합(가합) -> 보통은 무시하거나 약하게 처리
					// relations.add("가합(" + group.name + ")"); // 필요시 주석 해제
				}
			}
		}

		return relations;
	}

	/**
	 * 사주 전체 지지(4개)를 넣어서 완성된 '삼합'이 있는지 찾는 메서드 (이건 ManseCalculationService에서 한 번만 호출해서 전체 스캔용으로 쓰세요)
	 */
	public List<String> findFullSamhap(List<String> allJijis) {
		List<String> result = new ArrayList<>();
		Set<String> myJijis = new HashSet<>(allJijis); // 내 지지들을 Set으로 변환

		for (SamhapGroup group : SAMHAP_GROUPS) {
			// 내 지지에 삼합의 모든 글자(3개)가 다 포함되어 있는지 확인
			if (myJijis.containsAll(group.members)) {
				result.add("삼합(" + group.name + " 완성)");
			}
		}
		return result;
	}

	// 삼합 그룹 정보 클래스
	private static class SamhapGroup {

		Set<String> members; // 구성 글자들
		String name;         // 국 이름 (수국, 화국 등)
		String center;       // 왕지 (가운데 글자)

		public SamhapGroup(Set<String> members, String name, String center) {
			this.members = members;
			this.name = name;
			this.center = center;
		}
	}
}
