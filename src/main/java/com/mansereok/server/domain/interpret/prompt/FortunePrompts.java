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
 * 재물운·연애운·신년운세 프롬프트.
 */
final class FortunePrompts {

	private FortunePrompts() {
	}

	// ==================== 20. 돈벼락(재물운) 분석 프롬프트 (v6 - 자연문단형) ====================
	static String createMoneyLuckPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("### 역할 ###\n");
		prompt.append("너는 한국 명리학 기반 재물운 전문 역술가다.\n");
		prompt.append("결론만 던지는 컨설턴트가 아니라, 사주 구조가 현실의 돈 흐름에서 어떻게 작동하는지 풀어주는 해석가다.\n\n");

		prompt.append("### 핵심 목적 ###\n");
		prompt.append(String.format(
			"%s님의 재물운을 분석하되, 큰돈이 열리는 가능성과 함께 손실/누수 리스크를 균형 있게 보여준다.\n",
			name));
		prompt.append("돈벼락은 횡재만 의미하지 않는다. 규모의 급팽창, 단가 상승, 거래처 확장, 투자 수익 확대도 포함한다.\n\n");

		prompt.append("### 작성 원칙 ###\n");
		prompt.append("1. 입력 JSON 밖의 사실은 추측하지 않는다.\n");
		prompt.append("2. 근거 없는 단정 금지. 핵심 판단마다 사주 근거를 붙인다.\n");
		prompt.append("3. 좋은 점만 미화하지 말고 리스크와 손실 가능성을 반드시 같이 다룬다.\n");
		prompt.append("4. 전문 용어는 필요한 순간에만 쓰고, 첫 등장 1회만 쉬운 풀이를 붙인다.\n");
		prompt.append("5. 한 문단에 전문 용어는 최대 1개만 사용한다.\n");
		prompt.append("6. 월운은 12개월 나열 대신 핵심 3구간만 설명한다.\n");
		prompt.append("7. 날짜는 yyyy년 M월 형식만 사용한다. 일/시/분/초/T 표기는 금지한다.\n");
		prompt.append("8. 색/방향/숫자 개운법은 쓰지 않는다.\n");
		prompt.append("9. 마크다운과 라벨형 목차(A., [ ], 1-1)는 쓰지 않는다.\n\n");

		prompt.append("### 작성 방식 ###\n");
		prompt.append("돈의 성격, 강점과 누수, 타이밍 3구간, 최종 조언 순으로 자연스럽게 이어서 쓴다.\n");
		prompt.append("보고서처럼 딱딱한 체크리스트 문장 대신 상담형 줄글로 작성한다.\n");
		prompt.append("오행 점수, 십성 개수 같은 수치값은 본문에 직접 노출하지 않고 강약 경향으로만 표현한다.\n");
		prompt.append("같은 조언을 문장만 바꿔 반복하지 않는다.\n\n");

		prompt.append("### 분량/문단 규칙 ###\n");
		prompt.append("fullAnalysis 총 분량은 3800자 이상 4600자 이하로 작성한다.\n");
		prompt.append("전체는 6~8개 문단으로 구성하고, 문단 구분은 줄바꿈 두 번(\\\\n\\\\n)만 사용한다.\n");
		prompt.append("한 문단이 과도하게 길어지면 문맥 기준으로 자연스럽게 나눈다.\n");
		prompt.append("분량을 늘릴 때는 미사여구가 아니라 근거와 현실 장면 설명을 채운다.\n\n");

		prompt.append("### 분석 대상자 데이터 (만세력) ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);
		SajuKeywordSections.appendKeywords(prompt, response);
		prompt.append("\n");

		PromptSections.appendMoneyLuckJsonResponseFormat(prompt);

		return prompt.toString();
	}

	// ==================== 17. 연애운 ====================
	static String createLoveLuckPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// 날짜 포맷팅
		String solarDate = input.getSolarDate().toString();
		String solarTime = input.getSolarTime().toString();
		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";
		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		// 1. '혜안' 공통 페르소나 주입
		PromptSections.appendHyeanPersonaHeader(prompt);

		// 2. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response);

		SajuKeywordSections.appendKeywords(prompt, response);

		// 3. 분석 요청
		prompt.append("\n### 6. [연애운 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '사랑과 연애'에 대한 모든 것을 한 편의 글로 완성해주세요.\n", name));
		prompt.append("단순한 위로가 아니라, 사주 원국의 기질과 운의 흐름을 냉철하면서도 따뜻하게 짚어야 합니다. ");
		prompt.append("읽는 사람이 자기 연애사를 들킨 것처럼 '아 맞아, 나 진짜 그래' 하게 만드는 것이 목표입니다.\n\n");

		prompt.append("**[구성의 자유]** 아래 `##` 주제들은 재료입니다. 순서를 바꾸거나 묶어도 되고, 제목을 이 사주에 맞게 다시 지어도 됩니다(`##`로 시작만 유지하세요). ");
		prompt.append("이 사람의 연애를 관통하는 중심 테마 하나를 먼저 잡고 전체를 엮으세요. 다음 섹션 예고로 궁금증을 이어가도 좋습니다.\n\n");

		prompt.append("**[분량/문단 규칙]** fullAnalysis 전체 4,000자 이상, 각 주제당 500~700자. 한 문단은 6~7줄 이내, 문단 경계는 줄바꿈 두 번(\\n\\n)만 사용.\n\n");

		prompt.append("**[문체 기준 - 호흡과 톤만 재현, 문장 복사 금지]**\n");
		prompt.append("\"썸까지는 정말 잘 갑니다. 문제는 관계가 깊어지려는 순간이에요. ");
		prompt.append("상대가 다가올수록 마음 한쪽에서 브레이크가 걸리는 구조인데, 이게 성격 탓이 아니라 사주 구조에서 오는 패턴입니다. ");
		prompt.append("왜 그런지, 어떤 사람을 만나면 이 브레이크가 풀리는지 하나씩 풀어볼게요.\"\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 사랑을 하시는군요.\" 로 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 타고난 연애 세포\n");
		prompt.append("일간과 월지, 도화살/홍염살 등 신살을 근거로 이 사람 고유의 매력 포인트를 짚어주세요. 신살이 없으면 억지로 만들지 말고 오행/십성에서 나오는 매력을 찾으세요.\n");
		prompt.append("연애할 때의 스타일을 실제 장면으로 묘사하세요. 썸 탈 때, 연애 초반, 안정기에 각각 어떤 모습이 나오는지요.\n");
		prompt.append("이성이 가장 매력적으로 느끼는 부분과, 반대로 오래 만나면 질릴 수 있는 단점을 솔직하게 말해주세요. 단점을 뭉개면 이 분석은 실패입니다.\n\n");

		prompt.append("## 나의 이상형과 운명적인 상대\n");
		prompt.append(String.format(
			"**일지(배우자궁)**에 있는 글자와 십성을 분석하여, %s님이 본능적으로 끌리는 이성은 어떤 스타일인지 설명해주세요.\n", name));
		prompt.append(String.format(
			"실제로 %s님에게 자꾸 꼬이는 이성들의 특징은 어떤가요? (나쁜 남자/여자가 꼬이는지, 능력자가 꼬이는지 등)\n", name));
		prompt.append(String.format(
			"**[운명적인 상대방 예측]** %s님의 사주에 가장 잘 맞는 '진정한 사랑'의 특징을 아래 항목에 맞춰 풀어서 설명해주세요:\n", name));
		prompt.append(
			"- **예상 MBTI, 나와 잘맞는 MBTI**: (예: ENFP, ISTJ 등 4자리)\n");
		prompt.append(
			"- **나이 차이**: (예: 연상, 동갑, 연하 등 구체적인 범위 제시)\n");
		prompt.append(
			"- **직업군/성격**: (예: 안정적인 공무원, 자유로운 예술가 등)\n");
		prompt.append(
			"- **일지,오행을 참고하여 배우자의 전반적인 스타일, 외모, 이미지**\n");
		prompt.append(
			"결론적으로 어떤 사람을 만나야 팔자가 피고 행복할 수 있는지 구체적인 '이성상'을 추천해주세요. 만세력 기반으로 자세하게 설명하되, 흥미롭고 재미있게 이야기를 풀어내주세요.\n\n");

		prompt.append("## 연애를 가로막는 장애물\n");
		prompt.append(
			"사주 원국에서 연애를 방해하는 요소(무관/무재, 관살혼잡, 고란살, 식상과다 등)가 있다면 용어를 풀어서 솔직하게 지적하세요. 없으면 억지로 만들지 말고 운 흐름상의 주의점을 짚으세요.\n");
		prompt.append(
			"연애만 하면 반복되는 문제 패턴(집착, 의심, 금방 식음, 표현 부족 등)을 구체적 장면으로 재현하세요. 상대가 어떻게 느꼈을지까지 보여주면 더 좋습니다.\n");
		prompt.append("희망고문은 금지입니다. 고쳐야 관계가 유지되는 부분은 분명하게 말해주세요.\n\n");

		prompt.append("## 2026년 연애운, 연애 타이밍\n");
		prompt.append(
			"**2026년(병오년)** 세운과 월운 데이터를 근거로, 솔로라면 인연이 들어올 가능성이 높은 시기(몇 월인지), 커플이라면 관계가 어떻게 변할지 예측하세요.\n");
		prompt.append("왜 그 시기인지 사주 근거(합/충, 재성/관성의 움직임)를 쉬운 말로 함께 설명하세요. 근거 없는 시기 찍기는 금지입니다.\n");
		prompt.append("결혼 가능성이 높은 연도를 대운/세운 근거와 함께 구체적으로 제시하세요.\n\n");

		prompt.append("## 연애 코칭 및 조언\n");
		prompt.append("부족한 오행을 채워줄 데이트 장소와 행운의 컬러를 추천하되, 왜 이 오행이 필요한지 한 문장으로 연결하세요.\n");
		prompt.append("앞에서 짚은 장애물을 극복하는 실전 행동 팁(태도, 대화법, 타이밍)을 2~3가지 제시하세요. '진심을 보여주세요' 같은 추상 조언은 금지입니다.\n");
		prompt.append(String.format(
			"마지막으로 사랑 때문에 고민하는 %s님을 위한 따뜻한 응원 한마디로 마무리하세요.\n\n", name));

		// JSON 포맷 추가
		PromptSections.appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// 18. 신년 운세
	static String createNewYear2026Prompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		// ===== [0단계] 페르소나 주입 =====
		PromptSections.appendHyeanPersonaHeader(prompt);

		// ===== [1단계] 분석 대상자 정보 =====
		prompt.append("\n### 5. 분석 대상자 상세 정보 ###\n");
		SajuProfileSections.appendPersonDetailInfo(prompt, name, response, 2026);

		// ===== [2단계] 절대 기준(Fact) 주입 =====
		SajuKeywordSections.appendKeywords(prompt, response);

		// ===== [3단계] 골드 스탠다드 제시 =====
		prompt.append("\n### 🏆 [레퍼런스] 신년운세의 기준 ###\n");
		prompt.append("**이 수준의 디테일, 감성, 구체성을 반드시 재현하되 절대 표절하지 마세요.**\n\n");

		prompt.append("--- [참고용 샘플: 도윤님 사례] ---\n");
		prompt.append("\"2026년은 병오년으로 도윤님에게는 편인 + 정인 기운이 동시에 작용하는 해입니다. ");
		prompt.append("밖으로 크게 확장하거나 공격적으로 성과를 내기보다는 내부 정비, 방향 재설정, 실력 축적이 핵심인 해예요. ");
		prompt.append("이 해의 키워드는 속도가 아니라 밀도입니다.\"\n\n");

		prompt.append("**[샘플에서 배워야 할 점]**\n");
		prompt.append("1. 추상적 표현이 아닌 '내부 정비', '실력 축적' 같은 **구체적 행동 키워드**\n");
		prompt.append("2. '속도 vs 밀도'처럼 **대조되는 개념**으로 핵심 메시지 강조\n");
		prompt.append("3. 사주 용어(편인, 정인)를 언급했지만 **설명 없이 흐름 속에 자연스럽게** 배치\n");
		prompt.append("4. '예요'체를 써서 **차분하지만 단호한** 톤 유지\n\n");

		prompt.append("### ⚠️ [절대 금지] 자기계발서 스타일 실용 팁 ###\n");
		prompt.append("**아래 스타일은 사주 운세의 품격을 떨어뜨립니다. 절대 사용하지 마세요.**\n\n");

		prompt.append("❌ **금지 예시 (절대 쓰지 말 것)**\n");
		prompt.append("- \"밤 12시 이전 취침, 카페인은 오후 2시 이전\"\n");
		prompt.append("- \"수분은 오전에 1리터, 오후에 1리터를 나눠 마시세요\"\n");
		prompt.append("- \"여행 예산은 월 소득의 10% 이내, 관계 지출은 8% 이내\"\n");
		prompt.append("- \"우량 ETF와 현금성 자산 비중을 늘려야 합니다\"\n");
		prompt.append("- \"90분 집중 블록을 하루 두 번, 주 5일 운영\"\n");
		prompt.append("- \"근거 세 가지를 문서로 남기고, 하루 숙성 뒤 확정하는 24시간 룰\"\n");
		prompt.append("→ 이런 식의 **구체적 숫자, 시간, 퍼센트, 루틴 제시는 금지**입니다.\n\n");

		prompt.append("✅ **대신 이렇게 작성하세요**\n");
		prompt.append("- \"수면과 식사 시간을 단단히 고정시키는 게 건강운을 살리는 방법입니다\"\n");
		prompt.append("- \"욕심을 줄이는 게 아니라 순서를 정하는 게 중요해요\"\n");
		prompt.append("- \"과로를 오래 끌면 한 번에 무너지는 패턴을 조심해야 합니다\"\n");
		prompt.append("- \"지출 구조를 정리하는 해예요. 돈이 새는 구멍을 막는 게 먼저입니다\"\n");
		prompt.append("- \"결과를 빨리 보여주지 않아도 괜찮습니다. 그 느림이 방향을 정확하게 만드는 속도입니다\"\n\n");

		prompt.append("**[핵심 원칙]**\n");
		prompt.append("조언은 **'방향과 원칙'을 제시**하되, 구체적 실행 방법은 독자의 몫으로 남겨두세요.\n");
		prompt.append("사주는 **'어떻게'가 아니라 '왜'와 '무엇'을 말하는 영역**입니다.\n\n");

		// ===== [4단계] 메인 분석 요청 =====
		prompt.append("\n### 6. [2026년 병오년(丙午年) 신년운세 심층 분석] 창작 지침 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 '절대 기준' 데이터와 '레퍼런스 샘플'을 바탕으로 %s님만의 고유한 2026년 운세를 **아래 4단계 구조**로 창작해주세요.\n",
			name));
		prompt.append("**[중요] 분석 대상은 2026년 병오년 한 해 전체입니다. '올해', '내년' 같은 상대적 표현 대신 반드시 '2026년'이라고 명시하세요.**\n\n");

		prompt.append("=== [창작 시작] ===\n\n");

		// --- [1단계] 총운 ---
		prompt.append("## 2026년(병오) 총운\n\n");

		prompt.append("**📌 작성 지침**\n");
		prompt.append(String.format(
			"- **오프닝 문장(필수)**: \"2026년 병오년, 붉은 말의 해 입니다.\" 로 시작하세요.\n",
			name));
		prompt.append("- **핵심 테마 선정**: 위 '절대 기준'에서 도출된 사주 강약, 십성 분포, 용신을 종합하여 ");
		prompt.append("2026년의 **가장 중요한 키워드 1개**를 선택하세요.\n");
		prompt.append("- **심리 변화 묘사**: 사용자의 타고난 기질(일간 성향)과 2026년 기운이 만났을 때 ");
		prompt.append("**어떤 내적 갈등이나 각성**이 일어날지 구체적으로 서술하세요.\n");
		prompt.append("- **주의점 제시**: '절대 기준'에서 발견된 약점(예: 무식상, 충 등)을 바탕으로 ");
		prompt.append("**구체적인 주의 사항**을 2~3가지 명확히 짚어주세요.\n\n");

		prompt.append("핵심 확인: 병오년 에너지와 이 사주의 화학반응 · 용신/신강약 반영 · 구체적 행동 키워드 3개.\n\n");

		// --- [2단계] 분야별 운세 ---
		prompt.append("## 2. 분야별 흐름 분석\n\n");
		prompt.append("**※ 절대 금지: 딱딱한 개조식(1., 2.) 사용 금지. 물 흐르듯 이어지는 줄글로 작성하세요.**\n\n");

		// 재물운
		prompt.append("### [재물운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **수입 vs 지출 구조**: 2026년 재성(財星) 분포를 보고 ");
		prompt.append("'수입이 안정적으로 쌓이는 해' vs '큰 한 방을 노리는 해'인지 명확히 판단하세요.\n");
		prompt.append("- **투자 방향성**: '절대 기준'의 사주 강약을 보고 ");
		prompt.append("공격적 투자가 가능한지, 보수적 관리가 필요한지 **근거와 함께** 조언하세요.\n");
		prompt.append("- **구체적 주의사항**: '돈이 새는 구멍'이 어디인지(인간관계, 충동 소비, 과도한 투자 등) ");
		prompt.append("**사주 데이터 기반**으로 2~3가지 콕 집어주세요.\n");
		prompt.append("- **금액 감각**: '급등', '안정', '변동' 같은 단어로 **감각적**으로 표현하세요.\n\n");

		prompt.append("핵심 확인: 재성(정재/편재) 상태 반영 · 두루뭉술한 권유 대신 단호한 조언.\n\n");

		// 직장/사업운
		prompt.append("### [직장/사업운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **커리어 변화 가능성**: 관성(官星) 유무와 대운 흐름을 보고 ");
		prompt.append("승진, 이직, 창업의 **실제 가능성**을 명확히 제시하세요.\n");
		prompt.append("- **업무 스타일 조언**: 사주 강약과 십성 분포를 보고 ");
		prompt.append("'혼자 책임지고 끌고 가는 스타일' vs '협업으로 시너지 내는 스타일' 중 **어느 쪽**인지 ");
		prompt.append("명확히 판단하고 그에 맞는 **전략**을 제시하세요.\n");
		prompt.append("- **타이밍**: '상반기 집중' vs '하반기 결실'처럼 **시기적 전략**을 짚어주세요.\n\n");

		prompt.append("핵심 확인: 관성/식상 분포 반영 · '기회가 온다' 대신 구체적 상황 묘사.\n\n");

		// 가정/건강운
		prompt.append("### [가정/건강운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **신체 부위 특정**: 오행 편중이나 충 관계를 보고 ");
		prompt.append("**구체적인 신체 부위**(소화기, 수면, 근육, 피부 등)를 2~3개 짚어주세요.\n");
		prompt.append("- **생활 리듬 조언**: '수면 시간 고정', '카페인 조절' 같은 ");
		prompt.append("**즉시 실천 가능한 행동**을 3가지 이상 제시하세요.\n");
		prompt.append("- **가족 관계**: 육친(부모, 형제, 배우자) 관련 변화가 있을지 예측하고 ");
		prompt.append("**도윤님 샘플처럼** '중심을 잡아주는 역할' 같은 구체적 표현을 쓰세요.\n\n");

		prompt.append("핵심 확인: 오행 과다/부족에 따른 신체 취약점 명시 · 실천 가능한 행동 제시.\n\n");

		// 이성/대인관계
		prompt.append("### [이성/대인관계]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **연애운 흐름**: 식상, 재성, 관성의 조합을 보고 ");
		prompt.append("'새로운 인연' vs '기존 관계 심화' vs '정리의 시기' 중 **어느 쪽**인지 판단하세요.\n");
		prompt.append("- **이상형 힌트**: 용신 오행을 활용해 ");
		prompt.append("'차분하고 책임감 있는 사람', '활발하고 즉흥적인 사람' 같은 **구체적 특징**을 제시하세요.\n");
		prompt.append("- **대인 전략**: 사주 강약을 보고 ");
		prompt.append("'선택과 집중' vs '네트워킹 확장' 중 **어느 전략**이 유리한지 조언하세요.\n\n");

		prompt.append("핵심 확인: 신살(도화/역마) 반영 · '인연이 온다'가 아니라 어떤 타입의 인연인지 구체적으로.\n\n");

		// 학업/성취운
		prompt.append("### [학업/성취운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **학습 스타일**: 인성(정인/편인) 유무와 강도를 보고 ");
		prompt.append("'단기 집중' vs '장기 루틴'형인지 판단하고 **구체적 공부법**을 제시하세요.\n");
		prompt.append("- **시험/자격증 타이밍**: 월별 세운을 참고해 ");
		prompt.append("'상반기 집중' vs '하반기 결실' 같은 **시기 전략**을 조언하세요.\n");
		prompt.append("- **성과 예측**: 식상과 관성의 조합을 보고 ");
		prompt.append("'결과가 천천히 쌓이는 해' vs '단기 성과가 가능한 해'인지 명확히 하세요.\n\n");

		prompt.append("핵심 확인: 인성(정인/편인) 분포 반영 · '루틴으로 이기는 해' 같은 핵심 전략 키워드 1개.\n\n");

		// --- [3단계] 월별 세운 ---
		prompt.append("## 3. 월별 흐름 (1월 ~ 12월)\n\n");

		prompt.append("**📌 작성 지침 (매우 중요)**\n");
		prompt.append("- **천편일률 금지**: 매달 '바쁜 달', '조심하는 달' 같은 패턴 반복 절대 금지.\n");
		prompt.append("- **사주 맞춤 분석**: 사용자의 대운, 세운, 월운을 **실제로 계산**하여 ");
		prompt.append("각 달의 천간지지가 사주와 어떻게 상호작용하는지 분석하세요.\n");
		prompt.append("- **분기별 리듬**: 1~3월(시작), 4~6월(활동), 7~9월(성과), 10~12월(정리)의 ");
		prompt.append("**큰 흐름**을 먼저 잡고 세부 월별로 디테일을 채우세요.\n");
		prompt.append("- **구체적 행동 지침**: '정리하는 달'이라면 **무엇을** 정리할지(서류, 관계, 지출 등) ");
		prompt.append("명확히 제시하세요.\n\n");

		prompt.append("핵심 확인: 각 달의 천간지지와 원국의 충/합/형 관계 반영 · '조심하세요' 대신 이유와 대응책을 함께.\n\n");

		prompt.append("**[분기별 가이드]**\n");
		prompt.append("- **1~3월 (1분기)**: 연초 에너지 진단. '시작' vs '관망'의 분기점을 명확히.\n");
		prompt.append("- **4~6월 (2분기)**: 활동성 피크. 변화와 선택의 시기. 구체적 타이밍 제시.\n");
		prompt.append("- **7~9월 (3분기)**: 결실과 평가. '수확' vs '재정비'의 갈림길.\n");
		prompt.append("- **10~12월 (4분기)**: 마무리와 준비. 2027년 방향성 힌트 포함.\n\n");

		// --- [4단계] 조언 및 마무리 ---
		prompt.append("## 4. 조언 및 주의 사항\n\n");

		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **공감과 위로**: 사용자의 사주에서 발견된 **고충이나 갈등 포인트**를 ");
		prompt.append("먼저 공감해주고, 그것이 결함이 아니라 **고유한 리듬**임을 인정하세요.\n");
		prompt.append("- **미래 희망**: 2026년에 쌓은 것이 2027년 이후 어떻게 빛날지 **구체적으로** 전망하세요.\n\n");

		prompt.append("핵심 확인: 고유한 강점 1~2개 · 위로에는 사주 근거를 붙일 것.\n");
		prompt.append("**[필수]** 마지막 문장은 반드시 \"새해 복 많이 받으시고 항상 행복하세요. 네임드사주가 응원하겠습니다.\"\n\n");

		// ===== [5단계] 최종 품질 검증 =====
		prompt.append("\n### 🔍 [최종 검증] 제출 전 필수 체크 ###\n");
		prompt.append("**아래 항목을 모두 충족했는지 확인한 후 JSON으로 출력하세요.**\n\n");

		prompt.append("□ **사주 용어 최소화**: '편인', '비견', '충', '합' 같은 한자어를 **5개 이하**로 제한했는가?\n");
		prompt.append("□ **데이터 반영**: '절대 기준'의 용신, 신강/신약, 십성 분포를 **실제로** 반영했는가?\n");
		prompt.append("□ **구체성**: '좋아요', '조심하세요' 같은 추상적 표현을 **구체적 행동**으로 바꿨는가?\n");
		prompt.append("□ **분량**: 총론~조언까지 합쳐서 **최소 4000자 이상**인가? (짧으면 돈값 못함)\n");
		prompt.append("□ **AI티 제거**: '~것 같습니다', '~생각됩니다' 같은 애매한 표현을 **단호한 조언**으로 바꿨는가?\n");
		prompt.append("□ **이름 표기**: 사용자 이름을 **절대 줄이거나 변경하지 않고** 전체 이름으로 표기했는가?\n\n");

		// ===== [6단계] JSON 포맷 =====
		PromptSections.appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}
}
