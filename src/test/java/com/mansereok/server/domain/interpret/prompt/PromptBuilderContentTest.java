package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품별 프롬프트에 들어 있어야 하는(또는 들어 있으면 안 되는) 문구 검사.
 * 프롬프트를 패키지로 옮기기 전 ManseInterpretationServicePromptTest 가 리플렉션으로 하던 일을
 * 빌더를 직접 불러서 하도록 옮겨 왔다.
 */
@DisplayName("상품별 프롬프트 문구")
class PromptBuilderContentTest {

	private final ManseryeokCalculationResponse response = PromptFixtures.person1();

	@Test
	@DisplayName("사업운 프롬프트는 새 섹션 구조와 페이지 분리 규칙을 담는다")
	void businessPromptHasNewSectionStructureAndPageBreakRules() {
		String prompt = BusinessAndAcademicPrompts.createBusinessLuckPrompt("김태우", response);

		assertThat(prompt).isNotNull();
		assertThat(prompt).contains("fullAnalysis 총 분량은 최소 4000자 이상으로 작성한다.");
		assertThat(prompt).contains("페이지 분리는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로만 한다.");
		assertThat(prompt).contains("### 문체 기준 (골드 스탠다드) ###");
		assertThat(prompt).contains("### 이야기 흐름 (제목/번호는 출력하지 말 것) ###");
		assertThat(prompt).contains("### 절대 금지 패턴 ###");
		assertThat(prompt).contains("한 문단에 월 2개 이상 언급 금지");
		assertThat(prompt).contains("### 권장 서술 패턴 ###");
		assertThat(prompt).contains("yyyy년 M월 형식만 사용");
		assertThat(prompt).contains("한자(寅, 卯, 沖");
		assertThat(prompt).contains("색깔/방향/숫자 개운법");
		assertThat(prompt).contains("summary는 4~5줄로 작성");
		assertThat(prompt).contains("summary 총 길이는 280자 이내");
		assertThat(prompt).contains("### 월운 (향후 12개월) ###");
		assertThat(prompt).contains("적용구간:");
		assertThat(prompt).doesNotContain("## 1. 한 줄 결론");
		assertThat(prompt).doesNotContain("## 1. 성격 분석 + 사주적 근거");
		assertThat(prompt).doesNotContain("대괄호(`[]`)");
		assertThat(prompt).doesNotContain("첫 번째 단락 묶음");
		assertThat(prompt).doesNotContain("fullAnalysis 총 분량은 약 4000자 내외");
	}

	@Test
	@DisplayName("재물운 프롬프트는 알파벳 프레임 없이 목표 분량만 지시한다")
	void moneyLuckPromptHasNoLetteredFrameAndKeepsTargetLength() {
		String prompt = FortunePrompts.createMoneyLuckPrompt("김태우", response);

		assertThat(prompt).isNotNull();
		assertThat(prompt).contains("fullAnalysis 총 분량은 3800자 이상 4600자 이하로 작성한다.");
		assertThat(prompt).contains("월운은 12개월 나열 대신 핵심 3구간만 설명한다.");
		assertThat(prompt).contains("같은 조언을 문장만 바꿔 반복하지 않는다.");
		assertThat(prompt).doesNotContain("프레임마다 꼭 A, B, C, D, E, H");
		assertThat(prompt).doesNotContain("A. 돈벼락 가능성");
	}

	@Test
	@DisplayName("2026 키워드 프롬프트는 문단 규칙을 담고 메타 도입부를 금지한다")
	void keywordPromptHasParagraphRulesAndNoMetaOpening() {
		String prompt = FreeFortunePrompts.create2026KeywordPrompt("은정", response);

		assertThat(prompt).isNotNull();
		assertThat(prompt).contains("첫 줄은 [2026년 상반기 운명 키워드: 키워드명] 형식");
		assertThat(prompt).contains("정확히 4개 문단");
		assertThat(prompt).contains("줄바꿈 두 번(\\\\n\\\\n)");
		assertThat(prompt).contains("\"직접 대면 상담하듯 핵심만 전해드립니다\"");
		assertThat(prompt).contains("번호형 나열(1-1, 첫째, 둘째)");
		assertThat(prompt).contains("날짜 표기는 2026년 3월처럼 년-월까지만");
		assertThat(prompt).doesNotContain("## 2026년 상반기 운명 키워드: [키워드 명]");
		assertThat(prompt).doesNotContain("목차 강제");
	}

	@Test
	@DisplayName("케미 프롬프트는 추천 인물 구성과 문단 규칙을 담는다")
	void chemistryPromptHasParagraphRules() {
		String prompt = FreeFortunePrompts.createChemistryMatchPrompt("은정", response);

		assertThat(prompt).isNotNull();
		assertThat(prompt).contains("추천 대상은 반드시 여성으로만 선정하세요.");
		assertThat(prompt).contains("아이돌 1명, 배우 1명, 캐릭터 1명을 반드시 모두 채우세요.");
		assertThat(prompt).contains("각 후보마다 맞는 이유 2개, 주의점 1개");
		assertThat(prompt).contains("블랙핑크의 지수, 배우 박보영, 원피스의 나미");
		assertThat(prompt).contains("캐릭터 1명은 반드시 애니메이션 캐릭터만 허용");
		assertThat(prompt).contains("원피스의 나미, 귀멸의 칼날의 탄지로");
		assertThat(prompt).contains("본문 시작은 반드시 2~4문장으로 작성");
		assertThat(prompt).contains("님의 사주 핵심 성향을 간단히 설명");
		assertThat(prompt).contains("추천 파트의 첫 문장은 반드시 아래 형식으로 시작");
		assertThat(prompt).contains(
			"은정님과 가장 잘 어울리는 아이돌은 [아이돌 이름]님, 배우는 [배우 이름]님, 캐릭터는 [작품명]의 [캐릭터명]입니다.");
		assertThat(prompt).contains("인물 한 명 설명이 끝날 때마다 줄바꿈 두 번(\\n\\n)");
		assertThat(prompt).contains("한 인물 설명은 3~5문장");
		assertThat(prompt).contains("[아이돌 추천], [배우 추천], [캐릭터 추천] 같은 대괄호 라벨 금지");
		assertThat(prompt).contains("도입 문단: 사주 핵심 성향 + 잘 맞는 상대 타입 설명");
		assertThat(prompt).contains("추천 시작 문장: 아이돌/배우/캐릭터 1명 이름을 한 문장에 제시");
		assertThat(prompt).contains("아이돌 1명 추천 문단");
		assertThat(prompt).contains("배우 1명 추천 문단");
		assertThat(prompt).contains("캐릭터 1명 추천 문단");
		assertThat(prompt).contains("'남성 라인', '여성 라인', '카테고리', '골랐습니다', '선정했습니다'");
		assertThat(prompt).doesNotContain("종합 원픽 TOP3");
	}
}
