package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프롬프트가 "필수 요소를 여전히 담고 있는지" 를 보는 테스트.
 *
 * <p>골든 테스트({@link PromptGoldenTest})는 문자열 전체 비교라 문구를 한 글자만 다듬어도 깨진다.
 * 깨진 골든을 갱신하는 순간, 실수로 지워 버린 문단도 같이 통과해 버린다. 이 테스트는 그 구멍을 막는다.
 * 여기서 보는 것은 세 가지다.
 * <ul>
 *   <li>페르소나 정의 — 어떤 역할로 쓰는지</li>
 *   <li>분석 항목 제목 — 무엇을 분석하라고 했는지</li>
 *   <li>출력 작성 규칙 — 스키마가 강제하지 못하는 서식 지시</li>
 * </ul>
 */
@DisplayName("프롬프트 필수 요소")
class PromptEssentialsTest {

	private static final SajuPromptFactory SAJU = new SajuPromptFactory();
	private static final CompatibilityPromptFactory COMPATIBILITY =
		new CompatibilityPromptFactory();

	private static String saju(long subcategoryId) {
		return SAJU.create(subcategoryId,
			PromptContext.of("김태우", PromptFixtures.person1(), "원피스"));
	}

	private static String free(long subcategoryId) {
		return SAJU.createFree(subcategoryId, PromptContext.of("김태우", PromptFixtures.person1()));
	}

	private static String compatibility(long subcategoryId) {
		return COMPATIBILITY.create(subcategoryId, CompatibilityPromptContext.of(
			"김태우", PromptFixtures.person1(), "원피스",
			"이은정", PromptFixtures.person2(), "귀멸의 칼날"));
	}

	@Nested
	@DisplayName("페르소나 정의")
	class Persona {

		/** 혜안 페르소나 머리말을 쓰는 유료 사주 상품들. */
		@ParameterizedTest(name = "{0}번 상품은 혜안 페르소나와 작성 원칙을 담는다")
		@ValueSource(longs = {1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L})
		void sajuPromptsKeepHyeanPersona(long subcategoryId) {
			String prompt = saju(subcategoryId);

			assertThat(prompt).contains("당신은 30년 경력의 사주명리 전문가 '혜안'입니다.");
			assertThat(prompt).contains("### 1. 작성 원칙 ###");
			assertThat(prompt).contains("**3단 전개**");
			assertThat(prompt).contains("### 1-1. 깊이 규칙 — 겉핥기 금지 ###");
			assertThat(prompt).contains("### 2. 절대 금지 ###");
			assertThat(prompt).contains("### 3. 문장 리듬 ###");
			assertThat(prompt).contains("### 4. 용어 해석 가이드라인 ###");
		}

		@ParameterizedTest(name = "{0}번 궁합 상품은 관계 서사 상담가 페르소나를 담는다")
		@ValueSource(longs = {4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L})
		void compatibilityPromptsKeepRelationshipPersona(long subcategoryId) {
			String prompt = compatibility(subcategoryId);

			assertThat(prompt).contains("'관계 서사 상담가' 혜안(慧眼)입니다.");
			assertThat(prompt).contains("### 1. 핵심 분석 원칙 (Core Principles) ###");
			assertThat(prompt).contains("### 2. 금지 사항 (Strict Prohibitions) ###");
			assertThat(prompt).contains("### 4. [매우 중요] 용어 사용 절대 규칙 ###");
			assertThat(prompt).contains("### ⚠️ [필수 작성 지침] - 이름 표기 규칙 ###");
		}

		@ParameterizedTest(name = "{0}번 장문 유료 상품은 역술가 역할과 용어 정책을 담는다")
		@ValueSource(longs = {21L, 22L, 23L})
		void longformPromptsKeepFortuneTellerRole(long subcategoryId) {
			String prompt = saju(subcategoryId);

			assertThat(prompt).contains("### 역할 ###");
			assertThat(prompt).contains("역술가다");
			assertThat(prompt).contains("### 글의 본질 — 가장 중요한 원칙 ###");
			assertThat(prompt).contains("### 사주 풀이 깊이 규칙 (반드시 지킬 것) ###");
			assertThat(prompt).contains("### 이야기 흐름 (제목/번호는 출력하지 말 것) ###");
			assertThat(prompt).contains("### 절대 금지 패턴 ###");
			assertThat(prompt).contains("### 문장 스타일 ###");
		}

		@Test
		@DisplayName("재회운 프롬프트는 재회 상담 매뉴얼 구조를 담는다")
		void reunionPromptKeepsManualStructure() {
			String prompt = compatibility(19L);

			assertThat(prompt).contains("재회");
			assertThat(prompt).contains("### [최종 출력 형식] ###");
		}
	}

	@Nested
	@DisplayName("분석 항목 제목")
	class AnalysisTopics {

		static Stream<Arguments> sajuTopics() {
			return Stream.of(
				Arguments.of(21L, List.of("1) 사업 체질 진단", "2) 돈의 흐름과 함정",
					"3) 사업에서 반복될 패턴", "4) 타이밍 — 시작, 가속, 안정화",
					"5) 어울리는 아이템", "6) 정리와 조언")),
				Arguments.of(22L, List.of("1) 시험형 인간인지 체질 진단", "2) 머리 쓰는 방식과 학습 패턴",
					"3) 합격운 타이밍", "4) 공부할 때 망하는 패턴", "5) 최적의 공부 전략",
					"6) 멘탈 관리와 위험 구간", "7) 정리와 체크리스트")),
				Arguments.of(23L, List.of("1) 성향 요약", "2) 감정 구조 분석", "3) 가장 힘들었을 시기 분석",
					"4) 극복 방식 분석", "5) 앞으로 힘들어질 패턴", "6) 미래 대응 전략과 인생 숙제")));
		}

		@ParameterizedTest(name = "{0}번 상품은 분석 항목 제목을 모두 담는다")
		@MethodSource("sajuTopics")
		void sajuPromptKeepsEveryTopic(long subcategoryId, List<String> topics) {
			String prompt = saju(subcategoryId);

			assertThat(prompt).contains(topics.toArray(new String[0]));
		}

		@Test
		@DisplayName("연애 궁합 프롬프트는 개인 분석과 관계 분석 단계를 모두 담는다")
		void loveCompatibilityKeepsBothStages() {
			String prompt = compatibility(4L);

			assertThat(prompt).contains("### 6. [분석 구조] ###");
			assertThat(prompt).contains("개인 분석");
			assertThat(prompt).contains("궁합 분석");
			assertThat(prompt).contains("결혼");
		}

		@Test
		@DisplayName("오늘의 운세 프롬프트는 네 개 분야 섹션을 모두 담는다")
		void todayFortuneKeepsFourSections() {
			String prompt = free(105L);

			assertThat(prompt).contains("## 1. 오늘의 총운");
			assertThat(prompt).contains("## 2. 재물운/금전운");
			assertThat(prompt).contains("## 3. 애정운");
			assertThat(prompt).contains("## 4. 성취운");
		}

		@Test
		@DisplayName("3월 월간운세 프롬프트는 고정 섹션 순서를 모두 담는다")
		void marchMonthlyKeepsFixedSections() {
			String prompt = free(106L);

			assertThat(prompt).contains("--- [작성할 섹션 고정 순서] ---");
			assertThat(prompt).contains("3월 핵심 키워드", "금전운", "연애운", "학업운",
				"직장/일운", "건강운", "주의할 점과 조언", "3월운 총평");
		}
	}

	@Nested
	@DisplayName("출력 작성 규칙")
	class OutputRules {

		/** 스키마는 필드 존재만 강제한다. 제목 표기, 강조 기호, 문단 길이, summary 규칙은 프롬프트 몫이다. */
		@ParameterizedTest(name = "{0}번 상품은 fullAnalysis/summary 작성 규칙을 담는다")
		@ValueSource(longs = {1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L})
		void sajuPromptsKeepWritingRules(long subcategoryId) {
			String prompt = saju(subcategoryId);

			assertThat(prompt).contains("--- [fullAnalysis 작성 규칙] ---");
			assertThat(prompt).contains("`##` 기호 대신 대괄호로 감싸 출력하고");
			assertThat(prompt).contains("`**` 강조 기호는 출력하지 않습니다.");
			assertThat(prompt).contains("한 문단이 6~7줄을 넘지 않게 끊습니다.");
			assertThat(prompt).contains("--- [summary 작성 규칙] ---");
			assertThat(prompt).contains("총 250자 이내로 담고");
			assertThat(prompt).contains("마침표는 찍지 않습니다");
		}

		@ParameterizedTest(name = "{0}번 궁합 상품은 interpretation/score/summary 작성 규칙을 담는다")
		@ValueSource(longs = {4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L})
		void compatibilityPromptsKeepWritingRules(long subcategoryId) {
			String prompt = compatibility(subcategoryId);

			assertThat(prompt).contains("--- [interpretation 작성 규칙] ---");
			assertThat(prompt).contains("`**` 강조 기호는 출력하지 않습니다.");
			assertThat(prompt).contains("--- [score] ---");
			assertThat(prompt).contains("두 사람의 종합 궁합을 0~100 사이 정수로 담습니다.");
			assertThat(prompt).contains("--- [summary 작성 규칙] ---");
			assertThat(prompt).contains("총 250자 이내로 담고");
			assertThat(prompt).contains("마침표는 찍지 않습니다");
		}

		@ParameterizedTest(name = "{0}번 장문 유료 상품은 단락 서식과 summary 길이 규칙을 담는다")
		@ValueSource(longs = {21L, 22L, 23L})
		void longformPromptsKeepOutputRules(long subcategoryId) {
			String prompt = saju(subcategoryId);

			assertThat(prompt).contains("### 최종 출력 형식 ###");
			assertThat(prompt).contains("fullAnalysis에는 번호형 목차, 대괄호 제목, 목록 기호 없이 순수 문장 단락만 작성한다.");
			assertThat(prompt).contains("summary는 4~5줄로 작성하고, 핵심 행동만 짧게 정리한다.");
			assertThat(prompt).contains("summary 총 길이는 280자 이내로 제한한다.");
		}

		@Test
		@DisplayName("재물운 프롬프트는 자체 출력 규칙과 목표 길이를 담는다")
		void moneyLuckKeepsOwnOutputRules() {
			String prompt = saju(20L);

			assertThat(prompt).contains("### 최종 출력 형식 ###");
			assertThat(prompt).contains("fullAnalysis 길이는 3800자 이상 4600자 이하를 지킨다.");
			assertThat(prompt).contains("summary는 4~5줄로 작성하고 총 길이는 280자 이내로 제한한다.");
		}
	}

	@Nested
	@DisplayName("Structured Outputs 가 강제하는 지시는 남지 않는다")
	class RemovedInstructions {

		static Stream<Arguments> everyPrompt() {
			Stream.Builder<Arguments> builder = Stream.builder();
			for (long id : new long[]{1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L, 22L, 23L}) {
				builder.add(Arguments.of("saju " + id, saju(id)));
			}
			for (long id : new long[]{4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L}) {
				builder.add(Arguments.of("compatibility " + id, compatibility(id)));
			}
			for (long id : new long[]{101L, 102L, 103L, 104L, 105L, 106L}) {
				builder.add(Arguments.of("free " + id, free(id)));
			}
			return builder.build();
		}

		@ParameterizedTest(name = "{0} 프롬프트에는 필드 구성 설명과 JSON 이스케이프 표기가 없다")
		@MethodSource("everyPrompt")
		void noSchemaEnforcedInstructionsRemain(String label, String prompt) {
			assertThat(prompt)
				.as("%s 프롬프트", label)
				.doesNotContain("JSON 형식은 시스템이 강제하므로")
				.doesNotContain("두 필드로 구성")
				.doesNotContain("세 필드로 구성")
				// 역슬래시 n 은 JSON 문자열 이스케이프 표기다. 실제 줄바꿈을 뜻하는 말로 바꿨다.
				.doesNotContain("\\n")
				.doesNotContain("```");
		}
	}
}
