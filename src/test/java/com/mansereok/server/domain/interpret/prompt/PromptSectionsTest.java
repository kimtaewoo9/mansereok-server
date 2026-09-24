package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.SequencedMap;
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
	@DisplayName("사주 출력 형식 꼬리는 fullAnalysis 와 summary 작성 규칙을 담는다")
	void sajuJsonResponseFormatDescribesBothFields() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendSajuJsonResponseFormat(prompt);

		String text = prompt.toString();
		assertThat(text).startsWith("\n\n### [최종 출력 형식] ###\n");
		assertThat(text).contains("응답은 fullAnalysis와 summary 두 필드로 구성됩니다. JSON 형식은 시스템이 강제하므로 내용에만 집중하세요.");
		assertThat(text).contains("--- [fullAnalysis 작성 규칙] ---");
		assertThat(text).contains("--- [summary 작성 규칙] ---");
		assertThat(text).contains("총 250자 이내");
	}

	@Test
	@DisplayName("궁합 출력 형식 꼬리는 score, interpretation, summary 세 필드를 설명한다")
	void compatibilityJsonResponseFormatDescribesThreeFields() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendCompatibilityJsonResponseFormat(prompt);

		String text = prompt.toString();
		assertThat(text).contains("응답은 score, interpretation, summary 세 필드로 구성됩니다.");
		assertThat(text).contains("--- [interpretation 작성 규칙] ---");
		assertThat(text).contains("--- [score] ---");
		assertThat(text).contains("두 사람의 종합 궁합을 0~100 사이 정수로 담습니다.");
		assertThat(text).contains("--- [summary 작성 규칙] ---");
	}

	@Test
	@DisplayName("구획 조립은 사용자 입력 구획을 먼저 놓고 분석 지시 구획을 뒤에 붙인다")
	void sectionBoundaryPutsUserInputBeforeAnalysis() {
		SequencedMap<String, String> userValues = new LinkedHashMap<>();
		userValues.put("이름", "김태우");

		String prompt = PromptSections.withSectionBoundary(userValues, "분석 지시 본문");

		assertThat(prompt).startsWith(UserInputSanitizer.USER_INPUT_SECTION_HEADER);
		assertThat(prompt).endsWith("\n" + UserInputSanitizer.ANALYSIS_SECTION_HEADER
			+ "\n분석 지시 본문");
		assertThat(prompt.indexOf(UserInputSanitizer.USER_INPUT_BEGIN))
			.isLessThan(prompt.indexOf(UserInputSanitizer.ANALYSIS_SECTION_HEADER));
	}

	@Test
	@DisplayName("사업운 출력 형식 꼬리는 summary 줄 수 제한을 담는다")
	void businessJsonResponseFormatLimitsSummaryLines() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendBusinessJsonResponseFormat(prompt);

		assertThat(prompt.toString()).contains("summary는 4~5줄로 작성");
		assertThat(prompt.toString()).contains("summary 총 길이는 280자 이내");
	}

	@Test
	@DisplayName("재물운 출력 형식 꼬리는 번호형 라벨과 목록 기호 금지를 담는다")
	void moneyLuckJsonResponseFormatForbidsListMarkers() {
		StringBuilder prompt = new StringBuilder();

		PromptSections.appendMoneyLuckJsonResponseFormat(prompt);

		assertThat(prompt.toString()).contains("### 최종 출력 형식 ###");
		assertThat(prompt.toString()).contains(
			"fullAnalysis에는 번호형 라벨(A., 1., 첫째), 대괄호 제목([ ... ]), 목록 기호(-, *)를 쓰지 않는다.");
		assertThat(prompt.toString()).contains("fullAnalysis 길이는 3800자 이상 4600자 이하를 지킨다.");
	}
}
