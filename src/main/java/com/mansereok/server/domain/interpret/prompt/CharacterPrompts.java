package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;

/**
 * 아이돌·배우·캐릭터 사주 프롬프트.
 */
final class CharacterPrompts {

	private CharacterPrompts() {
	}

	// ==================== 5. 아이돌 최애 분석 프롬프트 (혜안 적용) ====================
	static String createIdolAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		String solarDate = input.getSolarDate().toString();
		String solarTime = input.getSolarTime().toString();

		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		// 1. '혜안' 공통 페르소나 주입 (유지)
		PromptSections.appendHyeanPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (유지)
		prompt.append("""
			### ⚠️ 매우 중요: 3인칭 서술 ###
			이 분석은 '%s'라는 제3자(아이돌)에 대한 것입니다.
			쉼표, 마침표, '-', 이런 표현 최대한 줄여주세요. AI가 작성한 글이라는 티가 나면 안됩니다.
			절대로 2인칭(당신)을 사용하지 말고, **'그는', '그녀는', '%s님은', '이 사람은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.

			"""
			.formatted(name, name));

		// 3. 분석 대상자 정보 주입
		prompt.append("""
			### 5. 분석 대상자 상세 정보 ###
			""");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);

		SajuKeywordSections.appendKeywords(prompt, response);

		// 4. [분석 요청]
		prompt.append("""

			### 6. [최애 심층 분석] 요청 ###
			혜안 선생님, 아이돌 '%s'님의 사주를 팬의 관점에서 **아래 요청된 순서대로** 깊이 있게 분석해주세요.
			"""
			.formatted(name));

		prompt.append("""
			**[가장 중요!]** 말투는 **'~입니다', '~네요', '~로군요', '~이군요' 같은 따뜻하고 명료한 말투**를 사용하세요.
			AI가 쓴 것 같은 뻔한 서론/결론, 억지 비유는 절대 쓰지 마세요. 부드럽고, 심각하지 않게, '발견한 사실'을 설명하는 방식이어야 합니다.

			""");

		prompt.append("""
			--- [분석 시작] ---
			"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다."와 같이 자연스럽게 분석을 시작해주세요.

			"""
			.formatted(name, formattedDate, formattedTime, saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("""
			## 아이돌의 타고난 기질, 성격, 인성, 그룹 내 역할
			(일간, 월지, 십성 분포를 바탕으로 %s님의 근본적인 성격과 인성을 심층 분석해주세요. 만세력 기반으로 설명하되, 알아듣기 쉽도록 풀어서 재미있게 설명해주세요.)
			(만약 팀이라면, 이 사람의 성격이 팀 내에서 어떻게 작용할지, 어떤 역할(리더형, 조율자형, 마이웨이형 등)을 맡을지도 함께 예측해주세요.)

			(아이돌로서 보여지는 것과 달리, 이 사람의 실제 성격은 어떤지, 끼를 타고난 아이돌인가 아니면 노력형인가, 만세력 기반으로 분석 하되 알아듣기 쉽도록 풀어서 재미있게 설명해주세요.)

			"""
			.formatted(name));

		prompt.append("""
			## 병크 및 리스크 예측
			(사주 원국과 신살, 운의 흐름을 볼 때, 이 아이돌이 아이돌 활동 중 가장 조심해야 할 '병크'나 리스크는 무엇인가요?)
			(예: 구설수, 건강 문제, 이성 문제 등. 흉살이나 충/형을 근거로 설명하되, 알아듣기 쉽게 풀어서 설명해주세요.)

			""");

		prompt.append("""
			## 아이돌이 아니었다면? (타고난 재능)
			(사주에 나타난 핵심 재능(식상, 인성, 관성 등)을 바탕으로, 아이돌이 아니었다면 어떤 직업에서 성공 했을지, 1~2가지 구체적으로 분석해주세요.)

			""");

		prompt.append("""
			## 연애관 및 이상형 (가장 마지막)
			(팬들이 궁금해하는 부분입니다. 이 사람의 연애 스타일, 본능적으로 끌리는 이상형(외모, 성격)을 솔직하게 분석해주세요.)
			(이 사람의 어떤 부분이 이성에게 매력으로 다가올지에 대해서 설명 (외적인 것, 내적인 것 만세력 기반으로 분석하되 알아듣기 쉽고 편하게 설명해주세요.))
			(결혼은 언제쯤 할지 정확한 년도 예측, 배우자궁(일지)의 모습은 어떤지도 포함해주세요.)

			""");

		PromptSections.appendSajuOutputFormat(prompt);

		return prompt.toString();
	}

	// ==================== 13. 배우 분석 프롬프트 ====================
	static String createActorAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		String solarDate = input.getSolarDate().toString();
		String solarTime = input.getSolarTime().toString();

		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		// 1. '혜안' 공통 페르소나 주입 (유지)
		PromptSections.appendHyeanPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (유지)
		prompt.append("""
			### ⚠️ 매우 중요: 3인칭 서술 ###
			이 분석은 '%s'라는 제3자(배우)에 대한 것입니다.
			쉼표, 마침표, '-', 이런 표현 최대한 줄여주세요. AI가 작성한 글이라는 티가 나면 안됩니다.
			절대로 2인칭(당신)을 사용하지 말고, **'그는', '그녀는', '%s님은', '이 사람은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.

			"""
			.formatted(name, name));

		// 3. 분석 대상자 정보 주입
		prompt.append("""
			### 분석 대상자 상세 정보 ###
			""");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);

		SajuKeywordSections.appendKeywords(prompt, response);

		// 4. 분석 요청
		prompt.append("""

			### [배우 심층 분석] 요청 ###
			혜안 선생님, 배우 '%s'님의 사주를 팬의 관점에서 **아래 요청된 순서대로** 깊이 있게 분석해주세요.
			"""
			.formatted(name));

		prompt.append("""
			**[가장 중요!]** 말투는 **'~입니다', '~네요', '~로군요', '~이군요' 같은 따뜻하고 명료한 말투**를 사용하세요.
			AI가 쓴 것 같은 뻔한 서론/결론, 억지 비유는 절대 쓰지 마세요. 부드럽고, 심각하지 않게, '발견한 사실'을 설명하는 방식이어야 합니다.

			""");

		prompt.append("""
			--- [분석 시작] ---
			"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다."와 같이 자연스럽게 분석을 시작해주세요.

			"""
			.formatted(name, formattedDate, formattedTime, saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("""
			## 배우의 타고난 기질, 성격, 인성, 작품 선택 능력
			(일간, 월지, 십성 분포를 바탕으로 %s님의 근본적인 성격과 인성을 심층 분석해주세요. 만세력 기반으로 자세하게 설명하되, 보는 사람이 알아 듣기 편하게 쉽고 재미있게 풀어서 설명해주세요.)
			(배우로서 어떤 작품을 선택하는지, 작품에 임하는 태도가 어떤지, 대중들에게 인기 많은 이유가 무엇인지? 어떤 점에 대중들이 매료됐는지 설명)만세력 기반으로 자세하고 구체적으로 설명하되, 사람들이 이해하기 쉽게 재미있게 풀어서 설명\s

			(촬영장에서 스태프들과의 사이가 어떨지, 상대 배우나 다른 배우들과의 사이가 어떨지(현장 케미) 실제 이 배우의 성격이 어떨지에 대해서 만세력 기반으로 자세하게 설명하되 알아듣기 쉽고 재미있게 풀어서 설명해주세요.)

			"""
			.formatted(name));

		prompt.append("""
			## 병크 및 리스크 예측
			(사주 원국과 신살, 운의 흐름을 볼 때, 이 배우가 연예 활동 중 가장 조심해야 할 '병크'나 리스크는 무엇인가요?)
			(예: 구설수, 건강 문제, 이성 문제 등. 흉살이나 충/형을 근거로 설명하되, 알아듣기 쉽게 풀어서 재미있게 설명해주세요.)

			""");

		prompt.append("""
			## 배우가 아니었다면? (타고난 재능)
			(사주에 나타난 핵심 재능(식상, 인성, 관성 등)을 바탕으로, 배우가 아니었다면 어떤 직업에서 성공 했을지, 어떤 직업이 잘 어울리는지, 1~2가지 구체적으로 분석해주세요.)

			""");

		prompt.append("""
			## 연애관 및 이상형 (가장 마지막)
			(팬들이 궁금해하는 부분입니다. 이 사람의 연애 스타일, 본능적으로 끌리는 이상형(외모, 성격)을 솔직하게 분석해주세요.)
			(결혼은 언제쯤 할지 정확한 년도 예측, 배우자궁(일지)의 모습은 어떤지도 포함해주세요.)

			""");

		PromptSections.appendSajuOutputFormat(prompt);

		return prompt.toString();
	}

	// ==================== 9. 캐릭터 사주 프롬프트 (혜안 적용) ====================
	static String createCharacterSajuPrompt(String name, ManseryeokCalculationResponse response,
		String sourceTitle) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		String dayIlgan = saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle(); // 예: 갑목

		// 1. 혜안 페르소나
		PromptSections.appendHyeanPersonaHeader(prompt);

		// 2. 캐릭터 설정 주입
		prompt.append("""
			### ⚠️ 캐릭터 분석 모드 ###
			이 사주는 작품 **'%s'**에 등장하는 캐릭터 **'%s'**의 사주입니다.
			캐릭터의 원작 설정(성격, 작중 행적)과 사주 풀이를 연결하여, '이 캐릭터가 왜 이런 운명을 가졌는지' 설명해주세요.

			"""
			.formatted(sourceTitle != null ? sourceTitle : "알 수 없는 작품", name));

		// 3. 사주 정보
		prompt.append("""
			### 5. 캐릭터 사주 정보 ###
			""");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);

		SajuKeywordSections.appendKeywords(prompt, response);

		// 4. 분석 요청
		prompt.append("""

			### 6. [캐릭터 사주 심층 분석] 요청 ###
			원작의 내용과 사주 명리학을 결합하여 다음 항목들을 '해요체'로 재미있게 분석해주세요.

			""");

		prompt.append("""
			--- [분석 시작] ---
			""");
		prompt.append(
			String.format("\"작품 '%s'의 '%s'님은 [%s 자연물 비유]와 같은 기운을 타고나셨군요.\"로 시작\n\n", sourceTitle,
				name, dayIlgan));

		prompt.append("""
			## 사주로 본 캐릭터에 대한 분석
			작중에서 보여주는 성격과 실제 사주(일간, 십성)의 싱크로율을 분석해주세요. 만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.
			왜 그런 행동을 했는지, 사주적 근거(충, 합, 신살 등)를 들어 설명해주세요. 만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.

			""");

		prompt.append("""
			## 작중 시련과 운명의 흐름
			캐릭터가 겪은 주요 사건이나 시련이 사주상 어떤 기운 때문이었는지 해석해주세요. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.

			""");

		prompt.append("""
			## 매력 포인트
			만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.팬들이 사랑하는 이 캐릭터의 치명적인 매력(도화살, 홍염살, 화개살 등)은 무엇인가요? 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.

			""");

		prompt.append("""
			## 최애와의 로맨스
			""");
		// 1. 대시 스타일 (적극/소극)
		prompt.append("""
			1. **사랑에 빠지는 과정**: %s님은 좋아하는 사람이 생기면 불도저처럼 직진하는 스타일인가요, 아니면 멀리서 지켜보며 신중하게 다가가는 스타일인가요? 사주(식상, 재성, 관성 등)를 근거로 분석해주세요.
			"""
			.formatted(name));

		// 2. 질투와 집착
		prompt.append("""
			2. **질투와 소유욕**: %s님의 질투 레벨은 어느 정도일까요? (겉으로는 쿨하지만 속은 타들어가는 타입, 대놓고 질투하는 타입, 집착광공 재질 등). 만세력의 기운을 바탕으로 상상력을 더해 묘사해주세요.
			"""
			.formatted(name));

		// 3. 연상/연하/동갑 취향
		prompt.append("""
			3. **잘 어울리는 관계**: 사주 구성상 %s님은 본인을 리드해주는 '연상', 본인이 챙겨줘야 하는 '연하', 친구 같은 '동갑' 중 누구와 가장 합이 좋을까요? 그 이유도 알려주세요.
			"""
			.formatted(name));

		// 4. 연애 시 모습
		prompt.append("""
			4. **연인이 된다면?**: %s님과 연애를 한다면 어떤 데이트를 하고 어떤 말을 해줄까요? (다정다감한 사랑꾼, 무심한 듯 챙겨주는 츤데레 등). 팬들이 설렐 수 있는 구체적인 상황을 예시로 들어주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.

			"""
			.formatted(name));

		prompt.append("""
			## 현실 세계에 산다면?
			이 캐릭터가 지금 한국에 산다면 어떤 직업(MBTI)과 라이프스타일을 가졌을지 상상해주세요. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.
			현실에서의 연애 스타일과 이상형도 예측해주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명

			""");

		PromptSections.appendSajuOutputFormat(prompt);
		return prompt.toString();
	}
}
