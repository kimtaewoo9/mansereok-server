package com.mansereok.server.domain.interpret.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 규칙별 단위 테스트. 기대 결과 비교 테스트가 "예전과 같은가" 를 지킨다면 여기서는 "왜 이렇게 다듬는가" 를 적는다.
 */
@DisplayName("해석 후처리")
class AnalysisNormalizerTest {

	private final AnalysisNormalizer normalizer = new AnalysisNormalizer();

	private static long paragraphCount(String text) {
		return Arrays.stream(text.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.count();
	}

	@Nested
	@DisplayName("경계값")
	class Boundaries {

		@Test
		@DisplayName("null 본문과 요약은 null 을 그대로 돌려준다")
		void returnsNullForNullText() {
			assertThat(normalizer.normalizeAnalysis(21L, null)).isNull();
			assertThat(normalizer.normalizeSummary(21L, null)).isNull();
		}

		@Test
		@DisplayName("subcategoryId 가 null 이면 아무것도 손대지 않는다")
		void leavesTextUntouchedWhenSubcategoryIdIsNull() {
			String raw = "[1. 총운]\n1-1 그대로 남아야 합니다.";

			assertThat(normalizer.normalizeAnalysis(null, raw)).isEqualTo(raw);
			assertThat(normalizer.normalizeSummary(null, raw)).isEqualTo(raw);
		}

		@ParameterizedTest(name = "규칙이 없는 상품 {0} 은 원문 그대로다")
		@ValueSource(longs = {1L, 2L, 5L, 19L, 999L})
		@DisplayName("표에 없는 상품은 원문 그대로다")
		void leavesTextUntouchedForUnknownSubcategory(long subcategoryId) {
			String raw = "[1. 총운]\n1-1 그대로 남아야 합니다.\n### 머리말";

			assertThat(normalizer.normalizeAnalysis(subcategoryId, raw)).isEqualTo(raw);
			assertThat(normalizer.normalizeSummary(subcategoryId, raw)).isEqualTo(raw);
		}

		@ParameterizedTest(name = "빈 문자열 입력({0})은 빈 문자열을 돌려준다")
		@ValueSource(longs = {20L, 21L, 101L, 102L, 104L, 106L})
		@DisplayName("빈 문자열은 빈 문자열로 남는다")
		void keepsBlankTextBlank(long subcategoryId) {
			assertThat(normalizer.normalizeAnalysis(subcategoryId, "")).isEmpty();
			assertThat(normalizer.normalizeAnalysis(subcategoryId, "   \n\n  \n")).isEmpty();
			assertThat(normalizer.normalizeSummary(subcategoryId, "")).isEmpty();
		}

		@Test
		@DisplayName("규칙에 걸리지 않는 평문은 내용이 그대로 남는다")
		void keepsPlainTextUnchanged() {
			String raw = "돈의 흐름은 천천히 좋아집니다. 무리한 투자만 피하면 됩니다.";

			assertThat(normalizer.normalizeAnalysis(20L, raw)).isEqualTo(raw);
			assertThat(normalizer.normalizeAnalysis(21L, raw)).isEqualTo(raw);
		}
	}

	@Nested
	@DisplayName("공통 규칙")
	class CommonRules {

		@Test
		@DisplayName("대괄호 목차 제목과 번호 표시, 마크다운 머리말을 지운다")
		void stripsStructuralLabels() {
			String normalized = normalizer.normalizeAnalysis(21L, """
				[1. 성격 분석 + 사주적 근거]
				1-1 핵심 성향은 임수 일간입니다.
				### 다음 단락
				3) 실행 체크리스트를 작성하세요.
				""");

			assertThat(normalized)
				.doesNotContain("[1. 성격 분석 + 사주적 근거]")
				.doesNotContain("1-1 ")
				.doesNotContain("###")
				.doesNotContain("3) ")
				.contains("핵심 성향은 임수 일간입니다.");
		}

		@Test
		@DisplayName("PAGE_BREAK 표시는 빈 줄 하나로 바뀐다")
		void pageBreakBecomesBlankLine() {
			String normalized = normalizer.normalizeAnalysis(21L,
				"앞 단락입니다.\n[PAGE_BREAK]\n뒤 단락입니다.");

			assertThat(normalized).doesNotContain("PAGE_BREAK");
			assertThat(paragraphCount(normalized)).isEqualTo(2);
		}

		@Test
		@DisplayName("기간 표기는 연·월 한국어로 통일한다")
		void unifiesPeriodNotation() {
			String normalized = normalizer.normalizeAnalysis(21L,
				"유리 구간은 2026-02-04T04:38~2026-04-05T03:32:59입니다. 2026-06-06 01:09도 좋습니다.");

			assertThat(normalized)
				.contains("2026년 2월~2026년 4월")
				.contains("2026년 6월")
				.doesNotContain("T03:32:59")
				.doesNotContain("01:09");
		}

		@Test
		@DisplayName("빈 줄이 세 줄 이상이면 한 줄로 줄인다")
		void collapsesBlankLines() {
			String normalized = normalizer.normalizeAnalysis(21L,
				"앞 단락입니다.\n\n\n\n\n뒤 단락입니다.");

			assertThat(normalized).isEqualTo("앞 단락입니다.\n\n뒤 단락입니다.");
		}
	}

	@Nested
	@DisplayName("재물운(20)")
	class MoneyLuck {

		@Test
		@DisplayName("A. B. 꼴 영문 절 제목을 지운다")
		void removesLetteredSectionTitles() {
			String normalized = normalizer.normalizeAnalysis(20L, """
				[A. 돈벼락 가능성]
				인생의 판이 바뀔 수 있습니다.
				""");

			assertThat(normalized)
				.doesNotContain("[A. 돈벼락 가능성]")
				.contains("인생의 판이 바뀔 수 있습니다.");
		}

		@Test
		@DisplayName("두 줄로 쪼개진 대괄호 제목 조각을 붙여서 대괄호만 지운다")
		void repairsBrokenBracketTitle() {
			String normalized = normalizer.normalizeAnalysis(20L, """
				흐름이 바뀝니다.
				[주의할 점과
				조언]
				지출 구조를 봅니다.
				""");

			assertThat(normalized)
				.doesNotContain("[주의할 점과")
				.doesNotContain("조언]")
				.contains("주의할 점과 조언");
		}

		@Test
		@DisplayName("깨진 아랍 문자 토큰을 흡인력으로 되살리고 남은 비정상 문자는 지운다")
		void repairsCorruptedUnicode() {
			String normalized = normalizer.normalizeAnalysis(20L,
				"جذب力이 강하게 작동합니다. дом 도 섞여 있습니다.");

			assertThat(normalized)
				.contains("흡인력이 강하게 작동합니다.")
				.doesNotContain("جذب")
				.doesNotContain("дом");
		}

		@Test
		@DisplayName("연속 공백은 한 칸으로 줄인다")
		void collapsesMultipleSpaces() {
			String normalized = normalizer.normalizeAnalysis(20L,
				"지출  구조를   다시 봅니다.");

			assertThat(normalized).isEqualTo("지출 구조를 다시 봅니다.");
		}
	}

	@Nested
	@DisplayName("사업운·학업운·인생조언(21, 22, 23)")
	class Business {

		@ParameterizedTest(name = "{0} 은 사업운 규칙을 쓴다")
		@ValueSource(longs = {21L, 22L, 23L})
		@DisplayName("세 상품이 같은 규칙을 공유한다")
		void businessAcademicLifeAdviceShareOneRule(long subcategoryId) {
			String normalized = normalizer.normalizeAnalysis(subcategoryId, "천간충이 있어요.");

			assertThat(normalized).contains("천간 충돌(생각과 실행이 맞부딪히는 구조)");
		}

		@Test
		@DisplayName("명리 용어를 괄호 설명으로 풀어 준다")
		void expandsJargon() {
			String normalized = normalizer.normalizeAnalysis(21L,
				"천간충과 양인살과 역마살이 함께 움직입니다.");

			assertThat(normalized)
				.contains("천간 충돌(")
				.contains("양인살(")
				.contains("역마살(");
		}

		@Test
		@DisplayName("요청하지 않은 개운법 줄을 지운다")
		void removesForbiddenAdvice() {
			String normalized = normalizer.normalizeAnalysis(21L, """
				핵심 성향은 임수 일간입니다.
				동쪽에서 청색을 쓰고 숫자 3과 8을 추천합니다.
				""");

			assertThat(normalized)
				.contains("핵심 성향은 임수 일간입니다.")
				.doesNotContain("동쪽")
				.doesNotContain("청색")
				.doesNotContain("3과 8");
		}

		@Test
		@DisplayName("내부 계산값(오행 점수)이 노출된 줄과 한자 괄호를 지운다")
		void removesInternalScores() {
			String normalized = normalizer.normalizeAnalysis(21L, """
				오행 점수는 목 3.2 화 1.1 입니다.
				비겁(比劫)이 많아 경쟁이 잦습니다.
				""");

			assertThat(normalized)
				.doesNotContain("오행 점수")
				.doesNotContain("(比劫)")
				.contains("비겁이 많아 경쟁이 잦습니다.");
		}

		@Test
		@DisplayName("요약은 다섯 줄까지만 남긴다")
		void limitsSummaryToFiveLines() {
			String normalized = normalizer.normalizeSummary(21L, """
				첫째 줄
				둘째 줄
				셋째 줄
				넷째 줄
				다섯째 줄
				여섯째 줄
				일곱째 줄
				""");

			assertThat(normalized.lines().filter(line -> !line.isBlank()).count()).isEqualTo(5);
			assertThat(normalized).contains("다섯째 줄").doesNotContain("여섯째 줄");
		}

		@Test
		@DisplayName("요약은 280자를 넘지 않는다")
		void limitsSummaryLength() {
			String normalized = normalizer.normalizeSummary(21L, "가".repeat(400));

			assertThat(normalized).hasSize(280);
		}

		@Test
		@DisplayName("요약은 본문과 달리 줄바꿈을 문단으로 합치지 않는다")
		void summaryKeepsLineBreaks() {
			String normalized = normalizer.normalizeSummary(21L, "첫째 줄\n둘째 줄");

			assertThat(normalized).isEqualTo("첫째 줄\n둘째 줄");
		}
	}

	@Nested
	@DisplayName("무료 운세(101~106)")
	class FreeFortune {

		@ParameterizedTest(name = "{0} 은 소제목 앞에서 문단을 끊는다")
		@ValueSource(longs = {101L, 103L, 105L})
		@DisplayName("소제목 기반 상품은 문단이 세 개 이상으로 나뉜다")
		void splitsByTopicMarkers(long subcategoryId) {
			String raw = switch ((int) subcategoryId) {
				case 101 -> """
					환경의 변화에서 이동수가 강하게 들어오며 일과 생활 리듬이 바뀔 가능성이 큽니다. 인간관계의 변화에서는 새로운 협업 제안이 들어오지만 조율이 필요합니다. 연애와 애정운은 감정 기복을 관리하면 안정적으로 이어질 흐름입니다. 학업 및 성취운은 집중력만 유지하면 성과가 확실히 보이는 구간입니다. 건강 및 컨디션은 수면과 회복 루틴을 먼저 잡아야 낙폭이 줄어듭니다.
					""";
				case 103 -> """
					당신의 매력 포인트는 무대 장악력과 솔직한 에너지입니다. 나만의 플러팅 비법은 과한 설명보다 짧고 정확한 표현으로 상대의 반응을 끌어내는 방식입니다. 이것만은 주의하세요 감정이 올라왔을 때 말의 강도가 높아지면 오해가 빠르게 커질 수 있습니다.
					""";
				default -> """
					오늘의 총운은 속도를 낮추고 우선순위를 정리할수록 결과가 좋아지는 흐름입니다. 재물운에서는 즉흥 결제보다 검토 후 지출이 안정성을 높입니다. 애정운은 경청이 핵심이며 성취운은 작은 마감부터 끝내는 방식이 효율을 올립니다.
					""";
			};

			assertThat(paragraphCount(normalizer.normalizeAnalysis(subcategoryId, raw)))
				.isGreaterThanOrEqualTo(3);
		}

		@Test
		@DisplayName("키워드 운세(102)는 상담 멘트와 AI 언급을 지우고 제목은 남긴다")
		void keywordRemovesMetaPhrases() {
			String normalized = normalizer.normalizeAnalysis(102L, """
				[2026년 상반기 운명 키워드: 균형]
				안녕하세요 은정님, 직접 대면 상담하듯 핵심만 전해드립니다. AI가 분석한 결과 균형이 핵심입니다. 속도를 조절해야 합니다.
				""");

			assertThat(normalized)
				.doesNotContain("직접 대면 상담하듯")
				.doesNotContain("핵심만 전해드립니다")
				.doesNotContain("AI가 분석한 결과")
				.contains("[2026년 상반기 운명 키워드: 균형]");
		}

		@Test
		@DisplayName("키워드 운세(102)는 요약에서도 상담 멘트를 지운다")
		void keywordSummaryRemovesMetaPhrases() {
			String normalized = normalizer.normalizeSummary(102L,
				"직접 대면 상담하듯 핵심만 전해드립니다. 균형이 핵심입니다");

			assertThat(normalized)
				.doesNotContain("직접 대면 상담하듯")
				.contains("균형이 핵심입니다");
		}

		@Test
		@DisplayName("다른 무료 운세의 요약은 줄바꿈 정리만 한다")
		void otherFreeSummaryOnlyCollapsesBlankLines() {
			String normalized = normalizer.normalizeSummary(101L, "첫 문장\n\n\n\n둘째 문장");

			assertThat(normalized).isEqualTo("첫 문장\n\n둘째 문장");
		}

		@Test
		@DisplayName("케미 궁합(104)은 추천 라벨을 지우고 추천 항목마다 문단을 나눈다")
		void chemistrySplitsByRecommendationMarkers() {
			String normalized = normalizer.normalizeAnalysis(104L, """
				[아이돌 추천] 병화 기운이 강한 분에게는 감정의 온도를 조절해주는 유형이 잘 맞습니다. 블랙핑크의 지수는 안정적인 정서 리듬을 만들어 주는 점이 강점입니다. 다만 감정표현 속도 차이는 주의가 필요합니다. [배우 추천] 배우 박보영은 부드러운 공감력으로 감정 파고를 낮춰주는 타입입니다. 종합 원픽 TOP3 1위: 지수 2위: 박보영 3위: 루피
				""");

			assertThat(normalized)
				.doesNotContain("[아이돌 추천]")
				.doesNotContain("[배우 추천]")
				.contains("종합 원픽 TOP3");
			assertThat(paragraphCount(normalized)).isGreaterThanOrEqualTo(3);
		}

		@Test
		@DisplayName("3월 월운(106)은 깨진 섹션 제목을 되살리고 정해진 순서로 재조립한다")
		void marchMonthlyRepairsHeaders() {
			String normalized = normalizer.normalizeAnalysis(106L, """
				[3월운 총평]
				3월은 관리가 동반되어야 성과가 남는 달입니다.

				[주의할 점과

				조언]
				선택의 유혹이 큽니다.

				[3월 핵심 키워드]
				아이디어가 잘 떠오르는 흐름입니다.
				""");

			assertThat(normalized)
				.doesNotContain("[3월 핵심 키워드]")
				.doesNotContain("[주의할 점과")
				.doesNotContain("조언]")
				.contains("3월 핵심 키워드\n")
				.contains("주의할 점과 조언\n");
			assertThat(normalized.indexOf("3월 핵심 키워드"))
				.as("섹션은 화면이 기대하는 순서로 재조립된다")
				.isLessThan(normalized.indexOf("주의할 점과 조언"));
			assertThat(normalized.indexOf("주의할 점과 조언"))
				.isLessThan(normalized.indexOf("3월운 총평"));
		}

		@Test
		@DisplayName("3월 월운(106)은 섹션을 230자 부근의 문장 끝에서 자른다")
		void marchMonthlyCapsSectionLength() {
			String sentence = "재정의 운용은 손실에 조금 더 노출될 수 있습니다. ";
			String normalized = normalizer.normalizeAnalysis(106L,
				"[금전운]\n" + sentence.repeat(20));

			String section = normalized.substring(normalized.indexOf("금전운") + "금전운\n".length());
			// 상한을 넘기면 직전 문장 끝까지만 남긴다. 종결 표지("다.")를 포함하느라 상한을 두어 글자 넘길 수 있다.
			assertThat(section.length()).isBetween(126, 233);
			assertThat(section).endsWith("다.");
		}

		@Test
		@DisplayName("3월 월운(106)은 알려진 섹션이 하나도 없으면 빈 문자열이 된다")
		void marchMonthlyBecomesEmptyWithoutKnownSections() {
			assertThat(normalizer.normalizeAnalysis(106L, "섹션 제목이 전혀 없는 본문입니다.")).isEmpty();
		}
	}
}
