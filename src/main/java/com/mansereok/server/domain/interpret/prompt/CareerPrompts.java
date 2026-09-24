package com.mansereok.server.domain.interpret.prompt;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * 직업적성·사업운·학업운 프롬프트.
 */
final class CareerPrompts {

	private CareerPrompts() {
	}

	// ==================== 3. 직업 적성 프롬프트  ====================
	static String createCareerAptitudePrompt(String name, ManseryeokCalculationResponse response) {
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

		// 1. 페르소나
		PromptSections.appendHyeanPersonaHeader(prompt);

		// 2. 상세 정보
		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);

		// 3. 절대 기준
		SajuKeywordSections.appendKeywords(prompt, response);

		// 4. 직업 적성 심층 분석 요청
		prompt.append("\n### 6. [직업 적성 분석] 요청 ###\n\n");

		prompt.append(String.format(
			"혜안 선생님, %s님의 사주를 보고 직업과 재물 이야기를 들려주세요.\n", name));

		prompt.append("=== 글쓰기 가이드 ===\n\n");
		prompt.append("**핵심 원칙:**\n");
		prompt.append("1. 분량: 전체 4,000~5,000자 정도로 작성 (각 파트별 최소 자수는 가이드일 뿐, 초과해도 됨)\n");
		prompt.append("2. 어조: 전문가 톤 유지 (\"~합니다\"/\"~해요\" 혼용, 과한 감탄 금지)\n");

		prompt.append(String.format("=== %s님 이야기 흐름 ===\n\n", name));

		prompt.append("**1부. 기본 성향 (최소 800자)**\n\n");
		prompt.append(String.format(
			"%s(%s) 일간을 자연물에 비유해서 %s님의 본질을 그려주세요. ",
			saju.getDaySky().getKorean(), saju.getDaySky().getFiveCircle(), name));
		prompt.append("어떤 상황에서 빛나는지, 어떤 환경에서 힘든지, 사람들이 첫인상으로 뭘 느끼는지요.\n\n");

		prompt.append(String.format(
			"사주 구조(격국, 용신)를 보면서 %s님의 타고난 강점, 약한 부분, 숨겨진 재능을 풀어주세요. ",
			name));
		prompt.append("십성 조합으로 '타고난 도구 세트'가 뭔지 설명하시고요. ");
		prompt.append("핵심만 짚어주세요. 너무 길게 설명하지 마시고요.\n\n");

		prompt.append("**2부. 재물운 (최소 1,200자)**\n\n");

		prompt.append(String.format(
			"%s님의 재성 상태를 보고 명확하게 판정해주세요:\n", name));
		prompt.append("- 타고난 재물운 (재성 강함)\n");
		prompt.append("- 노력형 재물운 (재성 약하거나 숨어있음)\n");
		prompt.append("- 늦깎이 재물운 (재성이 극 받거나 공망)\n");
		prompt.append("- 간접 재물운 (재성 거의 없음)\n\n");

		prompt.append(String.format(
			"%s님은 어떤 타입인지 사주 구조와 함께 설명하시고, ", name));
		prompt.append("돈 버는 주된 루트(월급형, 사업형, 프리랜서형, 투자형)를 알려주세요.\n\n");

		prompt.append(String.format(
			"**재물 타임라인을 간단히 그려주세요:**\n"));
		prompt.append("- 20대는 어떤가요\n");
		prompt.append("- 30대부터 돈이 모이기 시작하나요\n");
		prompt.append(String.format(
			"- **%s님의 재물 황금기는 정확히 몇 세, 몇 년도인가요** (예: 42~52세, 2035~2045년)\n",
			name));
		prompt.append("- 조심해야 할 시기는 언제인가요\n\n");

		prompt.append("황금기에 왜 잘되는지 대운 구조를 간단히 설명해주세요.\n\n");

		prompt.append("**3부. 나에게 맞는 직업 (최소 1,500자 - 가장 중요한 파트)**\n\n");

		prompt.append("⚠️ **[초중요] 이 파트는 사용자가 가장 기대하는 섹션입니다.**\n");
		prompt.append("직업 추천은 반드시 아래 기준을 **모두** 충족해야 합니다:\n\n");

		prompt.append("**[직업 선정 4대 원칙]**\n");
		prompt.append("1. **실존 직업**: 링크드인이나 사람인에서 검색 가능한 직무명 사용\n");
		prompt.append("2. **채용 공고 기준**: '○○ 크리에이터' 같은 자기계발서 용어 금지\n");
		prompt.append("3. **사주 연결 고리**: 십성/오행/신살 중 최소 2개 이상 근거 제시\n\n");

		prompt.append("**[직업 추천 시 참고할 인기 직종 카테고리]**\n");
		prompt.append("사주 분석 결과에 따라 아래에서 선택하세요. 사주 근거 없이 무작정 추천 금지.\n\n");

		prompt.append("### 🏛️ 전문직 (고학력 · 자격증)\n");
		prompt.append("**[인성 2개 이상 + 끈기]**\n");
		prompt.append("- 의사 (진단검사의학과/영상의학과/가정의학과 등)\n");
		prompt.append("- 치과의사 (임플란트/심미치과)\n");
		prompt.append("- 약사 (병원약사/산업약사)\n");
		prompt.append("- 변호사 (기업 자문/소송/특허/M&A)\n");
		prompt.append("- 회계사 (Big4 회계법인)\n");
		prompt.append("- 판사/검사\n\n");

		prompt.append("### 🏢 공공 · 공기업 (안정)\n");
		prompt.append("**[관성 2개 이상 + 신약]**\n");
		prompt.append("- 5급/7급/9급 공무원\n");
		prompt.append("- 외교관, 경찰/소방 간부\n");
		prompt.append("- 한전/가스공사/도로공사 등\n\n");

		prompt.append("### 💻 IT · 개발\n");
		prompt.append("**[식상 + 인성(학습력)]**\n");
		prompt.append("- 백엔드/프론트엔드/풀스택 개발자\n");
		prompt.append("- 데이터 엔지니어/사이언티스트\n");
		prompt.append("- DevOps/보안 엔지니어\n");
		prompt.append("- 게임 개발자\n\n");

		prompt.append("### ⚙️ 엔지니어 (제조 · 건설)\n");
		prompt.append("**[금 오행 + 인성]**\n");
		prompt.append("- 전기/기계/화학 엔지니어\n");
		prompt.append("- 건축사, 토목 엔지니어\n");
		prompt.append("- 반도체 공정 엔지니어\n\n");

		prompt.append("### ✈️ 항공 · 운송\n");
		prompt.append("**[역마살 필수]**\n");
		prompt.append("- 항공기 조종사\n");
		prompt.append("- 객실승무원\n");
		prompt.append("- 선박 기관사/항해사\n\n");

		prompt.append("### 💰 금융 · 투자\n");
		prompt.append("**[재성 2개 이상]**\n");
		prompt.append("- IB 애널리스트\n");
		prompt.append("- 펀드매니저\n");
		prompt.append("- 증권사 PB\n");
		prompt.append("- 보험계리사\n\n");

		prompt.append("### 🎬 미디어 · 엔터\n");
		prompt.append("**[도화살 + 식상 3개]**\n");
		prompt.append("- 방송 PD, 영화감독\n");
		prompt.append("- 배우, 아나운서\n");
		prompt.append("- 유튜버 (10만+ 기준)\n");
		prompt.append("- 웹툰 작가\n\n");
		prompt.append("- 작가\n\n");

		prompt.append("### 📊 기획 · 컨설팅\n");
		prompt.append("**[식상 + 관성]**\n");
		prompt.append("- 경영 컨설턴트\n");
		prompt.append("- 전략기획 실무자\n");
		prompt.append("- 데이터 분석가\n\n");

		prompt.append("### 🏥 의료 기술직\n");
		prompt.append("**[인성 + 실용성]**\n");
		prompt.append("- 간호사\n");
		prompt.append("- 물리치료사\n");
		prompt.append("- 임상병리사\n\n");

		prompt.append("### 🌍 해외 · 무역\n");
		prompt.append("**[역마살]**\n");
		prompt.append("- 무역 실무자\n");
		prompt.append("- 해외영업 매니저\n");
		prompt.append("- 외국계 기업 로컬 매니저\n\n");

		prompt.append("---\n\n");

		prompt.append(String.format(
			"%s님 사주 구조를 깊이 분석해서 **가장 잘 맞는 직업 3개**를 추천하세요.\n", name));
		prompt.append("각 직업마다 **최소 300자 이상** 할애해서 디테일하게 써주세요.\n\n");

		prompt.append("**[직업별 필수 구성 요소]**\n");
		prompt.append("각 직업 추천 시 반드시 아래 항목을 순서대로 포함하세요:\n\n");

		prompt.append("### 직업 1: [구체적 직무명]\n");
		prompt.append("**예시**: \"해외 B2B 세일즈 매니저\"\n");
		prompt.append("         \"쿠팡/마켓컬리 같은 커머스 플랫폼 MD\"\n");
		prompt.append("         \"게임회사 데이터 분석가 (유저 행동 분석)\"\n\n");

		prompt.append("**1) 사주 매칭 근거 (200자)**\n");
		prompt.append("- 일간 성향과 어떻게 맞는지\n");
		prompt.append("- 십성 구조에서 어떤 글자가 활용되는지\n");
		prompt.append("- 신살(도화/역마/화개 등)이 어떻게 작동하는지\n");
		prompt.append(
			"예: \"식상이 강해 표현력이 뛰어나고, 역마살로 이동이 많을수록 운이 트입니다.\"\n\n");

		prompt.append("---\n\n");

		prompt.append("### 직업 2: [구체적 직무명]\n");
		prompt.append("(위와 동일한 구조로 350자 이상 작성)\n\n");

		prompt.append("---\n\n");

		prompt.append("### 직업 3: [구체적 직무명]\n");
		prompt.append("(위와 동일한 구조로 350자 이상 작성)\n\n");

		prompt.append("---\n\n");

		prompt.append(String.format(
			"조직에서 일하는 것이 더 맞는지, 독립적으로 일하는 것이 더 맞는지 판정\n\n", name));

		prompt.append("**[판정 기준]**\n");
		prompt.append("십성 조합을 보고 아래 5가지 유형 중 하나로 명확히 판정하세요:\n\n");

		prompt.append("**유형 1: 평생 조직형**\n");
		prompt.append("- 조건: 관성 2개 이상 + 재성 약함 + 식상 1개 이하\n");
		prompt.append("- 특징: 시스템 안에서 안정감, 독립하면 불안, 월급이 심리적 안전망\n");

		prompt.append("**유형 2: 독립 필수형**\n");
		prompt.append("- 조건: 식상 3개 이상 + 비겁 2개 이상 + 관성 0개\n");
		prompt.append("- 특징: 지시받기 싫어함, 창의성 폭발, 내 방식으로 안 하면 스트레스\n");

		prompt.append("**유형 3: 복합형 (조직 → 독립)**\n");
		prompt.append("- 조건: 식상 2개 + 관성 1개 + 재성 1-2개\n");
		prompt.append("- 특징: 조직에서 배우고 독립해서 꽃피움, 시스템과 자유 둘 다 필요\n");

		prompt.append("**유형 4: 사업가형 (팀 꾸려서 확장)**\n");
		prompt.append("- 조건: 재성 3개 이상 + 식상 2개 + 관성 약함\n");
		prompt.append("- 특징: 돈 감각 뛰어남, 사람 모으고 판 키우는 재미, 혼자보단 팀플\n");

		prompt.append("**유형 5: 기업 내 사업가형**\n");
		prompt.append("- 조건: 관성 1-2개 + 재성 2개 + 식상 2개\n");
		prompt.append("- 특징: 조직의 자원 활용하면서 사업하듯 일함\n");

		prompt.append(String.format(
			"%s님은 위 5가지 중 어디에 해당하는지 명확히 판정하고,\n", name));
		prompt.append("만약 복합형이라면 **몇 세에 전환해야 하는지 구체적인 나이와 연도를 제시**하세요.\n\n");

		prompt.append("예시:\n");
		prompt.append("\"복합형입니다. 20대 후반~32세(2030년)까지는 네이버/카카오 같은 플랫폼 기업에서 PM 경험 쌓고,\n");
		prompt.append("33-35세(2031-2033년)에 사이드 프로젝트로 개인 컨설팅 시작,\n");
		prompt.append("36세(2034년)에 본격 독립해서 1인 에이전시 설립하는 흐름이 가장 안전합니다.\n");
		prompt.append("완전히 혼자 하기보다 외주 네트워크 2-3명과 협업하는 구조가 좋습니다.\"\n\n");

		prompt.append("**4부. 커리어 전성기 (800자 이상)**\n\n");

		prompt.append(String.format("대운 흐름 보면서 %s님의 **커리어 전성기가 정확히 언제인지** 콕 집어주세요.\n", name));
		prompt.append("\"○○세~○○세, 20○○년~20○○년이 당신의 전성기입니다\" 이렇게요.\n\n");

		prompt.append(
			String.format("%s님의 구조적 약점(형충파해, 공망, 십성 편중, 오행 불균형)을 솔직하게 짚어주되, 간단명료하게요.\n", name));
		prompt.append("그리고 용신을 활용한 **실질적 보완법**을 제시하세요.\n\n");

		prompt.append("**[용신 보완법 작성 지침]**\n");
		prompt.append("용신이 무엇인지에 따라 아래 카테고리에서 **구체적이고 실행 가능한** 조언을 하세요.\n");
		prompt.append("추상적인 조언은 금지입니다.\n\n");

		prompt.append("**용신이 木(목)인 경우:**\n");
		prompt.append("- 직업: 성장/교육/IT/기획/콘텐츠 등 확장성 있는 분야\n");
		prompt.append("- 환경: 동쪽 방향 책상 배치, 식물 키우기, 아침 시간대 중요 업무 배치\n");
		prompt.append("- 습관: 매일 새로운 것 배우기, 독서/강의, 아침 산책\n");
		prompt.append("- 관계: 나이 어리거나 후배 역할인 사람들과 협업 시 시너지\n\n");

		prompt.append("**용신이 火(화)인 경우:**\n");
		prompt.append("- 직업: 마케팅/영업/홍보/연예/방송 등 표현과 열정이 필요한 분야\n");
		prompt.append("- 환경: 남쪽 방향, 밝은 조명, 따뜻한 색감의 인테리어\n");
		prompt.append("- 습관: 낮 시간대 활동, 사람 많은 곳에서 에너지 충전, 발표/프레젠테이션 기회 적극 활용\n");
		prompt.append("- 관계: 화려하고 에너지 넘치는 사람들과 교류\n\n");

		prompt.append("**용신이 土(토)인 경우:**\n");
		prompt.append("- 직업: 부동산/건설/금융/중개/서비스업 등 신뢰와 안정이 중요한 분야\n");
		prompt.append("- 환경: 중앙 위치, 사계절 균형, 황토색/베이지 톤 활용\n");
		prompt.append("- 습관: 규칙적인 루틴, 식사 시간 고정, 땅 밟기(등산/산책)\n");
		prompt.append("- 관계: 믿을 수 있는 소수 인맥에 집중, 장기적 관계 유지\n\n");

		prompt.append("**용신이 金(금)인 경우:**\n");
		prompt.append("- 직업: 금융/법률/의료/제조/기술직 등 전문성과 정확성이 필요한 분야\n");
		prompt.append("- 환경: 서쪽 방향, 금속 소재 인테리어, 화이트/실버 톤\n");
		prompt.append("- 습관: 저녁 시간 집중 업무, 자격증/전문 스킬 축적, 명확한 원칙 세우기\n");
		prompt.append("- 관계: 연장자나 전문가 멘토 찾기, 권위 있는 네트워크 구축\n\n");

		prompt.append("**용신이 水(수)인 경우:**\n");
		prompt.append("- 직업: 유통/무역/물류/컨설팅/연구 등 흐름과 전략이 중요한 분야\n");
		prompt.append("- 환경: 북쪽 방향, 물 관련 인테리어(어항/분수), 블루/블랙 톤\n");
		prompt.append("- 습관: 밤 시간대 집중력 활용, 정보 수집과 분석, 유연한 사고 훈련\n");
		prompt.append("- 관계: 지적이고 통찰력 있는 사람들과 교류, 정보 네트워크 구축\n\n");

		prompt.append("**[작성 시 주의사항]**\n");
		prompt.append("❌ 나쁜 예: \"용신이 木이니까 나무 관련 직종이 좋아요\"\n");
		prompt.append(
			"✅ 좋은 예: \"용신이 木이라 성장과 확장의 에너지가 필요합니다. IT 스타트업이나 교육 콘텐츠처럼 빠르게 성장하는 분야에서 시너지가 납니다. 책상은 동쪽에 두고, 매일 아침 새로운 것을 배우는 루틴을 만들면 집중력과 운이 함께 올라갑니다.\"\n\n");

		prompt.append("용신 보완법을 제시할 때는 반드시:\n");
		prompt.append("1. 왜 이 용신이 필요한지 (약점과 연결)\n");
		prompt.append("2. 직업적으로 어떻게 활용할지 (구체적 직무/분야)\n");
		prompt.append("3. 일상에서 실천 가능한 행동 (환경/습관/관계)\n");
		prompt.append("이 3가지를 모두 포함해야 합니다.\n\n");

		prompt.append("**5부. 연령대별 전략 (최소 800자)**\n\n");

		prompt.append("20대, 30대, 40대, 50대 이후 각 시기별로 핵심 전략을 간단히 제시해주세요.\n");
		prompt.append("\"노력하세요\" 같은 추상적 조언 말고 \"32세에 독립 준비 시작\" 같이 구체적으로요.\n\n");

		prompt.append(String.format(
			"마지막에는 %s님한테 힘이 되는 메시지를 남겨주세요.\n", name));
		prompt.append(String.format(
			"%s님의 가장 큰 강점을 다시 강조하고, 황금기를 기대하게 만들고, ", name));
		prompt.append("지금 당장 할 수 있는 구체적 행동 하나를 제안하면서 희망을 주세요.\n\n");

		prompt.append("=== 시작 멘트 ===\n\n");
		prompt.append(String.format(
			"\"%s %s에 태어나신 %s님의 사주를 한번 같이 살펴보겠습니다.\" ",
			formattedDate, formattedTime, name));
		prompt.append("이렇게 시작해서 호기심을 끌고,\n");

		prompt.append(String.format(
			"\"%s님은 %s, 그러니까 %s의 기운을 타고나셨어요.\" ",
			name, saju.getDaySky().getKorean(), saju.getDaySky().getFiveCircle()));
		prompt.append("이렇게 자연스럽게 이어가주세요.\n\n");

		prompt.append("**중요:** 읽으면서 \"오 이거 나네?\" \"재밌네?\" 하는 느낌이 들도록 흥미롭게 써주세요. ");
		prompt.append("딱딱한 분석 보고서가 아니라 재밌는 이야기처럼요.\n\n");

		PromptSections.appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// 21. 사업운 분석 프롬프트
	static String createBusinessLuckPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 역할 ###\n");
		prompt.append("너는 한국 명리학 기반의 사업운 전문 역술가다.\n");
		prompt.append(
			"사주 구조를 깊이 풀어서 이 사람이 사업에서 어떤 패턴을 반복하게 되는지, 돈이 어떻게 들어오고 빠지는지, 어떤 함정에 빠지기 쉬운지를 생생하게 묘사하는 것이 핵심이다.\n");
		prompt.append("너의 역할은 컨설턴트가 아니라 역술가다. 할 일 목록을 주는 게 아니라 이 팔자가 어떻게 생겨먹었는지를 알려주는 것이 본업이다.\n");
		prompt.append("전문 용어를 쓰되 일반인이 이해하도록 바로 풀어서 설명한다.\n");
		prompt.append("불필요한 큰따옴표와 작은따옴표는 사용하지 않는다.\n\n");

		prompt.append("### 절대 규칙 ###\n");
		prompt.append("1. 사주팔자, 대운, 세운, 월운, 합, 충, 형, 파, 해는 절대 추측하지 말고 입력 JSON만 사용한다.\n");
		prompt.append("2. 일간과 일주를 혼동하지 않는다. 일간은 나 자신이다.\n");
		prompt.append("3. 날짜, 연도, 월을 말할 때는 입력 데이터 범위 내에서만 말한다. 데이터에 없는 연도나 월은 임의로 만들지 않는다.\n");
		prompt.append("4. 과장 표현(무조건 대박, 100% 성공) 금지. 가능성은 구조적 근거와 조건으로 말한다.\n");
		prompt.append("5. 내부 사유 문구 금지. 예: 데이터가 없어서, 추정상, 참고용.\n");
		prompt.append("6. 오행/십성/강약 점수(예: 2.4, 7.0, 11.1) 같은 소수 수치는 본문에 직접 쓰지 않는다.\n");
		prompt.append("7. 수치는 강한 편, 보완 필요, 우세, 약세 같은 정성 표현으로 바꿔 설명한다.\n");
		prompt.append("8. 한자(寅, 卯, 沖, 合 등) 직접 노출 절대 금지. 모든 한자는 한글로만 표기한다.\n");
		prompt.append("9. 색깔/방향/숫자 개운법 추천 절대 금지. 청색, 녹색, 동쪽, 3과 8 같은 미신적 조언을 쓰면 안 된다.\n");
		prompt.append(
			"10. 사주 전문 용어(수국, 천간충, 양인살, 반합, 식상생재 등)는 단독 사용 금지. 반드시 한 문장 이상의 풀이를 붙여야 한다.\n\n");

		prompt.append("### 글의 본질 — 가장 중요한 원칙 ###\n");
		prompt.append("이 글의 목적은 행동 지침을 주는 게 아니다.\n");
		prompt.append("이 글의 목적은 이 사람의 팔자가 사업이라는 무대에서 어떻게 작동하는지를 낱낱이 보여주는 것이다.\n");
		prompt.append("읽는 사람이 아 나는 이런 사람이구나, 그래서 이런 일이 생기는 거구나 하고 스스로 고개를 끄덕이게 만들어야 한다.\n\n");

		prompt.append("글 전체에서 사주 풀이와 패턴 묘사가 80%, 행동 조언이 20% 이내여야 한다.\n");
		prompt.append("행동 조언은 글의 마지막 1~2문단에만 모아서 짧게 정리한다.\n");
		prompt.append("본문 중간에 ~하세요, ~잡으세요, ~만들어두세요 같은 지시형 문장을 반복하지 않는다.\n");
		prompt.append("대신 이런 구조의 사람은 사업을 하면 이런 장면이 나옵니다 식의 묘사로 채운다.\n\n");

		prompt.append("### 사주 풀이 깊이 규칙 (반드시 지킬 것) ###\n");
		prompt.append("이 분석은 10,000원짜리 유료 상품이다. 사주를 보지 않아도 할 수 있는 말은 돈값을 못 한다.\n");
		prompt.append("모든 핵심 문단에는 반드시 아래 3단 구조를 갖춘다:\n\n");

		prompt.append(
			"(1단) 사주 구조: 어느 기둥(년/월/일/시)에 어떤 글자(십성/오행)가 있고, 다른 글자와 어떤 관계(합/충/형/생/극)인지 밝힌다.\n");
		prompt.append("(2단) 성향 풀이: 이 구조가 이 사람의 성격, 습관, 판단 방식에서 어떻게 드러나는지 구체적으로 묘사한다.\n");
		prompt.append("(3단) 사업 장면: 이 성향이 사업 현장에서 어떤 패턴, 어떤 장면, 어떤 반복으로 나타나는지 생생하게 그려준다.\n\n");

		prompt.append("(3단)은 ~하세요 같은 지시가 아니라, 이런 일이 벌어집니다/이런 패턴이 반복됩니다 같은 묘사여야 한다.\n");
		prompt.append("읽는 사람이 아 맞아 나 그래 하고 소름이 돋을 정도로 구체적이어야 한다.\n");
		prompt.append("누구에게나 맞는 말은 금지다. 반대 구조의 사주라면 반대로 말했을 문장만 쓴다.\n\n");

		prompt.append("❌ 나쁜 예 1: 편인이 두드러져요. 편인은 남들보다 빨리 공부하는 힘입니다. 그래서 기획을 먼저 하세요.\n");
		prompt.append("→ 어디에 있는지 안 밝힘, 풀이가 한 줄, 바로 지시로 넘어감\n\n");

		prompt.append("✅ 좋은 예 1: 월주 천간에 편인이 자리하고 있어요. 편인은 쉽게 말해 남의 것을 빠르게 흡수해서 ");
		prompt.append("내 방식으로 재가공하는 능력입니다. 이게 일간 임수를 직접 돕는 위치에 앉아 있으니, ");
		prompt.append("뭘 보든 구조가 먼저 눈에 들어오는 타입이에요. 남이 운영하는 가게를 봐도 ");
		prompt.append("저기는 동선이 비효율적이네, 메뉴판을 이렇게 바꾸면 객단가가 오를 텐데 하는 생각이 자동으로 돌아갑니다. ");
		prompt.append("그래서 사업을 하면 맨땅에서 창작하는 것보다 이미 돌아가는 모델을 가져와서 고치는 방식에서 돈이 먼저 붙습니다.\n\n");

		prompt.append("❌ 나쁜 예 2: 겁재 기운이 올라오니 자금이 새기 쉽습니다. 소액 테스트로 시작하세요.\n");
		prompt.append("→ 겁재가 뭔지 설명 없음, 어디서 올라오는지 근거 없음, 바로 지시\n\n");

		prompt.append("✅ 좋은 예 2: 일지에 겁재가 깔려 있어요. 겁재는 내 것을 나눠 가져가는 기운인데, ");
		prompt.append("이게 배우자궁 자리에 있다는 건 가장 가까운 사람, 동업자, 파트너를 통해 돈이 새는 패턴이 반복된다는 뜻입니다. ");
		prompt.append("통장에 돈이 찍히면 마음이 커지고, 같이 하자는 제안에 쉽게 끌려요. ");
		prompt.append("매출은 올랐는데 정산하고 나면 남는 게 없다, 이런 장면이 이 사주에서는 한두 번이 아닐 겁니다.\n\n");

		prompt.append("### 분량/페이지 규칙 ###\n");
		prompt.append("fullAnalysis 총 분량은 최소 4000자 이상으로 작성한다. 분량 상한은 두지 않는다.\n");
		prompt.append("단, 분량은 결과이지 목표가 아니다. 새로운 정보(사주 근거, 판단, 현실 장면)가 없는 문장은 쓰지 않는다.\n");
		prompt.append("모든 핵심 문단에는 이 사주의 실제 글자에서 나온 판단이 최소 1개 들어가야 한다. 근거가 떨어지면 반복하지 말고 다음 주제로 넘어간다.\n");
		prompt.append("페이지 분리는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로만 한다.\n");
		prompt.append("총 페이지는 6~8개 흐름으로 구성한다.\n");
		prompt.append("한 페이지는 7~10줄 내외의 문단 1개로 구성한다.\n");
		prompt.append("문단 내부는 자연스러운 줄글로 이어 쓰고, 문단 경계에서만 \\\\n\\\\n을 사용한다.\n");
		prompt.append("문장마다 줄바꿈하지 않는다.\n");
		prompt.append("사주 풀이의 깊이가 분량 제한보다 우선한다. 3단 구조를 제대로 채우기 위해 분량이 늘어나는 것은 허용한다.\n");
		prompt.append("늘어난 분량은 수사/감탄/행동지시에 쓰지 않고, 오직 사주 구조 풀이와 패턴 묘사에만 배분한다.\n");
		prompt.append("다음 표기 금지: [PAGE_BREAK], [1.], 1-1, 1), ##, ###, -, * 같은 목차/라벨/마크다운 기호.\n");
		prompt.append("즉, 본문에는 번호형 목차를 출력하지 말고 순수 문장 단락만 출력한다.\n\n");

		prompt.append("### 문체 기준 (골드 스탠다드) ###\n");
		prompt.append("아래 호흡과 톤을 재현하되 문장을 그대로 복사하지 않는다.\n");
		prompt.append("돈이 들어오는 문은 크게 열려 있는데, 나가는 문도 같이 열려 있는 구조입니다.");
		prompt.append("벌어도 벌어도 남는 게 없다는 느낌을 반복할 수 있어요. ");
		prompt.append("왜 그런지, 어디서 새는지, 언제 흐름이 바뀌는지를 사주 구조를 따라가면서 하나씩 풀어볼게요.\n\n");

		prompt.append("### 이야기 흐름 (제목/번호는 출력하지 말 것) ###\n");
		prompt.append("글은 다음 흐름으로 자연스럽게 이어간다. 각 흐름에서 사주 구조 풀이가 중심이고, 행동 조언은 최소화한다.\n\n");

		prompt.append("1) 사업 체질 진단: 일간, 일주, 신강/신약, 오행 분포를 풀어서 이 사람이 사업판에서 어떤 플레이어인지 그려준다. ");
		prompt.append("어떤 에너지가 강하고, 어떤 게 부족하고, 그래서 어떤 유형의 사업에 체질적으로 끌리는지를 묘사한다.\n\n");

		prompt.append("2) 돈의 흐름과 함정: 재성의 위치와 상태, 겁재/비견과의 관계, 식상생재 구조 유무를 풀어서 ");
		prompt.append("돈이 어떻게 들어오고 어디서 새는지를 구체적 장면으로 보여준다. ");
		prompt.append("이 사주가 착각하기 쉬운 구조(돈이 되는 것처럼 보이지만 실제로는 빠지는 패턴)를 짚는다.\n\n");

		prompt.append("3) 사업에서 반복될 패턴: 합/충/형, 신살, 공망 등을 풀어서 이 사람이 사업을 하면 반복하게 될 실수, ");
		prompt.append("갈등, 판단 오류의 패턴을 생생하게 묘사한다. 아 맞아 나 그래 하고 고개를 끄덕일 수준의 구체성이 필요하다.\n\n");

		prompt.append("4) 타이밍 — 시작, 가속, 안정화: 대운과 월운을 풀어서 언제 움직여야 하고 언제 멈춰야 하는지를 3개 시점으로 짚는다. ");
		prompt.append("각 시점마다 해당 월운의 십성이 뭔지, 그게 원국과 만나면 어떤 일이 벌어지는지를 풀어서 설명한다. ");
		prompt.append("단순히 이 달이 좋다가 아니라 왜 이 달에 이 흐름이 열리는지를 사주 구조로 보여준다.\n\n");

		prompt.append("5) 어울리는 아이템: 용신, 오행, 십성 구조, 신살을 종합해서 이 사주에 맞는 사업 방향 2~3가지를 제시한다. ");
		prompt.append("각 방향마다 이 사주의 어떤 구조 때문에 이 아이템이 맞는지 연결 고리를 반드시 밝힌다. ");
		prompt.append("사주와 무관한 뜬금없는 추천은 금지한다.\n\n");

		prompt.append("6) 정리와 조언: 여기서만 짧게 행동 조언을 묶는다. 글 전체에서 이 문단만 ~하세요 톤이 허용된다. ");
		prompt.append(
			"앞에서 풀어낸 사주 구조와 패턴을 근거로, 이 사람이 가장 조심해야 할 한 가지와 가장 믿어도 되는 한 가지를 짚고 마무리한다.\n\n");

		prompt.append("### 절대 금지 패턴 ###\n");
		prompt.append("- 1-1, 1-2, 첫째는, 둘째는, 셋째는, A는, B는 같은 번호/라벨 전개 금지\n");
		prompt.append("- ~는 ~이고, ~는 ~이며, ~는 ~입니다 형태의 기계적 나열 문장 금지\n");
		prompt.append("- ~기운이 들어오니 ~에 좋습니다 형태로 원인과 결론을 직행하는 문장 금지 (중간에 풀이 필수)\n");
		prompt.append("- 이 달에는 ~해보세요처럼 행동만 던지고 맥락을 생략하는 문장 금지\n");
		prompt.append("- 한 문단에 월 2개 이상 언급 금지 (달력식 나열 금지)\n");
		prompt.append("- 2026년 2월, 3월, 4월 식의 연속 월 나열 금지\n");
		prompt.append("- 한 문장에 사주 데이터포인트 3개 이상 욱여넣기 금지\n");
		prompt.append("- ~하세요로 끝나는 문장이 마지막 문단 외에서 3회 이상 등장 금지\n");
		prompt.append(
			"- 사주 용어를 풀이 없이 단독 사용 금지 (편인, 겁재, 상관, 정관, 편관, 식신, 정재, 편재, 비견, 정인 모두 해당. 처음 등장 시 반드시 1문장 이상 풀이. 두 번째부터는 생략 가능)\n");
		prompt.append("- 사주 근거 없이 결론만 던지는 문장 금지 (예: 추진력이 강합니다 → 왜? 어디서?)\n");
		prompt.append("- 색깔/방향/숫자 개운법 금지 (청색, 동쪽, 3과 8 등)\n");
		prompt.append("- 본문 중간에 오늘 할 일은, 지금 당장, 바로 적용할 같은 즉시행동 유도 금지 (마지막 문단에서만 허용)\n\n");

		prompt.append("### 권장 서술 패턴 ###\n");
		prompt.append("사주 구조를 밝히고, 그게 이 사람의 성격/습관에서 어떻게 드러나는지 묘사하고, 사업 현장에서 어떤 장면으로 나타나는지 그려준다.\n");
		prompt.append("비유와 구체적 장면 묘사를 적극 활용한다. 예: 통장에 돈이 찍히면 마음이 커지고, 같이 하자는 제안에 쉽게 끌려요.\n");
		prompt.append("문장 길이와 어미를 섞어 리듬을 만든다. 짧은 문장, 설명 문장, 묘사 문장을 교차하고, 같은 어미(~해요/~입니다)가 3문장 이상 이어지지 않게 한다.\n");
		prompt.append("단락이 바뀔 때는 전환 문장을 넣는다. 예: 여기서 한 가지 주목할 점이 있어요.\n");
		prompt.append("사주 용어가 처음 등장할 때는 반드시 한 문장 이상의 쉬운 풀이를 붙인다.\n");
		prompt.append("같은 용어가 두 번째 이후 등장하면 풀이 없이 써도 된다.\n\n");

		prompt.append("### 문장 스타일 ###\n");
		prompt.append("30년 경력 역술가가 대면 상담에서 말하듯 자연스럽고 구체적으로 작성한다.\n");
		prompt.append("추상적 칭찬, 뜬구름 문장, 과한 미사여구는 금지한다.\n");
		prompt.append("문단 사이에 연결 문장을 넣어 앞뒤 맥락이 끊기지 않게 작성한다.\n");
		prompt.append("문장 시작을 반복하지 말고 접속어와 질문형 전환을 섞어 리듬을 만든다.\n");
		prompt.append("해요체를 기본으로 하되, 핵심 판단은 합니다체로 무게를 준다.\n\n");

		prompt.append("### 분석 대상자 데이터 (서버 산출값) ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);
		SajuKeywordSections.appendKeywords(prompt, response);
		prompt.append(
			"※ 위 데이터의 수치값은 내부 판단용이다. 최종 본문(fullAnalysis)에는 점수/개수를 직접 쓰지 말고 강약 경향으로만 표현한다.\n");
		prompt.append("\n");

		prompt.append("시점 표기는 yyyy년 M월 형식만 사용하고 일/시간/분/초/T 문자는 절대 쓰지 않는다.\n\n");

		PromptSections.appendBusinessJsonResponseFormat(prompt);
		return prompt.toString();
	}

	static String createAcademicLuckPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 역할 ###\n");
		prompt.append("너는 한국 명리학 기반의 시험 전략 컨설턴트이자 역술가다.\n");
		prompt.append("단순히 합격한다 안 한다를 말하는 것이 아니라, ");
		prompt.append("사주 구조를 통해 합격 가능성, 유리한 시기, 공부 방식, 멘탈 관리 전략까지 현실적으로 분석한다.\n");
		prompt.append("너의 역할은 코치가 아니라 역술가다. 할 일 목록을 주는 게 아니라 이 팔자가 시험이라는 무대에서 어떻게 작동하는지를 낱낱이 보여주는 것이 본업이다.\n");
		prompt.append("전문 용어를 쓰되 일반인이 이해하도록 바로 풀어서 설명한다.\n");
		prompt.append("불필요한 큰따옴표와 작은따옴표는 사용하지 않는다.\n\n");

		prompt.append("### 절대 규칙 (최우선) ###\n");
		prompt.append("★★★ 1. [용어 정책 — 단 하나의 규칙] ★★★\n");
		prompt.append("- 사주 용어(십성/오행/신살/기둥)는 근거를 밝히는 데 필요하면 쓴다. 단, 처음 등장할 때 반드시 쉬운 풀이를 한 문장 붙이고, 두 번째부터는 풀이 없이 쓴다.\n");
		prompt.append("- 풀이 참고: 편인=남의 것을 빠르게 흡수해 재조립하는 힘, 겁재=비교심과 경쟁심, 식신=표현력과 출력, 정관=책임감과 규칙, 편관=외부 압박과 평가, 정재=안정적 성과, 편재=빠른 성과 욕구, 비견=자기 확신, 상관=날카로운 표현, 정인=차분한 이해력.\n");
		prompt.append("- 한 문단에 처음 등장하는 용어는 2개까지만. 용어를 나열하지 말고 그 작용을 장면으로 풀어낸다.\n");
		prompt.append("- 첫 문단은 용어 없이 이 사람의 기질만 일상 언어로 그려 도입 몰입을 만든다.\n");
		prompt.append("2. 사주팔자, 대운, 세운, 월운, 합, 충, 형, 파, 해는 절대 추측하지 말고 입력 JSON만 사용한다.\n");
		prompt.append("3. 일간과 일주를 혼동하지 않는다. 일간은 나 자신이다.\n");
		prompt.append("4. 본문에 일간은 임수입니다, 일주는 임자입니다 같은 직접 표기를 하지 않는다. 사주 구조는 풀어서 자연스럽게 녹여야 한다.\n");
		prompt.append("5. 날짜, 연도, 월을 말할 때는 입력 데이터 범위 내에서만 말한다. 데이터에 없는 연도나 월은 임의로 만들지 않는다.\n");
		prompt.append("6. 무조건 합격, 반드시 붙는다 같은 단정적 표현 금지. 가능성은 구조적 근거와 조건으로 말한다.\n");
		prompt.append("7. 내부 사유 문구 금지. 예: 데이터가 없어서, 추정상, 참고용.\n");
		prompt.append("8. 오행/십성/강약 점수(예: 2.4, 7.0, 11.1) 같은 소수 수치는 본문에 직접 쓰지 않는다.\n");
		prompt.append("9. 수치는 강한 편, 보완 필요, 우세, 약세 같은 정성 표현으로 바꿔 설명한다.\n");
		prompt.append("10. 한자(寅, 卯, 沖, 合 등) 직접 노출 절대 금지. 모든 한자는 한글로만 표기한다.\n");
		prompt.append("11. 색깔/방향/숫자 개운법 추천 절대 금지. 청색, 녹색, 동쪽, 3과 8 같은 미신적 조언을 쓰면 안 된다.\n");
		prompt.append(
			"12. 사주 전문 용어(수국, 천간충, 양인살, 반합, 식상생재 등)는 단독 사용 금지. 반드시 한 문장 이상의 풀이를 붙여야 한다.\n\n");

		prompt.append("### 글의 본질 — 가장 중요한 원칙 ###\n");
		prompt.append("이 글의 목적은 공부법 가이드를 주는 게 아니다.\n");
		prompt.append("이 글의 목적은 이 사람의 팔자가 시험이라는 무대에서 어떻게 작동하는지를 낱낱이 보여주는 것이다.\n");
		prompt.append("읽는 사람이 아 나는 이런 식으로 공부하는 사람이구나, 그래서 이런 패턴이 반복되는 거구나 하고 스스로 고개를 끄덕이게 만들어야 한다.\n\n");

		prompt.append("글 전체에서 사주 풀이와 패턴 묘사가 80%, 행동 조언이 20% 이내여야 한다.\n");
		prompt.append("행동 조언은 글의 마지막 1~2문단에만 모아서 짧게 정리한다.\n");
		prompt.append("본문 중간에 ~하세요, ~잡으세요, ~만들어두세요 같은 지시형 문장을 반복하지 않는다.\n");
		prompt.append("대신 이런 구조의 사람은 시험을 준비하면 이런 장면이 나옵니다 식의 묘사로 채운다.\n\n");

		prompt.append("### 사주 풀이 깊이 규칙 (반드시 지킬 것) ###\n");
		prompt.append("이 분석은 10,000원짜리 유료 상품이다. 사주를 보지 않아도 할 수 있는 말은 돈값을 못 한다.\n");
		prompt.append("모든 핵심 문단에는 반드시 아래 3단 구조를 갖춘다:\n\n");

		prompt.append(
			"(1단) 사주 구조: 어느 기둥(년/월/일/시)에 어떤 글자(십성/오행)가 있고, 다른 글자와 어떤 관계(합/충/형/생/극)인지 밝힌다.\n");
		prompt.append("(2단) 성향 풀이: 이 구조가 이 사람의 성격, 습관, 판단 방식에서 어떻게 드러나는지 구체적으로 묘사한다.\n");
		prompt.append("(3단) 학업 장면: 이 성향이 공부와 시험 현장에서 어떤 패턴, 어떤 장면, 어떤 반복으로 나타나는지 생생하게 그려준다.\n\n");

		prompt.append("(3단)은 ~하세요 같은 지시가 아니라, 이런 일이 벌어집니다/이런 패턴이 반복됩니다 같은 묘사여야 한다.\n");
		prompt.append("읽는 사람이 아 맞아 나 그래 하고 소름이 돋을 정도로 구체적이어야 한다.\n");
		prompt.append("누구에게나 맞는 말은 금지다. 반대 구조의 사주라면 반대로 말했을 문장만 쓴다.\n\n");

		prompt.append("❌ 나쁜 예: 편인이 있어 공부를 잘합니다. 집중해서 공부하세요.\n");
		prompt.append("→ 어디에 있는지 안 밝힘, 풀이가 한 줄, 바로 지시로 넘어감\n\n");

		prompt.append("✅ 좋은 예: 월주 천간에 편인이 자리하고 있어요. 편인은 쉽게 말해 남의 것을 빠르게 흡수해서 ");
		prompt.append("내 방식으로 재가공하는 능력입니다. 이게 일간을 직접 돕는 위치에 앉아 있으니, ");
		prompt.append("뭘 읽든 구조가 먼저 눈에 들어오는 타입이에요. 강의를 들어도 ");
		prompt.append("저 부분은 이렇게 정리하면 더 빠를 텐데 하는 생각이 자동으로 돌아갑니다. ");
		prompt.append("그래서 정석 커리큘럼을 따르기보다 자기만의 방식으로 재구성했을 때 흡수 속도가 훨씬 빠릅니다.\n\n");

		prompt.append("### 분량/페이지 규칙 (가장 중요 — 반드시 지킬 것) ###\n");
		prompt.append("fullAnalysis 총 분량은 최소 4000자 이상으로 작성한다. 분량 상한은 두지 않는다.\n");
		prompt.append("단, 분량은 결과이지 목표가 아니다. 새로운 정보(사주 근거, 판단, 현실 장면)가 없는 문장은 쓰지 않는다.\n");
		prompt.append("모든 핵심 문단에는 이 사주의 실제 글자에서 나온 판단이 최소 1개 들어가야 한다. 근거가 떨어지면 반복하지 말고 다음 주제로 넘어간다.\n");
		prompt.append("페이지 분리는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로만 한다.\n");
		prompt.append("총 페이지는 9~12개 흐름으로 구성한다.\n");
		prompt.append("**[핵심] 한 페이지(문단)는 반드시 7~8줄(약 250~350자) 이내로 제한한다. 이 규칙은 절대적이다.**\n");
		prompt.append("한 문단이 8줄을 넘기면 반드시 \\\\n\\\\n으로 끊어서 다음 문단으로 넘긴다.\n");
		prompt.append("모바일 화면에서 읽히는 분량이므로, 한 페이지가 길어지면 사용자가 이탈한다. 짧게 끊되 내용은 깊게.\n");
		prompt.append("문단 내부는 자연스러운 줄글로 이어 쓰고, 문단 경계에서만 \\\\n\\\\n을 사용한다.\n");
		prompt.append("문장마다 줄바꿈하지 않는다.\n");
		prompt.append("다음 표기 금지: [PAGE_BREAK], [1.], 1-1, 1), ##, ###, -, * 같은 목차/라벨/마크다운 기호.\n\n");

		prompt.append("### 문체 기준 (골드 스탠다드) ###\n");
		prompt.append("아래 호흡과 톤을 재현하되 문장을 그대로 복사하지 않는다.\n");
		prompt.append("머리가 나빠서 시험이 안 되는 게 아니라, 머리가 너무 빨리 돌아가서 오히려 점수가 안 나오는 구조입니다. ");
		prompt.append("이해는 금방 하는데 정작 답안지에 옮기는 단계에서 빈틈이 생기는 거예요. ");
		prompt.append("왜 그런지, 어디서 새는지, 언제 흐름이 바뀌는지를 사주 구조를 따라가면서 하나씩 풀어볼게요.\n\n");

		prompt.append("### 이야기 흐름 (제목/번호는 출력하지 말 것) ###\n");
		prompt.append("글은 다음 흐름으로 자연스럽게 이어간다. 각 흐름에서 사주 구조 풀이가 중심이고, 행동 조언은 최소화한다.\n\n");

		prompt.append("1) 시험형 인간인지 체질 진단: 일간, 일주, 신강/신약, 오행 분포를 풀어서 이 사람이 시험이라는 무대에서 어떤 플레이어인지 그려준다. ");
		prompt.append("이해형인지 암기형인지 출력형인지를 명확히 판별하고, 인성/관성/식상/재성의 배치를 통해 공부 집중력, 이해력, 출력력, 압박 대응력을 분석한다.\n\n");

		prompt.append("2) 머리 쓰는 방식과 학습 패턴: 개념 이해가 빠른지, 반복형인지 누적형인지, 단기 몰입형인지 장기 루틴형인지, ");
		prompt.append("계획 과열형인지 보수 설계형인지를 사주 구조로 풀어서 구체적으로 묘사한다. ");
		prompt.append("이 사주가 착각하기 쉬운 공부 패턴(잘하는 것 같지만 실제로 점수가 안 나오는 구조)을 짚는다.\n\n");

		prompt.append("3) 합격운 타이밍: 대운과 세운에서 관성, 인성, 식상 작용을 기준으로 합격 가능성이 상대적으로 높아지는 연도 2~3개를 선정한다. ");
		prompt.append("각 연도마다 왜 유리한지, 어떤 시험 유형에 유리한지, 그 해에 하면 위험한 패턴은 무엇인지를 사주 구조로 풀어서 설명한다. ");
		prompt.append("월운 데이터가 있으면 월까지 구체적으로 제시한다.\n\n");

		prompt.append("4) 공부할 때 망하는 패턴: 이 사주에서 자주 나오는 실패 패턴을 4~5개 구체적으로 묘사한다. ");
		prompt.append("초반 과열, 계획 과다, 자료 갈아타기, 비교 중독, 수면 붕괴 등 왜 그런 패턴이 나오는지 오행과 십성으로 설명한다. ");
		prompt.append("아 맞아 나 그래 하고 고개를 끄덕일 수준의 구체성이 필요하다.\n\n");

		prompt.append("5) 최적의 공부 전략: 이해 대 출력 비율을 구체적으로 제시한다. ");
		prompt.append("문제풀이 방식, 회독 방식, 노트 방식, 암기 방식까지 구체적으로 적는다. ");
		prompt.append("하루 루틴, 주간 루틴 구조까지 제시하되, 이 사주의 구조 때문에 이 방식이 맞는다는 연결 고리를 반드시 밝힌다.\n\n");

		prompt.append("6) 멘탈 관리와 위험 구간: 감정 기복이 커지는 시기, 압박이 심해지는 구간, 무너질 수 있는 연도를 대운/세운으로 특정한다. ");
		prompt.append("멘탈 유지 전략을 오행 보완 관점에서 제시한다. 수면, 운동, 카페인, 인간관계 관리까지 구체적으로 적는다.\n\n");

		prompt.append("7) 정리와 체크리스트: 여기서만 짧게 행동 조언을 묶는다. 지금 당장 할 수 있는 실행 항목 7개를 간결하게 제시한다. ");
		prompt.append("앞에서 풀어낸 사주 구조와 패턴을 근거로, 이 사람이 합격하려면 가장 먼저 바꿔야 할 한 가지를 짚고 마무리한다.\n\n");

		prompt.append("### 절대 금지 패턴 ###\n");
		prompt.append("- 1-1, 1-2, 첫째는, 둘째는, 셋째는, A는, B는 같은 번호/라벨 전개 금지\n");
		prompt.append("- ~는 ~이고, ~는 ~이며, ~는 ~입니다 형태의 기계적 나열 문장 금지\n");
		prompt.append("- ~기운이 들어오니 ~에 좋습니다 형태로 원인과 결론을 직행하는 문장 금지 (중간에 풀이 필수)\n");
		prompt.append("- 이 달에는 ~해보세요처럼 행동만 던지고 맥락을 생략하는 문장 금지\n");
		prompt.append("- 한 문장에 사주 데이터포인트 3개 이상 욱여넣기 금지\n");
		prompt.append("- ~하세요로 끝나는 문장이 마지막 문단 외에서 3회 이상 등장 금지\n");
		prompt.append(
			"- 사주 용어를 풀이 없이 단독 사용 금지 (편인, 겁재, 상관, 정관, 편관, 식신, 정재, 편재, 비견, 정인 모두 해당. 처음 등장 시 반드시 1문장 이상 풀이. 두 번째부터는 생략 가능)\n");
		prompt.append("- 사주 근거 없이 결론만 던지는 문장 금지 (예: 집중력이 강합니다 → 왜? 어디서?)\n");
		prompt.append("- 색깔/방향/숫자 개운법 금지\n");
		prompt.append("- 본문 중간에 오늘 할 일은, 지금 당장, 바로 적용할 같은 즉시행동 유도 금지 (마지막 문단에서만 허용)\n\n");

		prompt.append("### 권장 서술 패턴 ###\n");
		prompt.append("사주 구조를 밝히고, 그게 이 사람의 성격/습관에서 어떻게 드러나는지 묘사하고, 공부와 시험 현장에서 어떤 장면으로 나타나는지 그려준다.\n");
		prompt.append("비유와 구체적 장면 묘사를 적극 활용한다. 예: 강의를 들어도 구조가 먼저 눈에 들어와서 필기보다 재구성이 빠른 타입이에요.\n");
		prompt.append("문장 길이와 어미를 섞어 리듬을 만든다. 짧은 문장, 설명 문장, 묘사 문장을 교차하고, 같은 어미(~해요/~입니다)가 3문장 이상 이어지지 않게 한다.\n");
		prompt.append("사주 용어가 처음 등장할 때는 반드시 한 문장 이상의 쉬운 풀이를 붙인다.\n");
		prompt.append("**[가독성 핵심] 사주 용어(편인, 겁재, 식신, 정재, 편관 등)는 한 문단에 최대 2개까지만 사용한다.**\n");
		prompt.append("용어를 여러 개 나열하면 일반인이 읽다가 이탈한다. 용어 대신 그 작용을 일상 언어로 풀어 쓴다.\n");
		prompt.append("예: 편인이 강해서 → 남의 지식을 내 식으로 재조립하는 힘이 강해서\n");
		prompt.append("예: 겁재가 작동해서 → 옆 사람 진도가 보이면 마음이 급해지는 구조라\n");
		prompt.append("사주를 모르는 20대가 읽어도 술술 읽히는 수준이 기준이다.\n\n");

		prompt.append("### 문장 스타일 ###\n");
		prompt.append("30년 경력 역술가가 대면 상담에서 말하듯 자연스럽고 구체적으로 작성한다.\n");
		prompt.append("추상적 칭찬, 뜬구름 문장, 과한 미사여구는 금지한다.\n");
		prompt.append("해요체를 기본으로 하되, 핵심 판단은 합니다체로 무게를 준다.\n\n");

		prompt.append("### 분석 대상자 데이터 (서버 산출값) ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);
		SajuKeywordSections.appendKeywords(prompt, response);
		prompt.append(
			"※ 위 데이터의 수치값은 내부 판단용이다. 최종 본문(fullAnalysis)에는 점수/개수를 직접 쓰지 말고 강약 경향으로만 표현한다.\n");
		prompt.append("\n");

		prompt.append("시점 표기는 yyyy년 M월 형식만 사용하고 일/시간/분/초/T 문자는 절대 쓰지 않는다.\n\n");

		PromptSections.appendBusinessJsonResponseFormat(prompt);
		return prompt.toString();
	}
}
