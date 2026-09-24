package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 캐릭터가 끼는 궁합 프롬프트.
 */
final class CharacterCompatibilityPrompts {

	private CharacterCompatibilityPrompts() {
	}

	// 10번 나와 캐릭터의 궁합
	// 10번: 나와 캐릭터의 궁합 (3단계 구조: 나 -> 캐릭터 -> 궁합)
	static String createCharacterCompatibilityPrompt(
		String userName, ManseryeokCalculationResponse userSaju,
		String charName, ManseryeokCalculationResponse charSaju,
		String sourceTitle
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 혜안 궁합 페르소나
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 상황 설정 (드림/이입)
		prompt.append("""
			### ⚠️ '나'와 '최애'의 심층 연애 시뮬레이션 ###
			""");
		prompt.append(
			String.format("사용자('%s')가 작품 **'%s'**의 캐릭터 **'%s'**와의 연애 궁합을 의뢰했습니다.\n", userName,
				sourceTitle, charName));
		prompt.append("""
			단순한 분석글이 아니라, **사용자가 주인공이 된 한 편의 로맨스 소설**을 읽는 듯한 **엄청난 몰입감과 풍부한 분량**을 제공하세요.
			""");
		prompt.append("**각 챕터마다 최소 5문장 이상** 서술하고, 상황 묘사와 감정선을 아주 디테일하게 풀어써야 합니다.\n"); // 분량 강제
		prompt.append("""
			※ 캐릭터 이름 유지 필수: 입력된 풀네임을 그대로 사용하세요.

			""");

		// 3. 사주 정보
		prompt.append("""

			### 3. 분석 대상 정보 ###
			--- 캐릭터 (최애): %s (%s) ---
			"""
			.formatted(charName, sourceTitle));
		SajuProfileSections.appendPersonDetailInfo(prompt, charName, charSaju);

		// 🔥 [추가 1] 캐릭터의 절대 기준(Fact) 주입
		SajuKeywordSections.appendKeywords(prompt, charSaju);

		prompt.append("\n--- 사용자 (나): ").append(userName).append(" ---\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, userName, userSaju);

		// 🔥 [추가 2] 나의 절대 기준(Fact) 주입
		SajuKeywordSections.appendKeywords(prompt, userSaju);

		// 4. 분석 구조
		prompt.append("""

			### 4. [분석 구조] ###
			1. 주인공 %s님(나)의 연애 DNA 분석
			2. 최애 %s의 숨겨진 내면과 연애관
			3. %s X %s의 로맨스 서사 (궁합 시뮬레이션)

			"""
			.formatted(userName, charName, userName, charName));

		// ===== 5. 1단계: 사용자(나) 분석 =====
		prompt.append("""
			### 5. [1단계: 주인공 '%s'님(나) 집중 탐구] ###
			먼저 사용자의 사주를 분석하여, 이 로맨스 소설의 '주인공'으로서 어떤 매력을 가졌는지 분석해주세요.

			"""
			.formatted(userName));

		prompt.append("""
			타고난 분위기와 매력 포인트
			- %s님은 태생적으로 어떤 아우라(일간/오행)를 풍기는 사람인가요? [자연물 비유]를 들어 설명해주세요.
			- 이성을 끌어당기는 결정적인 매력(도화, 홍염 등)이나 성격적 장점은 무엇인가요?

			"""
			.formatted(userName));

		prompt.append("""
			연애 스타일
			- 사랑에 빠지면 직진하는 타입인가요, 아니면 신중하게 지켜보는 타입인가요? (십성 근거)
			- 연인에게 바라는 가장 중요한 가치는 무엇인가요? (안정감, 설렘, 대화 등)

			""");

		prompt.append("""
			내 사주가 말하는 '운명의 상대'
			- 일지(배우자궁)를 볼 때, %s님은 본능적으로 어떤 스타일의 이성에게 끌리나요?

			"""
			.formatted(userName));

		prompt.append("""
			이상형
			- 이 캐릭터가 본능적으로 끌릴 수밖에 없는 상대의 분위기, 성격, 외모를 아주 상세하게 묘사해주세요.

			""");

		// ===== 6. 2단계: 캐릭터 분석 =====
		prompt.append("""
			### 6. [2단계: 최애 '%s' 집중 탐구] ###
			캐릭터의 원작 성격과 사주(일간, 십성, 신살)를 연결하여 아주 구체적으로 분석해주세요.

			"""
			.formatted(charName));

		prompt.append("""
			타고난 기질과 은밀한 매력
			- 겉으로 보이는 성격 뒤에 숨겨진 내면의 모습은 무엇인가요? (지장간, 신살 활용하여 분석)
			- 원작에서 보여준 행동들이 사주의 어떤 글자에서 비롯되었는지 구체적으로 연결해서 설명해주세요.

			""");

		prompt.append("""
			연애 스타일: 사랑에 빠진 모습
			- 평소 모습과 달리, 사랑하는 사람 앞에서는 어떻게 변할까요? (구체적인 행동 묘사 필수)
			- 집착, 질투, 혹은 회피? 사주 십성(관성, 재성 등)을 근거로 디테일하게 묘사해주세요.

			""");

		prompt.append("""
			절대적인 이상형
			- 이 캐릭터가 본능적으로 끌릴 수밖에 없는 상대의 분위기, 성격, 외모를 아주 상세하게 묘사해주세요.

			""");

		// ===== 7. 3단계: 궁합 시뮬레이션 (핵심) =====
		prompt.append("""

			### 7. [3단계: %s X %s의 로맨스 서사] ###
			**가장 중요한 파트입니다. 두 사람의 만남을 눈앞에 그려지듯 생생하게 서술하세요.**

			"""
			.formatted(userName, charName));

		prompt.append("""
			--- [분석 시작] ---
			""");

		prompt.append("""
			## 운명적 이끌림: 너는 내 취향일까?
			- **[교차 검증]** 앞서 분석한 **캐릭터의 이상형**에 %s님(사용자)이 얼마나 부합하며, 반대로 **사용자의 이상형**에 캐릭터가 얼마나 부합하는지 설명해주세요.
			- %s(캐릭터)는 %s님(사용자)의 어떤 매력 포인트(도화살, 특정 오행 등)에 시선을 뺏길까요?
			- **[상황 묘사]** 두 사람이 처음 마주치는 순간, 캐릭터가 사용자에게 건넬 첫마디나 속마음을 상상해서 적어주세요.

			"""
			.formatted(userName, charName, userName));

		prompt.append("""
			## 연애시의 온도: 케미
			- **[관계성]** 친구 같은 연인? 아니면 긴장감 넘치는 어른의 연애? 두 사람의 오행과 십성 관계를 통해 분위기를 묘사하세요.
			- **[데이트]** 두 사람이 데이트를 한다면 어디를 가고 무엇을 할까요? 사주 성향에 맞는 구체적인 데이트 코스를 추천하고 장면을 묘사해주세요.
			- **[스킨십/애정표현]** 서로의 애정 표현 방식은 잘 맞을까요? 누가 더 적극적일까요?

			""");

		prompt.append("""
			## 공략 : 마음을 얻는 방법
			- %s(캐릭터)의 마음을 확실하게 얻기 위한 '필살기(행동 지침)'를 2~3가지 구체적으로 조언해주세요.
			- 반대로, 절대 해서는 안 되는 행동(지뢰)은 무엇인가요?

			"""
			.formatted(charName));

		prompt.append("""
			## 혜안의 총평
			- 이 커플의 서사를 한 줄로 요약한다면?
			- 사용자의 '덕질'이 행복한 결말(성덕)을 맺을 수 있도록 응원의 메시지를 남겨주세요.

			""");

		PromptSections.appendCompatibilityJsonResponseFormat(prompt, userName, charName);

		return prompt.toString();
	}

	// 11번 캐릭터와 캐릭터 궁합.
	static String createCharacterToCharacterCompatibilityPrompt(
		String char1Name, ManseryeokCalculationResponse char1Saju, String char1Source,
		String char2Name, ManseryeokCalculationResponse char2Saju, String char2Source
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 혜안 궁합 페르소나 주입
		PromptSections.appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 상황 설정 (팬픽/크로스오버 관점 강화)
		prompt.append("""

			### ⚠️ 캐릭터 관계성(Chemistry) 심층 분석 ###
			""");
		prompt.append(
			String.format(
				"- 이 분석은 작품 **'%s'**의 **'%s'**와 작품 **'%s'**의 **'%s'** 간의 가상 궁합(Coupling)입니다.\n",
				char1Source, char1Name, char2Source, char2Name));
		prompt.append("""
			- 단순한 분석글이 아니라, **두 캐릭터의 서사(Narrative)를 완성하는 고퀄리티 관계 분석글**을 작성하세요.
			- 팬들이 이 글을 읽고 '이 주식은 된다(This ship is real)'라고 느낄 수 있도록 **몰입감과 분량을 극대화**해야 합니다.
			- **각 챕터마다 최소 5문장 이상** 서술하고, 상황 묘사(If)를 적극적으로 활용하세요. 말이 자연스럽게 이어지도록 글을 구성하세요.
			""");

		// 3. 분석 대상자들 정보 주입
		prompt.append("""

			### 3. 분석 대상 캐릭터 정보 ###
			--- 캐릭터 1: %s (%s) ---
			"""
			.formatted(char1Name, char1Source));
		SajuProfileSections.appendPersonDetailInfo(prompt, char1Name, char1Saju);

		// 🔥 [추가 1] 캐릭터 1의 팩트 주입
		SajuKeywordSections.appendKeywords(prompt, char1Saju);

		prompt.append("""

			--- 캐릭터 2: %s (%s) ---
			"""
			.formatted(char2Name, char2Source));
		SajuProfileSections.appendPersonDetailInfo(prompt, char2Name, char2Saju);

		// 🔥 [추가 2] 캐릭터 2의 팩트 주입
		SajuKeywordSections.appendKeywords(prompt, char2Saju);

		// 4. 분석 구조 설명
		prompt.append("""

			### 4. [분석 구조] ###
			1. %s (%s)의 연애관과 기질
			2. %s (%s)의 연애관과 기질
			3. 두 캐릭터의 '케미스트리'와 '관계 서사' (핵심)

			"""
			.formatted(char1Name, char1Source, char2Name, char2Source));

		// ===== 5. 1단계: 캐릭터 1 분석 =====
		prompt.append("""
			### 5. [1단계: '%s' 캐릭터성 분석] ###
			캐릭터의 원작 성격과 사주(일간, 십성)를 연결하여 연애 스타일을 분석해주세요.

			"""
			.formatted(char1Name));

		prompt.append("""
			타고난 기질과 숨겨진 욕망
			""");
		prompt.append(
			String.format("- %s의 겉모습과 달리 내면에 숨겨진 욕망이나 결핍은 무엇인가요? (지장간, 신살 활용)\n", char1Name));
		prompt.append("""
			- 원작의 행동 패턴이 사주의 어떤 글자와 일치하는지 구체적으로 연결해주세요.

			""");

		prompt.append("""
			연애 스타일
			- 연애를 할 때 리드하는 타입인가요, 아니면 챙김 받는 타입인가요? 사주 십성을 근거로 분석해주세요.
			- 집착, 회피, 헌신 등 사랑에 빠졌을 때 나타나는 특징을 묘사해주세요.

			""");

		// ===== 6. 2단계: 캐릭터 2 분석 =====
		prompt.append("""
			### 6. [2단계: '%s' 캐릭터성 분석] ###
			마찬가지로 두 번째 캐릭터의 사주를 통해 연애 스타일을 분석해주세요.

			"""
			.formatted(char2Name));

		prompt.append("""
			타고난 기질과 숨겨진 욕망
			- %s의 겉모습과 달리 내면에 숨겨진 욕망이나 결핍은 무엇인가요?
			- 원작의 성격이 사주의 어떤 부분에서 기인했는지 설명해주세요.

			"""
			.formatted(char2Name));

		prompt.append("""
			연애 스타일: 공(Top)인가 수(Bottom)인가?
			- 관계를 주도하는 성향인가요, 맞춰주는 성향인가요?
			- 이 캐릭터가 사랑을 표현하는 고유한 방식(말/행동/돈/희생 등)은 무엇인가요?

			""");

		// ===== 7. 3단계: 두 캐릭터의 궁합 (여기가 핵심) =====
		prompt.append(
			String.format("\n### 7. [3단계: %s X %s 관계성] ###\n", char1Name, char2Name));
		prompt.append("""
			**가장 중요한 파트입니다. 두 캐릭터가 엮이는 장면을 눈앞에 보이듯 생생하게 서술하세요.**

			""");

		prompt.append("""
			--- [분석 시작] ---
			"%s와 %s의 조합이라니... 마치 [비유]처럼 [어떤 분위기]의 서사가 펼쳐지겠네요."로 시작

			"""
			.formatted(char1Name, char2Name));

		prompt.append("""
			## 첫 만남과 비주얼 합
			- **[상황 묘사]** 두 캐릭터가 처음 마주친다면 어떤 장면일까요? (긴장감? 호기심? 무관심?) 구체적인 상황을 상상해서 묘사해주세요.
			- 서로의 일간(日干) 기운으로 볼 때, 첫눈에 끌릴까요 아니면 부딪힐까요?

			""");

		prompt.append("""
			## 관계의 역학
			- 두 사람의 관계성을 한 단어로 정의한다면? (예: 배틀 연애, 상호 구원, 집착과 도망 등)
			- **[주도권 싸움]** 사귄다면 누가 관계의 주도권(기강)을 잡게 될까요? 사주의 '관성'과 '비겁' 세력을 비교해서 분석해주세요.
			""");
		prompt.append(
			String.format("- %s의 이상형 조건에 %s가 얼마나 부합하는지, 반대는 어떤지 교차 검증해주세요.\n\n", char1Name,
				char2Name));

		prompt.append("""
			## 갈등과 위기
			- 두 사람 사이에 발생할 수 있는 가장 치명적인 갈등(위기) 상황은 무엇인가요? (오해, 가치관 차이, 집착 등)
			- **[상황 묘사]** 갈등 상황에서 서로에게 어떤 상처 주는 말을 할지, 혹은 어떻게 행동할지 구체적으로 묘사해주세요.
			- 이 갈등을 해결하고 해피엔딩으로 가기 위해 서로에게 필요한 것은 무엇인가요?

			""");

		prompt.append("""
			## 혜안의 한 줄 평
			- 이 커플의 궁합을 한 문장으로 정의한다면? (예: '세계관 최강자들의 자존심 강한 사랑')
			- 팬들에게 이 조합을 '먹어볼 만한지(츄라이)' 영업하는 멘트로 마무리.

			""");

		PromptSections.appendCompatibilityJsonResponseFormat(prompt, char1Name, char2Name);

		return prompt.toString();
	}
}
