package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 연애·삼각관계 궁합 프롬프트.
 */
final class CompatibilityPrompts {

	private CompatibilityPrompts() {
	}

	// ==================== 4,6,14 러브 스토리 프롬프트 (혜안 적용) ====================
	static String createLoveStoryPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 궁합 페르소나 주입
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 분석 대상자들 정보 주입
		prompt.append("""

			### 5. 분석 대상자 상세 정보 ###
			""");

		// --- 사람 1 ---
		prompt.append("--- 첫 번째 사람 정보: ").append(person1Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person1Name, person1Response);

		// 🔥 [추가 1] Person 1 팩트 주입
		SajuKeywordSections.appendKeywords(prompt, person1Response);

		// --- 사람 2 ---
		prompt.append("\n--- 두 번째 사람 정보: ").append(person2Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 🔥 [추가 2] Person 2 팩트 주입
		SajuKeywordSections.appendKeywords(prompt, person2Response);

		// ===== 3. 분석 구조 설명 =====
		prompt.append("""

			### 6. [분석 구조] ###
			%s님에 대한 분석
			%s님과 %s님의 궁합

			"""
			.formatted(person1Name, person1Name, person2Name));

		// ===== 4. 1단계: 첫 번째 사람 개인 분석 =====
		prompt.append("### 7. [").append(person1Name).append("님 개인 분석] ###\n");
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
			%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려)
			%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?
			%s님의 질투심 수준과 연애 vs 일의 우선순위는?

			"""
			.formatted(person1Name, person1Name, person1Name, person1Name));

		prompt.append("""
			이상형과 끌리는 타입
			%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.
			%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.
			반대로 %s님과 갈등이 생기기 쉬운 타입은 어떤 사람인지도 언급해주세요.

			"""
			.formatted(person1Name, person1Name, person1Name));

		// ===== 5. 2단계: 두 사람 궁합 분석 =====
		prompt.append("\n8. [2단계: ").append(person1Name).append("님과 ").append(person2Name)
			.append("님 궁합 분석] ###\n");
		prompt.append("""
			이제 %s님과 %s님 두 분의 궁합을 단계별로 분석해주세요.

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
			누가 먼저 다가가거나 호감을 표현할 가능성이 높은지 예측해주세요.
			%s님의 이상형 분석 결과, %s님이 그 이상형에 얼마나 부합하는지 설명해주세요.

			"""
			.formatted(person1Name, person2Name, person2Name, person1Name, person1Name, person2Name));

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
			성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형)을 **사주 용어를 쉽게 풀어서** 설명해주세요. AI 티 나는 말투 절대 금지
			연애 중 서로에게 주는 긍정적 영향은?

			""");

		prompt.append("""
			갈등과 극복
			두 사람 사이에 발생할 수 있는 주요 갈등 원인 3가지 (성격 차이, 가치관 충돌, 생활 패턴 등)를 예측하고, **지지 충/형을 쉽게 풀어서** 설명해주세요.
			관계의 위기(권태기, 이별 위기)가 올 수 있는 시점은 언제일까요?
			각자의 특성을 고려했을 때, 갈등을 어떻게 극복하는 유형인지 구체적으로 설명해주세요.
			이 커플이 오래 지속되려면 서로 어떤 노력이 필요한가요?

			""");

		prompt.append("""
			결혼 가능성과 장기 전망
			%s님의 결혼관과 %s님의 결혼관을 각각 분석하고, 두 분이 결혼에 대해 어떻게 생각하고 있을지 예측해주세요.
			이 커플의 결혼 가능성은? (냉철하게 판단해도 됩니다. 높음/중간/낮음)
			만약 결혼한다면 몇 년도에 할 가능성이 높은지, 대운과 세운을 참고하여 **구체적인 연도** 예측해주세요.
			두 사람의 인연에 대한 최종 조언과 응원 메시지로 따뜻하게 마무리해주세요.

			"""
			.formatted(person1Name, person2Name));

		PromptSections.appendCompatibilityJsonResponseFormat(prompt);

		return prompt.toString();
	}

	// ==================== [수정] 8. 삼각관계 프롬프트 (혜안 적용) ====================
	static String createTriangleRelationshipPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시
		prompt.append("""
			### ⚠️ 매우 중요: 3인칭 서술 ###
			- 이 분석은 '%s'와 '%s'라는 제3자들에 대한 것입니다.
			- 절대로 2인칭(당신들)을 사용하지 말고, **'두 사람은', '%s님은', '%s님은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.

			"""
			.formatted(person1Name, person2Name, person1Name, person2Name));

		// 3. 분석 대상자들 정보 주입
		prompt.append("""

			### 5. 분석 대상자 상세 정보 ###
			""");
		prompt.append("--- 첫 번째 사람: ").append(person1Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person1Name, person1Response);
		prompt.append("\n--- 두 번째 사람: ").append(person2Name).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 4. 분석 요청
		prompt.append("""

			### 6. [삼각관계 심층 분석] 요청 ###
			혜안 선생님, 위 두 사람(%s님, %s님)의 '관계 서사'에 제3자가 개입할 가능성과 그로 인한 **5가지 드라마틱한 국면**을 깊이 있게 작성해주세요.
			두 사람의 관계 취약점, 감정 변화, 역학 구도에 초점을 맞춰 '혜안'의 스타일로 서술하되, 국면 하나마다 최소 1문단(4~6문장) 이상으로 채워주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			--- [분석 시작] ---
			"%s님과 %s님의 '두 지도의 만남'을 보니, 기본적인 끌림과 함께 '관계의 역동성'을 불러일으키는 지점도 보이네요..." 로 시작해주세요.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			## 두 사람의 기본 관계 방정식: 끌림과 균열의 씨앗
			두 사람이 서로에게 느끼는 매력과 기본적인 관계의 강점 분석.
			겉으로 드러나지 않을 수 있는 관계의 취약점 또는 불만 요소 예측 (지지 '충/형', 오행 불균형 등).
			제3자가 비집고 들어올 수 있는 '틈'은 어디에 있는지 분석.

			""");

		prompt.append("""
			## 제3자의 등장: 어떤 인물이, 왜 끼어드는가?
			%s님 또는 %s님이 끌리기 쉬운 제3자의 사주적 특징(일간, 오행, 십성 등) 예측.
			두 사람 중 누가 먼저 마음이 흔들리거나 관계에 변화를 줄 가능성이 높은지 분석.
			제3자의 등장이 두 사람의 관계에 미치는 초기 영향력 예측.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			## 질투와 경쟁: 감정의 소용돌이
			삼각관계 상황에서 %s님과 %s님이 각각 보일 수 있는 질투의 양상과 강도 분석 (겁재, 비견 등 활용).
			누가 관계의 주도권을 쥐려 하거나 혹은 더 집착하는 모습을 보일지 예측.
			경쟁 구도 속에서 각자가 사용할 수 있는 전략이나 행동 패턴 분석.

			"""
			.formatted(person1Name, person2Name));

		prompt.append("""
			## 관계의 역학: 누가 선택하고 누가 상처받는가?
			삼각관계 구도에서 누가 심리적으로 우위에 서거나 선택하는 입장이 될 가능성이 높은지 분석.
			반대로 누가 더 큰 상처를 받거나 관계에서 밀려날 가능성이 높은지 예측.
			이 복잡한 관계가 안정될 가능성 vs 파국으로 치달을 가능성 평가.

			""");

		prompt.append("""
			## 예상 시나리오와 최종 조언
			이 삼각관계가 맞이할 가능성이 높은 결말 시나리오 1~2가지 제시 (명리학적 근거 포함).
			각 당사자(%s님, %s님, 그리고 가상의 제3자)가 이 상황을 현명하게 대처하기 위한 조언.
			관계의 복잡성 속에서도 각자가 '성장'할 수 있는 방법에 대한 메시지로 마무리.

			"""
			.formatted(person1Name, person2Name));

		PromptSections.appendCompatibilityJsonResponseFormat(prompt);

		return prompt.toString();
	}

}
