package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 아이돌·배우 궁합 프롬프트.
 */
final class CelebrityCompatibilityPrompts {

	private CelebrityCompatibilityPrompts() {
	}

	// ==================== [수정] 7. 아이돌 궁합 프롬프트 (혜안 적용) ====================
	static String createIdolCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (createIdolCompatibilityPrompt에서 가져와 강화)
		prompt.append("""

			### ⚠️ 매우 중요: 3인칭 서술 (아이돌 팬픽 관점) ###
			""");
		prompt.append(
			String.format("- 이 분석은 '%s'와 '%s'라는 제3자(아이돌)들에 대한 것입니다.\n", person1Name, person2Name));
		prompt.append("""
			- 절대로 2인칭(당신, 당신들)을 사용하지 마세요.
			""");
		prompt.append(
			String.format("- [개인 분석]은 '%s님은...', '%s님은...' 처럼 3인칭 단수로 서술해야 합니다.\n", person1Name,
				person2Name));
		prompt.append("""
			- [궁합 분석]은 '두 사람은...', '%s님과 %s님은...' 처럼 3인칭 관찰자 시점으로 서술해야 합니다.
			- 팬들의 상상력을 자극할 수 있는 서사적이고 감성적인 어조를 사용해주세요.
			"""
			.formatted(person1Name, person2Name));

		// 3. 분석 대상자들 정보 주입
		prompt.append("""

			### 3. 분석 대상자 상세 정보 ###
			""");
		prompt.append("--- 첫 번째 아이돌: ").append(person1Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person1Name, person1Response);

		// 🔥 [추가 1] Person 1 팩트 주입
		SajuKeywordSections.appendKeywords(prompt, person1Response);

		prompt.append("\n--- 두 번째 아이돌: ").append(person2Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person2Name, person2Response);

		SajuKeywordSections.appendKeywords(prompt, person2Response);

		// 4. 분석 구조 설명 (3단계로 수정)
		prompt.append("""

			### 4. [분석 구조] ###
			1. %s님 개인 심층 분석
			2. %s님 개인 심층 분석
			3. %s님과 %s님의 관계 서사 (궁합)

			"""
			.formatted(person1Name, person2Name, person1Name, person2Name));

		// ===== 5. 1단계: 첫 번째 사람 개인 분석 =====
		prompt.append("### 5. [1단계: ").append(person1Name).append("님 개인 심층 분석] ###\n");
		prompt.append("""
			먼저 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.

			"""
			.formatted(person1Name));

		prompt.append("""
			타고난 성격과 가치관
			%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.
			%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.
			일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.

			"""
			.formatted(person1Name, person1Name));

		prompt.append("""
			연애 스타일과 특징
			%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)
			%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 분석
			%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?

			"""
			.formatted(person1Name, person1Name, person1Name));

		prompt.append("""
			이상형과 끌리는 타입
			%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.
			%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.

			"""
			.formatted(person1Name, person1Name));

		// ===== 6. 2단계: 두 번째 사람 개인 분석 (추가된 부분) =====
		prompt.append("### 6. [2단계: ").append(person2Name).append("님 개인 심층 분석] ###\n");
		prompt.append("""
			다음으로 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.

			"""
			.formatted(person2Name));

		prompt.append("""
			타고난 성격과 가치관
			%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.
			%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.
			일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.

			"""
			.formatted(person2Name, person2Name));

		prompt.append("""
			연애 스타일과 특징
			%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)
			%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 재미있게 풀어서 설명
			%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?

			"""
			.formatted(person2Name, person2Name, person2Name));

		prompt.append("""
			이상형과 끌리는 타입
			%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.
			%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.

			"""
			.formatted(person2Name, person2Name));

		// ===== 7. 3단계: 두 사람 궁합 분석 (번호 수정 및 내용 보강) =====
		prompt.append("\n### 7. [3단계: ").append(person1Name).append("님과 ").append(person2Name)
			.append("님의 관계 서사] ###\n");
		prompt.append("""
			이제 [1단계]와 [2단계]의 개인 분석을 바탕으로, %s님과 %s님 두 분의 궁합을 단계별로 분석해주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			"%s님과 %s님, 두 분의 사주 인연을 보니... [두 사람의 일간을 자연물에 비유]처럼 [어떤 느낌]의 만남이네요." 라는 느낌으로 시작해주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			첫 만남: 서로의 첫인상
			%s님이 %s님을 처음 봤을 때 어떤 인상을 받을까요? (호감/무관심/경계)
			%s님이 %s님을 처음 봤을 때는 어떨까요?
			누가 먼저 다가가거나 호감을 표현할 가능성이 높은지 [1, 2단계 성격]을 기반으로 예측해주세요.
			**[중요]** %s님의 이상형([1단계 분석])과 %s님의 매력([2단계 분석])이 얼마나 부합하는지 교차 분석해주세요.
			**[중요]** %s님의 이상형([2단계 분석])과 %s님의 매력([1단계 분석])이 얼마나 부합하는지 교차 분석해주세요.

			"""
			.formatted(person1Name, person2Name, person2Name, person1Name, person1Name, person2Name, person2Name, person1Name));

		prompt.append("""
			썸과 관계 발전
			관계가 친구에서 연인으로 발전하는 속도는? (빠른 편/천천히)
			썸 기간 동안 누가 관계를 리드하고, 밀당 주도권은 누가 쥘까요?
			서로의 어떤 점에 매력을 느끼고 마음을 열게 될까요? (구체적으로)
			두 사람의 오행 관계(상생/상극)를 쉽게 풀어서 설명하고, 서로에게 어떤 영향을 주는지 분석해주세요.

			""");

		prompt.append("""
			연애의 모습: 두 사람만의 케미
			연인이 된 후 두 사람의 데이트 스타일은? (활동적/조용한/감성적)
			애정 표현 방식이 서로 잘 맞을까요? 차이가 있다면 어떻게 조율해야 할까요?
			스킨십 성향과 친밀도는?
			**[질투 분석] 누가 더 질투심이 많을까요?** 각자의 사주(비겁, 관성, 일간 특성 등)를 근거로 누가 어떤 상황에서 질투를 느끼는지, 그리고 어떻게 표현하는지 서사적으로 분석해주세요.
			연애 vs 일, 두 사람의 우선순위는 비슷할까요? 다를까요?
			성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형)을 **사주 용어를 쉽게 풀어서** 설명해주세요. AI 티 나는 말투 절대 금지

			""");

		prompt.append("""
			갈등과 극복
			두 사람 사이에 발생할 수 있는 주요 갈등 원인 3가지 (성격 차이, 가치관 충돌, 생활 패턴 등)를 예측하고, **지지 충/형을 쉽게 풀어서** 설명해주세요.
			관계의 위기(권태기, 이별 위기)가 올 수 있는 시점은 언제일까요?
			각자의 특성을 고려했을 때, 갈등을 어떻게 극복하는 유형인지 구체적으로 설명해주세요.

			""");

		prompt.append("""
			미래: 결혼 가능성과 장기 전망
			%s님의 결혼관([1단계 분석])과 %s님의 결혼관([2단계 분석])을 비교 분석해주세요.
			이 커플의 결혼 가능성은? (냉철하게 판단해도 됩니다. 높음/중간/낮음)
			만약 결혼한다면 몇 년도에 할 가능성이 높은지, 대운과 세운을 참고하여 **구체적인 연도** 예측해주세요.
			두 사람의 인연에 대한 최종 조언과 응원 메시지로 따뜻하게 마무리해주세요.

			"""
			.formatted(person1Name, person2Name));

		PromptSections.appendCompatibilityOutputFormat(prompt);

		return prompt.toString();
	}

	// ==================== 15. 배우 궁합 프롬프트 ====================
	static String createActorCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (배우용으로 수정)
		prompt.append("""

			### ⚠️ 매우 중요: 3인칭 서술 (배우 팬픽 관점) ###
			""");
		prompt.append(
			String.format("- 이 분석은 '%s'와 '%s'라는 제3자(배우)들에 대한 것입니다.\n", person1Name, person2Name));
		prompt.append("""
			- 절대로 2인칭(당신, 당신들)을 사용하지 마세요.
			""");
		prompt.append(
			String.format("- [개인 분석]은 '%s님은...', '%s님은...' 처럼 3인칭 단수로 서술해야 합니다.\n", person1Name,
				person2Name));
		prompt.append("""
			- [궁합 분석]은 '두 사람은...', '%s님과 %s님은...' 처럼 3인칭 관찰자 시점으로 서술해야 합니다.
			- 팬들의 상상력을 자극할 수 있는 서사적이고 감성적인 어조를 사용해주세요.
			"""
			.formatted(person1Name, person2Name));

		// 3. 분석 대상자들 정보 주입
		prompt.append("""

			### 3. 분석 대상자 상세 정보 ###
			""");
		prompt.append("--- 첫 번째 배우: ").append(person1Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person1Name, person1Response);
		SajuKeywordSections.appendKeywords(prompt, person1Response);

		prompt.append("\n--- 두 번째 배우: ").append(person2Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person2Name, person2Response);

		SajuKeywordSections.appendKeywords(prompt, person2Response);

		// 4. 분석 구조 설명 (3단계로 수정)
		prompt.append("""

			### 4. [분석 구조] ###
			1. %s님 개인 심층 분석
			2. %s님 개인 심층 분석
			3. %s님과 %s님의 관계 서사 (궁합)

			"""
			.formatted(person1Name, person2Name, person1Name, person2Name));

		// ===== 5. 1단계: 첫 번째 사람 개인 분석 =====
		prompt.append("### 5. [1단계: ").append(person1Name).append("님 개인 심층 분석] ###\n");
		prompt.append("""
			먼저 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.

			"""
			.formatted(person1Name));

		prompt.append("""
			타고난 성격과 가치관
			%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.
			%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.
			일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.

			"""
			.formatted(person1Name, person1Name));

		prompt.append("""
			연애 스타일과 특징
			%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)
			%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 분석
			%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?

			"""
			.formatted(person1Name, person1Name, person1Name));

		prompt.append("""
			이상형과 끌리는 타입
			%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.
			%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.

			"""
			.formatted(person1Name, person1Name));

		// ===== 6. 2단계: 두 번째 사람 개인 분석 (추가된 부분) =====
		prompt.append("### 6. [2단계: ").append(person2Name).append("님 개인 심층 분석] ###\n");
		prompt.append("""
			다음으로 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.

			"""
			.formatted(person2Name));

		prompt.append("""
			타고난 성격과 가치관
			%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.
			%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.
			일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.

			"""
			.formatted(person2Name, person2Name));

		prompt.append("""
			연애 스타일과 특징
			%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)
			%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 재미있게 풀어서 설명
			%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?

			"""
			.formatted(person2Name, person2Name, person2Name));

		prompt.append("""
			이상형과 끌리는 타입
			%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.
			%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.

			"""
			.formatted(person2Name, person2Name));

		// ===== 7. 3단계: 두 사람 궁합 분석 (번호 수정 및 내용 보강) =====
		prompt.append("\n### 7. [3단계: ").append(person1Name).append("님과 ").append(person2Name)
			.append("님의 관계 서사] ###\n");
		prompt.append("""
			이제 [1단계]와 [2단계]의 개인 분석을 바탕으로, %s님과 %s님 두 분의 궁합을 단계별로 분석해주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			"%s님과 %s님, 두 분의 사주 인연을 보니... [두 사람의 일간을 자연물에 비유]처럼 [어떤 느낌]의 만남이네요." 라는 느낌으로 시작해주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			첫 만남: 서로의 첫인상
			%s님이 %s님을 처음 봤을 때 어떤 인상을 받을까요? (호감/무관심/경계)
			%s님이 %s님을 처음 봤을 때는 어떨까요?
			누가 먼저 다가가거나 호감을 표현할 가능성이 높은지 [1, 2단계 성격]을 기반으로 예측해주세요.
			**[중요]** %s님의 이상형([1단계 분석])과 %s님의 매력([2단계 분석])이 얼마나 부합하는지 교차 분석해주세요.
			**[중요]** %s님의 이상형([2단계 분석])과 %s님의 매력([1단계 분석])이 얼마나 부합하는지 교차 분석해주세요.

			"""
			.formatted(person1Name, person2Name, person2Name, person1Name, person1Name, person2Name, person2Name, person1Name));

		prompt.append("""
			썸과 관계 발전
			관계가 친구에서 연인으로 발전하는 속도는? (빠른 편/천천히)
			썸 기간 동안 누가 관계를 리드하고, 밀당 주도권은 누가 쥘까요?
			서로의 어떤 점에 매력을 느끼고 마음을 열게 될까요? (구체적으로)
			두 사람의 오행 관계(상생/상극)를 쉽게 풀어서 설명하고, 서로에게 어떤 영향을 주는지 분석해주세요.

			""");

		prompt.append("""
			연애의 모습: 두 사람만의 케미
			연인이 된 후 두 사람의 데이트 스타일은? (활동적/조용한/감성적)
			애정 표현 방식이 서로 잘 맞을까요? 차이가 있다면 어떻게 조율해야 할까요?
			스킨십 성향과 친밀도는?
			**[질투 분석] 누가 더 질투심이 많을까요?** 각자의 사주(비겁, 관성, 일간 특성 등)를 근거로 누가 어떤 상황에서 질투를 느끼는지, 그리고 어떻게 표현하는지 서사적으로 분석해주세요.
			연애 vs 일, 두 사람의 우선순위는 비슷할까요? 다를까요?
			성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형)을 **사주 용어를 쉽게 풀어서** 설명해주세요. AI 티 나는 말투 절대 금지

			""");

		prompt.append("""
			갈등과 극복
			두 사람 사이에 발생할 수 있는 주요 갈등 원인 3가지 (성격 차이, 가치관 충돌, 생활 패턴 등)를 예측하고, **지지 충/형을 쉽게 풀어서** 설명해주세요.
			관계의 위기(권태기, 이별 위기)가 올 수 있는 시점은 언제일까요?
			각자의 특성을 고려했을 때, 갈등을 어떻게 극복하는 유형인지 구체적으로 설명해주세요.

			""");

		prompt.append("""
			미래: 결혼 가능성과 장기 전망
			%s님의 결혼관([1단계 분석])과 %s님의 결혼관([2단계 분석])을 비교 분석해주세요.
			이 커플의 결혼 가능성은? (냉철하게 판단해도 됩니다. 높음/중간/낮음)
			만약 결혼한다면 몇 년도에 할 가능성이 높은지, 대운과 세운을 참고하여 **구체적인 연도** 예측해주세요.
			두 사람의 인연에 대한 최종 조언과 응원 메시지로 따뜻하게 마무리해주세요.

			"""
			.formatted(person1Name, person2Name));

		PromptSections.appendCompatibilityOutputFormat(prompt);

		return prompt.toString();
	}
}
