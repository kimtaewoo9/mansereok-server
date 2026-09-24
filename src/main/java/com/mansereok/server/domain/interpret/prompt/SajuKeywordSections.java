package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 신살과 해석 키워드 블록.
 */
final class SajuKeywordSections {

	private SajuKeywordSections() {
	}

	static void appendSinsalFull(StringBuilder prompt, SajuInfo saju) {
		Map<String, List<String>> sinsalByPillar = new HashMap<>();
		sinsalByPillar.put("년주", new ArrayList<>());
		sinsalByPillar.put("월주", new ArrayList<>());
		sinsalByPillar.put("일주", new ArrayList<>());
		sinsalByPillar.put("시주", new ArrayList<>());

		// 각 기둥별 신살 수집
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().forEach((pillar, sinsals) -> {
				if (sinsals != null && !sinsals.isEmpty()) {
					sinsalByPillar.get(pillar).addAll(sinsals);
				}
			});
		}

		// 특수 신살 추가 (일주)
		if (Boolean.TRUE.equals(saju.getHasGoegang())) {
			sinsalByPillar.get("일주").add("괴강살");
		}
		if (Boolean.TRUE.equals(saju.getHasBaekho())) {
			sinsalByPillar.get("일주").add("백호대살");
		}

		// 공망 추가
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			sinsalByPillar.get("일주").add("공망:" + String.join(",", saju.getGongmang()));
		}

		// 기둥별로 출력 (신살이 있는 기둥만)
		boolean hasSinsal = false;
		for (String pillar : Arrays.asList("년주", "월주", "일주", "시주")) {
			List<String> sinsals = sinsalByPillar.get(pillar);
			if (!sinsals.isEmpty()) {
				prompt.append(pillar).append(": ").append(String.join(", ", sinsals)).append("\n");
				hasSinsal = true;
			}
		}

		if (!hasSinsal) {
			prompt.append("""
				해당 없음
				""");
		}
		prompt.append("\n");
	}

	/**
	 * AI 환각 방지 및 고품질 해석을 위한 절대 기준(Fact) 주입
	 */
	static void appendKeywords(StringBuilder prompt, ManseryeokCalculationResponse response) {
		if (response == null || response.getSaju() == null) {
			return;
		}

		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		// 1. 오행/십성 데이터 계산 (판단을 위해 필요)
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		SajuElementSections.calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("""

			### 🔥 [절대 기준] AI 해석 가이드라인 (이 내용을 무조건 따를 것) ###
			너의 임의적인 판단보다 아래의 '계산된 팩트'가 우선합니다. 이 정보를 해석의 뼈대로 삼으세요.
			""");

		// ==========================================
		// 1. [핵심] 사주 강약 및 용신 (YongsinResult)
		// ==========================================
		if (saju.getYongsinInfo() != null) {
			prompt.append("""
				- 사주 강약 판정: %s (내 세력 %.1f vs 남의 세력 %.1f)
				- 용신 판단 규칙: %s (%s)
				"""
				.formatted(saju.getYongsinInfo().getStrength(), saju.getYongsinInfo().getMyScore(), (saju.getYongsinInfo().getTotalScore() - saju.getYongsinInfo().getMyScore()), saju.getYongsinInfo().getAppliedRuleName(), saju.getYongsinInfo().getAppliedRuleCode()));

			// AI에게 '신강/신약'에 따른 처세술 힌트 제공
			if (saju.getYongsinInfo().getMyScore() >= saju.getYongsinInfo().getTotalScore() / 2) {
				prompt.append("""
					  -> (지침) 주관이 뚜렷하고 고집이 셉니다. '독단적인 행동'을 주의하라고 조언하세요.
					""");
			} else {
				prompt.append("""
					  -> (지침) 주변 환경에 잘 휩쓸립니다. '자기 주관'을 가지라고 조언하세요.
					""");
			}

			prompt.append(
				String.format(
					"- 행운의 용신(Key): %s (%s) -> 이 오행의 기운을 살리는 활동/환경/습관을 조언에 자연스럽게 녹여내세요. 단, 이 프롬프트에 색상/숫자/방향 개운법 금지 규칙이 있으면 그 규칙이 우선입니다.\n",
					saju.getYongsinInfo().getYongsin(),
					saju.getYongsinInfo().getDescription()));
		}

		// ==========================================
		// 2. [성격] 오행 과다/결핍 (Ohaeng)
		// ==========================================
		if (ohaengCounts.getOrDefault("목", 0.0) >= 4.0) {
			prompt.append("""
				- [성격 키워드] 목(Wood) 과다: 계획과 시작이 많고 성장 욕구가 강하나, 벌여놓은 일의 마무리가 약해지기 쉬움. 우선순위 정리가 핵심 과제.
				""");
		}
		if (ohaengCounts.getOrDefault("화", 0.0) >= 4.0) {
			prompt.append("""
				- [성격 키워드] 화(Fire) 과다: 성격이 매우 급하고 다혈질, 화려함을 추구함. 감정 조절이 핵심 과제.
				""");
		}
		if (ohaengCounts.getOrDefault("토", 0.0) >= 4.0) {
			prompt.append("""
				- [성격 키워드] 토(Earth) 과다: 신중하고 묵직해 신뢰를 얻지만, 변화 대응이 느리고 고집이 셈. 결단의 타이밍이 과제.
				""");
		}
		if (ohaengCounts.getOrDefault("금", 0.0) >= 4.0) {
			prompt.append("""
				- [성격 키워드] 금(Metal) 과다: 원칙과 기준이 분명하고 맺고 끊음이 확실하나, 융통성 부족과 날카로운 말로 관계가 상하기 쉬움.
				""");
		}
		if (ohaengCounts.getOrDefault("수", 0.0) >= 4.0) {
			prompt.append("""
				- [성격 키워드] 수(Water) 과다: 생각이 너무 많아 우울감 주의, 비밀이 많고 융통성이 좋음.
				""");
		}
		for (Map.Entry<String, String> lack : Map.of(
			"목", "성장/확장 동력이 약해 새 일을 벌이는 결단이 늦음",
			"화", "표현과 열정의 발산이 약해 존재감이 묻히기 쉬움",
			"토", "중심을 잡아주는 안정감이 약해 환경 변화에 흔들리기 쉬움",
			"금", "맺고 끊는 결단력이 약해 정리와 거절이 어려움",
			"수", "유연한 사고와 휴식이 부족해 번아웃에 취약함").entrySet()) {
			if (ohaengCounts.getOrDefault(lack.getKey(), 0.0) <= 0.7) {
				prompt.append("""
					- [결핍] %s 부족: %s. 보완 방향을 조언에 반영하세요.
					"""
					.formatted(lack.getKey(), lack.getValue()));
			}
		}

		// ==========================================
		// 3. [직업/재능] 십성 단독 + 조합 (Sipseong)
		// ==========================================
		int siksang = sipseongCounts.getOrDefault("식신", 0) + sipseongCounts.getOrDefault("상관", 0);
		int gwanseong = sipseongCounts.getOrDefault("정관", 0) + sipseongCounts.getOrDefault("편관", 0);
		int jaeseong = sipseongCounts.getOrDefault("정재", 0) + sipseongCounts.getOrDefault("편재", 0);
		int inseong = sipseongCounts.getOrDefault("정인", 0) + sipseongCounts.getOrDefault("편인", 0);
		int bigyeop = sipseongCounts.getOrDefault("비견", 0) + sipseongCounts.getOrDefault("겁재", 0);

		if (siksang == 0) {
			prompt.append("""
				- [단점] 무식상(No Expression): 표현력이 부족하고 행동보다 생각이 앞섬. -> '일단 저질러라'고 조언.
				""");
		} else if (siksang >= 3) {
			prompt.append("""
				- [장점] 식상 과다: 언변이 뛰어나고 끼가 넘침. 예체능, 마케팅, 영업 직무 추천.
				""");
		}

		if (gwanseong == 0) {
			prompt.append("""
				- [특징] 무관성(No Control): 자유로운 영혼. 조직 생활보다는 프리랜서나 전문직이 적합함.
				""");
		}

		if (jaeseong == 0) {
			prompt.append("""
				- [특징] 무재성(No Wealth Star): 돈 자체보다 일의 의미/완성도에 끌림. 재물은 전문성의 부산물로 따라오는 구조 -> 몸값을 올리는 전략을 조언.
				""");
		} else if (jaeseong >= 3) {
			prompt.append("""
				- [특징] 재성 혼잡: 결과와 돈 욕심이 많으나 마무리가 약할 수 있음. '선택과 집중'을 조언.
				""");
		}

		if (inseong >= 3) {
			prompt.append("""
				- [특징] 인성 과다: 배우고 계획하는 인풋은 넘치는데 실행이 늦음(생각 과다). 인풋 하나당 아웃풋 하나를 강제하라고 조언.
				""");
		}
		if (bigyeop >= 3) {
			prompt.append("""
				- [특징] 비겁 과다: 승부욕과 독립심이 강하고 내 사람을 잘 챙기나, 동업/돈거래에서 손실이 반복되기 쉬움.
				""");
		}

		// 조합 해석 (구조가 있으면 반드시 해석에 활용)
		if (siksang >= 2 && jaeseong >= 2) {
			prompt.append("""
				- [구조] 식상생재: 내가 만든 결과물(표현/기술/콘텐츠)이 돈으로 바뀌는 구조. 만들어서 파는 방향이 정답.
				""");
		}
		if (gwanseong >= 2 && inseong >= 2) {
			prompt.append("""
				- [구조] 관인상생: 조직에서 인정받아 단계적으로 올라가는 구조. 큰 판보다 검증된 시스템 안에서 성장이 빠름.
				""");
		}
		if (siksang >= 2 && gwanseong >= 2) {
			prompt.append("""
				- [구조] 식상-관성 긴장: 자유로운 표현 욕구와 규칙/책임이 내부에서 부딪힘. 규율 있는 조직과 창의적 역할 사이 균형이 평생 과제.
				""");
		}
		int pyeonin = sipseongCounts.getOrDefault("편인", 0);
		if (pyeonin >= 2 && siksang >= 1 && pyeonin > siksang) {
			prompt.append("""
				- [구조] 도식(倒食) 기운: 흡수(편인)가 출력(식신)을 누르는 구조. 일을 진행하다 '더 좋은 방법'이 보이면 완성 직전에 판을 뒤엎고 새로 시작하는 패턴이 반복됨 -> 갈아엎고 싶을 때 일단 완성부터 하라고 조언.
				""");
		}
		if (bigyeop >= 2 && jaeseong >= 2) {
			prompt.append("""
				- [구조] 군겁쟁재: 나와 같은 기운(비겁)들이 재물을 나눠 갖는 구조. 동업, 보증, 공동 투자, 가까운 사람과의 돈거래에서 재물이 갈라져 나가는 패턴 -> 돈의 소유와 관리 주체를 명확히 분리하라고 조언.
				""");
		}
		if (sipseongCounts.getOrDefault("정관", 0) >= 1
			&& sipseongCounts.getOrDefault("편관", 0) >= 1) {
			prompt.append("""
				- [구조] 관살혼잡: 책임(정관)과 압박(편관)이 뒤섞여 나를 평가하는 기준이 둘인 구조. 조직에서 상반된 요구 사이에 끼거나, 연애에서 상대 유형이 극단적으로 갈리는 장면으로 구체화하세요.
				""");
		}
		if (sipseongCounts.getOrDefault("편관", 0) >= 1 && inseong >= 2) {
			prompt.append("""
				- [구조] 살인상생: 외부 압박(편관)을 학습(인성)으로 소화하는 구조. 위기가 공부가 되고 시험/평가 국면에서 오히려 강해짐. 압박이 없으면 늘어지는 이면도 함께 짚으세요.
				""");
		}
		if (jaeseong >= 2 && gwanseong >= 1) {
			prompt.append("""
				- [구조] 재생관: 성과(재성)가 지위(관성)를 밀어주는 구조. 결과물을 쌓으면 평판과 자리가 따라오는 정공법 루트가 맞고, 정치나 줄서기는 오히려 독.
				""");
		}

		// ==========================================
		// 3-1. [에너지] 12운성 궁위 힌트
		// ==========================================
		appendUnseongHint(prompt, "월지(사회 기반)", saju.getMonthGround());
		appendUnseongHint(prompt, "일지(배우자궁·나의 자리)", saju.getDayGround());
		appendUnseongHint(prompt, "시지(말년·자식 자리)", saju.getTimeGround());

		// ==========================================
		// 4. [매력/살] 신살 정보 (SinsalInfo) - DTO 활용!
		// ==========================================
		if (saju.getSinsalInfo() != null) {
			// 모든 기둥의 신살을 뒤져서 '도화'나 '역마'가 있는지 체크
			boolean hasDohwa = false;
			boolean hasYeokma = false;
			boolean hasHwagae = false;

			for (List<String> sinsals : saju.getSinsalInfo().values()) {
				if (sinsals == null) {
					continue;
				}
				for (String s : sinsals) {
					if (s.contains("도화")) {
						hasDohwa = true;
					}
					if (s.contains("역마") || s.contains("지살")) {
						hasYeokma = true;
					}
					if (s.contains("화개")) {
						hasHwagae = true;
					}
				}
			}

			if (hasDohwa) {
				prompt.append("""
					- [매력] 도화살 보유: 사람을 끄는 묘한 매력과 인기가 있음. 연예인적 기질.
					""");
			}
			if (hasYeokma) {
				prompt.append("""
					- [활동] 역마살 보유: 한곳에 머물기보다 이동하고 여행하며 운이 트임. 해외 관련 일 추천.
					""");
			}
			if (hasHwagae) {
				prompt.append("""
					- [잠재력] 화개살 보유: 종교, 철학, 예술적 재능이 뛰어나고 고독을 즐김.
					""");
			}
		}

		// ==========================================
		// 5. [관계/사건] 합충 정보 (GroundRelations) - DTO 활용!
		// ==========================================
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			prompt.append("""
				- [지지 관계 특이사항] 어느 '자리'끼리의 작용인지가 해석의 핵심입니다. 막연한 '변화가 많다'가 아니라 아래 영역의 구체적 장면으로 풀어내세요:
				""");
			for (String relation : saju.getGroundRelations()) {
				String palace = describePalaceContext(relation);
				if (relation.contains("충")) {
					prompt.append("""
						  * %s: 정면충돌 — %s 사이에서 변동/단절/재편이 반복되는 자리.
						"""
						.formatted(relation, palace));
				} else if (relation.contains("합")) {
					prompt.append("""
						  * %s: 결속 — %s이(가) 강하게 묶임. 안정감인 동시에 그 영역에 묶여 답답할 수 있음.
						"""
						.formatted(relation, palace));
				} else if (relation.contains("원진") || relation.contains("귀문")) {
					prompt.append("""
						  * %s: 애증의 긴장 — %s 사이의 미묘한 신경전, 예민함과 직관.
						"""
						.formatted(relation, palace));
				} else if (relation.contains("형")) {
					prompt.append("""
						  * %s: 내적 마찰 — %s에서 겉으로 드러나지 않는 압박과 조정이 이어지는 자리.
						"""
						.formatted(relation, palace));
				}
			}
		}

		// ==========================================
		// 6. [부족함] 공망 (Gongmang)
		// ==========================================
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append("""
				- [공망(비어있음)] %s: 해당 글자에 해당하는 육친(부모,형제 등)이나 십성의 덕이 부족하거나 인연이 짧을 수 있음.
				"""
				.formatted(String.join(", ", saju.getGongmang())));
		}

		prompt.append("\n");
	}

	/**
	 * 지지 관계 문자열("년지-월지: 충" 등)에서 어느 궁위끼리의 작용인지 설명을 만든다.
	 */
	private static String describePalaceContext(String relation) {
		List<String> palaces = new ArrayList<>();
		if (relation.contains("년지")) {
			palaces.add("뿌리(가족·초년 환경)");
		}
		if (relation.contains("월지")) {
			palaces.add("사회 기반(직업·일터·환경)");
		}
		if (relation.contains("일지")) {
			palaces.add("나의 자리(배우자·일상·몸)");
		}
		if (relation.contains("시지")) {
			palaces.add("말년·자식 자리");
		}
		return palaces.isEmpty() ? "해당 영역" : String.join("과 ", palaces);
	}

	/**
	 * 궁위별 12운성이 특기할 만한 값이면 에너지 힌트를 추가한다.
	 */
	private static void appendUnseongHint(StringBuilder prompt, String palaceLabel,
		PillarElement ground) {
		if (ground == null || ground.getUnseong() == null) {
			return;
		}
		Map<String, String> notable = Map.of(
			"제왕", "기세가 정점인 자리 — 이 영역에서 주도권을 쥐려는 힘이 강하고, 그만큼 굽히기 어려움",
			"장생", "새로 태어나 자라나는 자리 — 이 영역에서는 배우는 활력이 마르지 않음",
			"건록", "스스로 벌어 서는 자리 — 이 영역에서 자립심과 실속이 강함",
			"태", "다시 잉태되는 자리 — 이 영역은 남들보다 늦게 시작되거나 후반에 새 국면이 열림",
			"묘", "거두어 저장하는 자리 — 이 영역의 감정과 자원을 쌓아두고 잘 드러내지 않음",
			"절", "끊어졌다 다시 이어지는 자리 — 이 영역에서 단절과 재시작을 경험함",
			"사", "활동이 잦아드는 자리 — 이 영역은 확장보다 정리와 마무리가 어울림");
		String meaning = notable.get(ground.getUnseong());
		if (meaning != null) {
			prompt.append("""
				- [에너지] %s 12운성 '%s': %s.
				"""
				.formatted(palaceLabel, ground.getUnseong(), meaning));
		}
	}
}
