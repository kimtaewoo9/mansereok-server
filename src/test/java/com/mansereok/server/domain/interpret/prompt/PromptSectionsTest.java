package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("프롬프트 공통 조각")
class PromptSectionsTest {

	@Test
	@DisplayName("라우팅용 상품 id 는 int 범위를 벗어나면 잘리지 않고 예외가 된다")
	void requireRoutableSubcategoryIdRejectsOutOfRange() {
		assertThat(PromptSections.requireRoutableSubcategoryId(1L)).isEqualTo(1);

		assertThatThrownBy(() -> PromptSections.requireRoutableSubcategoryId(4294967297L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
		assertThatThrownBy(() -> PromptSections.requireRoutableSubcategoryId(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 카테고리입니다");
	}

	@Test
	@DisplayName("혜안 페르소나 머리말은 역할 정의와 작성 원칙, 절대 금지를 담는다")
	void hyeanPersonaHeaderHasRoleAndRules() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendHyeanPersonaHeader(prompt);

		String text = prompt.toString();
		assertThat(text).startsWith("### 0. 시스템 역할 정의 ###\n");
		assertThat(text).contains("당신은 30년 경력의 사주명리 전문가 '혜안'입니다.");
		assertThat(text).contains("### 1. 작성 원칙 ###");
		assertThat(text).contains("### 1-1. 깊이 규칙 — 겉핥기 금지 ###");
		assertThat(text).contains("### 2. 절대 금지 ###");
		assertThat(text).contains("### 4. 용어 해석 가이드라인 ###");
	}

	@Test
	@DisplayName("궁합 페르소나 머리말은 관계 서사 상담가 역할과 이름 표기 규칙을 담는다")
	void compatibilityPersonaHeaderHasRelationshipRole() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		String text = prompt.toString();
		assertThat(text).startsWith("### 0. 시스템 역할 정의 (Role Definition) ###\n");
		assertThat(text).contains("'관계 서사 상담가' 혜안(慧眼)입니다.");
		assertThat(text).contains("### 2. 금지 사항 (Strict Prohibitions) ###");
		assertThat(text).contains("### ⚠️ [필수 작성 지침] - 이름 표기 규칙 ###");
		assertThat(text).contains("**제공된 캐릭터의 이름은 절대로 임의로 줄이거나 변경하지 마세요.**");
	}

	@Test
	@DisplayName("사주 출력 형식 꼬리는 스키마가 못 잡는 작성 규칙만 남기고 필드 구성 설명은 지운다")
	void sajuOutputFormatKeepsOnlyWritingRules() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendSajuOutputFormat(prompt);

		String text = prompt.toString();
		assertThat(text).startsWith("\n\n### [최종 출력 형식] ###\n");
		assertThat(text).contains("--- [fullAnalysis 작성 규칙] ---");
		assertThat(text).contains("대괄호로 감싸 출력하고");
		assertThat(text).contains("`**` 강조 기호는 출력하지 않습니다.");
		assertThat(text).contains("한 문단이 6~7줄을 넘지 않게 끊습니다.");
		assertThat(text).contains("--- [summary 작성 규칙] ---");
		assertThat(text).contains("총 250자 이내");
		assertThat(text).contains("마침표는 찍지 않습니다");
		// Structured Outputs 가 강제하는 내용이라 프롬프트에서 뺀 문구
		assertThat(text).doesNotContain("두 필드로 구성됩니다");
		assertThat(text).doesNotContain("JSON");
	}

	@Test
	@DisplayName("궁합 출력 형식 꼬리는 세 필드의 작성 규칙만 남기고 필드 구성 설명은 지운다")
	void compatibilityOutputFormatKeepsOnlyWritingRules() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendCompatibilityOutputFormat(prompt);

		String text = prompt.toString();
		assertThat(text).contains("--- [interpretation 작성 규칙] ---");
		assertThat(text).contains("--- [score] ---");
		assertThat(text).contains("두 사람의 종합 궁합을 0~100 사이 정수로 담습니다.");
		assertThat(text).contains("--- [summary 작성 규칙] ---");
		assertThat(text).contains("총 250자 이내");
		assertThat(text).doesNotContain("세 필드로 구성됩니다");
		assertThat(text).doesNotContain("JSON");
	}

	@Test
	@DisplayName("구획 조립은 사용자 입력 구획을 먼저 놓고 분석 지시 구획을 뒤에 붙인다")
	void userInputSectionComesBeforeAnalysis() {
		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", "김태우");

		String prompt = PromptSections.prependUserInputSection(userValues, "분석 지시 본문");

		assertThat(prompt).startsWith(UserInputSanitizer.USER_INPUT_SECTION_HEADER);
		assertThat(prompt).endsWith("\n" + UserInputSanitizer.ANALYSIS_SECTION_HEADER
			+ "\n분석 지시 본문");
		assertThat(prompt.indexOf(UserInputSanitizer.USER_INPUT_BEGIN))
			.isLessThan(prompt.indexOf(UserInputSanitizer.ANALYSIS_SECTION_HEADER));
	}

	@Test
	@DisplayName("사업운 출력 형식 꼬리는 summary 줄 수 제한을 담는다")
	void businessOutputFormatLimitsSummaryLines() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendBusinessOutputFormat(prompt);

		assertThat(prompt.toString()).contains("summary는 4~5줄로 작성");
		assertThat(prompt.toString()).contains("summary 총 길이는 280자 이내");
		assertThat(prompt.toString()).doesNotContain("두 필드로 구성된다");
	}

	@Test
	@DisplayName("재물운 출력 형식 꼬리는 번호형 라벨과 목록 기호 금지를 담는다")
	void moneyLuckOutputFormatForbidsListMarkers() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendMoneyLuckOutputFormat(prompt);

		assertThat(prompt.toString()).contains("### 최종 출력 형식 ###");
		assertThat(prompt.toString()).contains(
			"fullAnalysis에는 번호형 라벨(A., 1., 첫째), 대괄호 제목([ ... ]), 목록 기호(-, *)를 쓰지 않는다.");
		assertThat(prompt.toString()).contains("fullAnalysis 길이는 3800자 이상 4600자 이하를 지킨다.");
		assertThat(prompt.toString()).doesNotContain("두 필드로 구성된다");
	}

	@Test
	@DisplayName("장문 유료 상품 깊이 규칙은 3단 전개를 상품별 무대만 갈아 끼워 만든다")
	void longformDepthRuleFillsStagePerProduct() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendLongformDepthRule(prompt, new PromptSections.LongformStage(
			"성향 설명", "사업", "사업 장면 설명", "이런 패턴이 반복됩니다"));

		String text = prompt.toString();
		assertThat(text).startsWith("### 사주 풀이 깊이 규칙 (반드시 지킬 것) ###\n");
		assertThat(text).contains("(1단) 사주 구조:");
		assertThat(text).contains("(2단) 성향 풀이: 성향 설명");
		assertThat(text).contains("(3단) 사업 장면: 사업 장면 설명");
		assertThat(text).contains("이런 일이 벌어집니다/이런 패턴이 반복됩니다 같은 묘사여야 한다.");
		assertThat(text).contains("누구에게나 맞는 말은 금지다.");
	}

	@Test
	@DisplayName("장문 유료 상품 분량 규칙은 페이지 수와 문단 길이와 총 분량을 숫자로 못 박는다")
	void longformPageRuleStatesNumbers() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendLongformPageRule(prompt);

		String text = prompt.toString();
		assertThat(text).contains("fullAnalysis 총 분량은 최소 4000자 이상으로 작성한다.");
		assertThat(text).contains("총 페이지는 9~12개 흐름으로 구성한다.");
		assertThat(text).contains("한 페이지(문단)는 반드시 7~8줄(약 250~350자) 이내로 제한한다.");
	}

	/**
	 * 세 숫자(총 분량, 페이지 수, 문단 길이)의 관계를 프롬프트 문자열에서 직접 뽑아 확인한다.
	 * 상수끼리 계산해서 단언하면 프로덕션 문구를 한 글자도 읽지 않아 어떤 회귀도 잡지 못하므로,
	 * 정규식으로 실제 숫자를 뽑아 온다.
	 *
	 * <p>지금은 하단 모서리(9페이지 × 250자 = 2250자)가 최소 4000자에 미치지 않는다. 상단
	 * (12 × 350 = 4200자)에서만 성립하는 빠듯한 범위이고, 이 PR 은 범위를 건드리지 않기로 했다.
	 * 그래서 이 테스트는 "상단 모서리는 성립한다" 를 고정해, 페이지 수나 문단 길이를 줄여
	 * 어느 쪽 끝에서도 성립하지 않게 되는 회귀를 잡는다.
	 */
	@Test
	@DisplayName("장문 유료 상품 분량 규칙은 상단 모서리에서 총 분량을 채울 수 있다")
	void longformPageRuleUpperBoundReachesMinimumLength() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendLongformPageRule(prompt);

		String text = prompt.toString();
		int minTotalChars = onlyGroup(text, "총 분량은 최소 (\\d+)자 이상", 1);
		int maxPages = onlyGroup(text, "총 페이지는 (\\d+)~(\\d+)개", 2);
		int maxCharsPerPage = onlyGroup(text, "약 (\\d+)~(\\d+)자", 2);

		assertThat(maxPages * maxCharsPerPage)
			.as("페이지 %d개 × 한 페이지 %d자", maxPages, maxCharsPerPage)
			.isGreaterThanOrEqualTo(minTotalChars);
	}

	/** 프롬프트에서 패턴의 group(n) 을 정수로 뽑는다. 패턴이 한 번만 등장하는 것도 함께 확인한다. */
	private static int onlyGroup(String text, String regex, int group) {
		Matcher matcher = Pattern.compile(regex).matcher(text);
		assertThat(matcher.find()).as("패턴을 찾지 못했다: %s", regex).isTrue();
		int value = Integer.parseInt(matcher.group(group));
		assertThat(matcher.find()).as("패턴이 두 번 이상 나온다: %s", regex).isFalse();
		return value;
	}

	@Test
	@DisplayName("장문 유료 상품 문장 스타일은 사업운만 가운데 두 줄을 더 끼운다")
	void longformNarrationStyleTakesExtraLines() {
		StringBuilder plain = new StringBuilder();
		StringBuilder withExtra = new StringBuilder();

		PromptSections.appendLongformNarrationStyle(plain);
		PromptSections.appendLongformNarrationStyle(withExtra, """
			추가 줄 하나.
			""");

		assertThat(plain.toString()).contains("### 문장 스타일 ###");
		assertThat(plain.toString()).doesNotContain("추가 줄 하나.");
		assertThat(withExtra.toString()).contains("추가 줄 하나.");
		assertThat(withExtra.toString()).endsWith("해요체를 기본으로 하되, 핵심 판단은 합니다체로 무게를 준다.\n\n");
	}

	@Test
	@DisplayName("장문 유료 상품 용어 정책은 사주 용어 풀이 규칙과 데이터 밖 추측 금지를 담는다")
	void longformTermPolicyHasTermAndFactRules() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendLongformTermPolicy(prompt);

		String text = prompt.toString();
		assertThat(text).startsWith("### 절대 규칙 (최우선) ###\n");
		assertThat(text).contains("[용어 정책 — 단 하나의 규칙]");
		assertThat(text).contains("처음 등장할 때 반드시 쉬운 풀이를 한 문장 붙이고");
		assertThat(text).contains("입력 데이터만 사용한다.");
		assertThat(text).doesNotContain("입력 JSON");
	}
}
