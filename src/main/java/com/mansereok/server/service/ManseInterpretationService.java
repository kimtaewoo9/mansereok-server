package com.mansereok.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Result;
import com.mansereok.server.entity.User;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.service.request.Gpt5Request;
import com.mansereok.server.service.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ManseInterpretationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.service.response.model.GptCompatibilityResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ManseInterpretationService {

	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final UserService userService;
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// 60갑자 순서 정의 (대운 계산용)
	private static final List<String> HEAVENLY_STEMS = Arrays.asList("甲", "乙", "丙", "丁", "戊",
		"己", "庚", "辛", "壬", "癸");
	private static final List<String> EARTHLY_BRANCHES = Arrays.asList("子", "丑", "寅", "卯", "辰",
		"巳", "午", "未", "申", "酉", "戌", "亥");
	private static final List<String> GAPJA_CYCLE = new ArrayList<>();

	static {
		for (int i = 0; i < 60; i++) {
			GAPJA_CYCLE.add(HEAVENLY_STEMS.get(i % 10) + EARTHLY_BRANCHES.get(i % 12));
		}
	}

	private static final String GPT5_SYSTEM_INSTRUCTION =
		"--- SYSTEM INSTRUCTION ---\n" +
			"당신은 30년 경력의 전문 사주명리학자입니다. " +
			"자연스럽고 전문적인 어조로 사주 해석을 제공하되, 절대 다음 표현들을 사용하지 마세요:\n" +
			"- '~을 바탕으로 한 해석', '~를 중심으로 풀어갈게요', '~를 정리해 드려요'\n" +
			"- '이번 해석은', '다음 분석은', '위 정보를 바탕으로'\n" +
			"- 해석이 AI나 시스템에 의한 것임을 암시하는 모든 메타적 표현\n\n" +
			"대신, 마치 대면 상담에서 직접 말하듯이 자연스럽게 시작하세요.\n" +
			"예: '강영현님의 사주를 보니...', '먼저 눈에 띄는 건...' 등\n\n" +
			"부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조하세요. " +
			"'해요'체를 사용하여 부드럽고 친근한 말투를 사용하세요.\n\n" +
			"--- USER QUERY ---\n";


	public ManseInterpretationService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl,
		ResultRepository resultRepository,
		UserService userService,
		CompatibilityResultRepository compatibilityResultRepository
	) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
		this.userService = userService;
	}

	/**
	 * 만세력 계산 결과를 바탕으로 GPT-5에게 사주 해석을 요청하고, 구조화된 응답 객체(ManseInterpretationResponse)를 반환합니다.
	 *
	 * @param name          분석 대상자의 이름
	 * @param response      만세력 계산 결과
	 * @param username      사용자명
	 * @param subcategoryId 상품 카테고리 ID (1~9)
	 * @return GPT-5 해석과 추가 정보가 담긴 응답 DTO
	 */
	public ManseInterpretationResponse interpret(
		String name,
		ManseryeokCalculationResponse response,
		String username,
		Long subcategoryId
	) {
		log.info("✅ 사주 해석 요청 시작 - name: {}, subcategoryId: {}", name, subcategoryId);

		// 사주의 핵심인 '일간' 정보를 미리 추출합니다.
		String ilgan = "정보 없음";
		if (response != null && response.getSaju() != null
			&& response.getSaju().getDaySky() != null) {
			PillarElement daySky = response.getSaju().getDaySky();
			ilgan = daySky.getKorean() + daySky.getFiveCircle(); // 예: "임" + "수" -> "임수"
		}

		try {
			// subcategoryId에 따라 다른 프롬프트 생성
			String userPrompt = createPromptBySubcategory(subcategoryId, name, response);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				16384,
				"high",
				"high"
			);

			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			log.info("GPT-5 요청 데이터 생성 완료. API 호출 시작...");
			String gptResponse = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			log.info("GPT-5 응답 수신 완료.");
			String interpretationText = extractContentFromResponseGpt5(gptResponse);

			User user = userService.findByUsername(username);
			log.info("사용자 id: " + user.getId());

			Result savedResult = resultRepository.save(
				Result.create(
					user.getId(),
					name,
					response.getInput().getSolarDate(),
					response.getInput().getSolarTime(),
					response.getInput().getGender(),
					response.getInput().getIsLunar(),
					ilgan,
					interpretationText
				)
			);

			// 성공 시, 모든 정보를 담아 DTO를 빌드하여 반환합니다.
			return new ManseInterpretationResponse(
				savedResult.getId(),
				name,
				ilgan,
				interpretationText
			);

		} catch (Exception e) {
			log.error("GPT API 요청 중 오류 발생: {}", e.getMessage(), e);

			return new ManseInterpretationResponse(
				null,
				name,
				ilgan,
				"해석 생성 중 API 요청 오류가 발생했습니다. 서버 로그를 확인해주세요."
			);
		}
	}

	/**
	 * subcategoryId에 따라 적절한 프롬프트를 생성하는 메서드
	 */
	private String createPromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {

		return switch (subcategoryId.intValue()) {
			case 1 -> createLifeOverallPrompt(name, response);           // 인생 총운
			case 2 -> createPersonalityAnalysisPrompt(name, response);   // 성격 분석
			case 3 -> createCareerAptitudePrompt(name, response);        // 직업 적성
			case 4 -> createLoveFortunePrompt(name, response);           // 연애 운세
			case 5 -> createIdolAnalysisPrompt(name, response);          // 최애에 대한 모든 것
			case 9 -> createCharacterSajuPrompt(name, response);         // 캐릭터 사주
			default -> createComprehensiveAnalysisPrompt(name, response); // 기본 (기존 프롬프트)
		};
	}

	/**
	 * 궁합 분석 (subcategoryId 6, 7, 8번용)
	 */
	public ManseCompatibilityAnalysisResponse analyzeCompatibilityWithSubcategory(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response,
		Long subcategoryId
	) {
		String person1Ilgan = extractIlgan(person1Response);
		String person2Ilgan = extractIlgan(person2Response);

		log.info("✅ 궁합 분석 요청 시작 - subcategoryId: {}, {} & {}", subcategoryId, person1Name,
			person2Name);

		try {
			// subcategoryId에 따라 다른 궁합 프롬프트 생성
			String userPrompt = createCompatibilityPromptBySubcategory(
				subcategoryId, person1Name, person1Response, person2Name, person2Response);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				16384,
				"high",
				"high"
			);
			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			log.info("GPT-5 궁합 분석 요청 데이터 생성 완료. API 호출 시작...");
			String gptResponse = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			log.info("GPT-5 궁합 분석 응답 수신 완료.");
			String interpretation = extractContentFromResponseGpt5(gptResponse);

			GptCompatibilityResponse gptData = objectMapper.readValue(interpretation,
				GptCompatibilityResponse.class);
			Integer score = gptData.getScore();
			String analysisText = gptData.getInterpretation();

			CompatibilityResult savedResult = compatibilityResultRepository.save(
				CompatibilityResult.create(null, person1Name, person1Ilgan, person2Name,
					person2Ilgan, score, analysisText)
			);

			return new ManseCompatibilityAnalysisResponse(
				savedResult.getId(), savedResult.getPerson1Name(), savedResult.getPerson1Ilgan(),
				savedResult.getPerson2Name(), savedResult.getPerson2Ilgan(),
				savedResult.getInterpretation(), savedResult.getCompatibilityScore()
			);
		} catch (Exception e) {
			log.error("GPT API 궁합 분석 요청 중 오류 발생: {}", e.getMessage(), e);
			return new ManseCompatibilityAnalysisResponse(null, person1Name, person1Ilgan,
				person2Name, person2Ilgan, "궁합 분석 중 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", null);
		}
	}

	/**
	 * subcategoryId에 따른 궁합 프롬프트 선택
	 */
	private String createCompatibilityPromptBySubcategory(
		Long subcategoryId,
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		return switch (subcategoryId.intValue()) {
			case 6 ->
				createLoveStoryPrompt(person1Name, person1Response, person2Name, person2Response);
			case 7 -> createIdolCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 8 -> createTriangleRelationshipPrompt(person1Name, person1Response, person2Name,
				person2Response);
			default -> createCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
		};
	}

	// ==================== 기존 종합 프롬프트 (기본값) ====================

	/**
	 * ManseryeokCalculationResponse 객체를 바탕으로 GPT에게 전달할 프롬프트를 생성 (데이터 보강 버전)
	 */
	private String createComprehensiveAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// =================================================================
		// 1. 시스템 역할 정의 (Hyper-Specific Persona & Philosophy)
		// =================================================================
		prompt.append("### 0. 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 경력의 사주명리학 대가이자, '인생 서사 상담가' 혜안(慧眼)입니다.\n");
		prompt.append(
			"당신의 임무는 점술가(Fortune-teller)가 아닙니다. 당신은 한 사람의 고유한 인생 지도(사주팔자)를 해석하여, 그 사람의 잠재력을 긍정하고 삶의 여정을 응원하는 '안내자(Guide)'입니다.\n");
		prompt.append("유료 결제가 전혀 아깝지 않은, 세상에 단 하나뿐인 감동적인 해석을 제공해야 합니다.\n\n");

		prompt.append("### 1. 핵심 분석 원칙 (Core Principles) ###\n");
		prompt.append(
			"1. **절대적 긍정성의 원칙**: 운명론적, 비관적, 단정적 표현을 '절대' 사용하지 않습니다. 모든 부정적 요소(충, 형, 파, 흉살 등)는 '성장을 위한 역동적인 에너지', '삶의 균형을 맞추기 위한 과제', '숨겨진 잠재력의 발현'으로만 해석합니다.\n");
		prompt.append(
			"2. **서사적 스토리텔링**: 사주 데이터를 절대 나열하지 않습니다. 모든 정보는 '한 편의 이야기' 속에 자연스럽게 녹여내야 합니다. (예: '목이 3개' (X) -> '님은 굳건한 숲처럼...'(O))\n");
		prompt.append(
			"3. **감성적 비유 활용**: 아래 '핵심 비유 사전'을 '반드시' 준수하여 일관되고 풍부한 비유로 설명합니다.\n");
		prompt.append(
			"4. **따뜻한 '해요체'**: '~군요', '~네요', '~시네요', '~것 같아요' 등, 마치 마주 앉아 대화하듯 부드럽고 따뜻한 '해요체'를 사용합니다.\n");
		prompt.append(
			"5. **구체적이고 실행 가능한 조언**: 추상적인 조언이 아닌, '그래서 지금 무엇을 하면 좋은지'에 대한 실질적인 조언을 각 파트마다 포함합니다.\n\n");

		prompt.append("### 2. 핵심 비유 사전 (Metaphor Lexicon) ###\n");
		prompt.append("- **사주팔자 (8글자)**: '나만의 인생 지도', '타고난 설계도', '하늘이 준 나침반'\n");
		prompt.append("- **일간 (日干)**: '나의 본질', '나라는 나무의 뿌리', '내 삶의 엔진' (반드시 자연물에 비유)\n");
		prompt.append("- **월지 (月支)**: '내가 자라난 토양', '나의 사회적 무대', '내 마음의 계절'\n");
		prompt.append("- **지장간 (地藏干)**: '내면의 DNA', '숨겨진 보물 상자', '잠재력의 씨앗'\n");
		prompt.append("- **십성 (十星)**: '내가 가진 10가지 도구', '나의 사회적 역할', '인간관계를 맺는 방식'\n");
		prompt.append("- **12운성 (十二運星)**: '내 인생의 에너지 리듬', '삶의 12단계 주기'\n");
		prompt.append("- **대운 (大運)**: '인생의 10년짜리 챕터', '새롭게 열리는 무대', '계절의 변화'\n");
		prompt.append("- **신살 (神殺)**: '나만의 특수 능력(Special Skill)', '삶의 조미료', '잘 다뤄야 할 강력한 도구'\n");
		prompt.append(
			"- **합/충/형/파 (Clash/Combine)**: '안정'과 '변화'를 위한 에너지의 상호작용, '새로운 기회를 만드는 역동성'\n\n");

		prompt.append("### 3. 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- **운명론적 단정**: '반드시 ~하게 된다', '~할 운명이다', '사주가 나쁘다/좋다'\n");
		prompt.append("- **데이터 나열**: '목 2개, 화 1개...', '십성은 편관, 정재가...'\n");
		prompt.append("- **AI/시스템 노출**: '분석 결과', '위 정보를 바탕으로', '시스템에 따르면', '해석을 시작합니다'\n");
		prompt.append("- **딱딱한 '다/까'체**: '~합니다', '~했습니다' (절대 금지. 오직 '해요체'만 사용)\n\n");

		// =================================================================
		// 4. 분석 대상자 데이터 주입 (Data Injection)
		// =================================================================
		prompt.append("### 4. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		prompt.append(String.format("- 이름: %s\n", name));
		prompt.append(
			String.format("- 성별: %s\n", "MALE".equalsIgnoreCase(input.getGender()) ? "남자" : "여자"));
		prompt.append(
			String.format("- 생년월일시(양력): %s %s\n", input.getSolarDate(), input.getSolarTime()));
		prompt.append(String.format("- 현재 년도: %d년\n", java.time.LocalDate.now().getYear()));
		prompt.append("\n");

		// [중요] 사주 원국 데이터를 서사적으로 풀기 위해 상세 주입
		prompt.append("### 5. 사주 원국(原局) 상세 데이터 ###\n");
		// 이 부분은 기존에 성공하셨던 'appendDetailedPillarInfo' 헬퍼 메서드를 사용해야 합니다.
		// (헬퍼 메서드 코드는 2번 항목에 추가했습니다.)
		appendDetailedPillarInfo(prompt, "년주(年柱)", "초년운(뿌리), 조상, 배경", saju.getYearSky(),
			saju.getYearGround());
		appendDetailedPillarInfo(prompt, "월주(月柱)", "청년운(줄기), 사회, 직업", saju.getMonthSky(),
			saju.getMonthGround());
		appendDetailedPillarInfo(prompt, "일주(日柱)", "중년운(꽃), 본인, 배우자", saju.getDaySky(),
			saju.getDayGround());
		if (saju.getTimeSky() != null && saju.getTimeGround() != null) {
			appendDetailedPillarInfo(prompt, "시주(時柱)", "말년운(열매), 자녀, 결과", saju.getTimeSky(),
				saju.getTimeGround());
		}
		prompt.append("\n");

		// 오행 및 십성 데이터 주입 (비유로 풀기 위한 재료)
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("### 6. 오행(五行) 세력 분석 (지장간 가중치 적용) ###\n");
		ohaengCounts.forEach((key, value) -> prompt.append(
			String.format("- %s: %.1f점%s\n", key, value,
				saju.getDaySky().getFiveCircle().equals(key) ? " (일간)" : "")));
		prompt.append("\n");

		prompt.append("### 7. 십성(十星) 분포 분석 ###\n");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d개\n", key, value)));
		prompt.append("\n");

		prompt.append("### 8. 주요 신살(神殺) 및 공망(空亡) 정보 ###\n");
		// 이 부분은 'appendSinsalAnalysis' 헬퍼 메서드가 필요합니다. (2번 항목 참고)
		appendSinsalAnalysis(prompt, saju);
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format("- 공망: %s\n", String.join(", ", saju.getGongmang())));
		}
		prompt.append("\n");

		prompt.append("### 9. 대운(大運) 흐름 데이터 ###\n");
		prompt.append(String.format("- 대운수: %d\n", saju.getBigFortuneNumber()));
		// 이 부분은 'appendDaewoonFlow' 헬퍼 메서드가 필요합니다. (2번 항목 참고)
		appendDaewoonFlow(prompt, saju, input.getGender());
		prompt.append("\n");

		// =================================================================
		// 5. 분석 요청사항 (Output Structure) - 10단계 서사 구조
		// =================================================================
		prompt.append("### 10. 종합 심층 분석 요청 (A 10-Chapter Narrative) ###\n\n");
		prompt.append(
			"혜안 선생님, 위 데이터를 '절대' 나열하지 마시고, '핵심 비유 사전'을 활용하여 아래 10개의 챕터로 구성된 하나의 완결된 '인생 서사'를 작성해주세요.\n");
		prompt.append("각 챕터는 자연스럽게 연결되어야 하며, 전체 분량은 최소 4000자 이상으로 매우 깊이 있게 작성해주세요.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"먼저 \"%s님은 %s %s에 태어나신, [일간(%s)을 자연물(예: 드넓은 바다, 따뜻한 태양, 굳건한 소나무)에 빗댄 핵심 비유]와 같은 기운을 지니셨네요.\"로 감성적인 첫 문장을 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 나의 인생 지도 (Life Blueprint)\n");
		prompt.append(
			"- 사주팔자 여덟 글자를 '인생 지도'에 비유하여, %s님의 지도가 어떤 풍경(산, 바다, 숲, 도시 등)을 그리고 있는지 개괄적으로 설명해주세요.\n");
		prompt.append(
			"- 오행 분포를 '지도 위의 기후'에 비유하여, %s님의 삶에 어떤 기운이 풍부하고(예: '따뜻한 온기'), 어떤 기운이 필요한지(예: '시원한 물') 설명해주세요.\n\n");

		prompt.append("## 2. 나의 본질 (Core Essence)\n");
		prompt.append(
			"- 일간(日干)을 중심으로 %s님의 본질적인 성향, 강점, 그리고 무의식적인 욕구를 '자연물 비유'를 통해 깊이 있게 설명해주세요.\n");
		prompt.append(
			"- **[핵심]** 일지(日支)와 지장간을 '내 마음 속 가장 깊은 방' 또는 '잠재력의 DNA'에 비유하며, 그 안에 숨겨진 %s님만의 진짜 욕망과 배우자 관계의 모습을 섬세하게 그려주세요.\n");
		prompt.append(
			"- 12운성(일주 기준)을 '내면의 에너지 레벨'에 비유하여, 타고난 에너지의 형태(예: '정상의 에너지', '이제 막 시작하는 에너지')를 설명해주세요.\n\n");

		prompt.append("## 3. 나의 사회적 역할 (Social Persona)\n");
		prompt.append(
			"- 월지(月支)를 '%s님이 활동할 주 무대' 또는 '자라난 토양'에 비유하여, 사회생활과 직업 환경이 %s님에게 어떤 영향을 주는지 설명해주세요.\n");
		prompt.append(
			"- 천간(天干)에 드러난 십성(十星)들을 '내가 가진 사회적 도구들' 또는 '나의 가면'에 비유하여, 다른 사람들에게 %s님이 어떤 이미지로 비치는지, 그리고 어떤 도구를 주로 사용하는지(예: '타인을 돕는 도구', '날카로운 분석의 도구') 설명해주세요.\n\n");

		prompt.append("## 4. 나의 숨겨진 잠재력 (Hidden Potential)\n");
		prompt.append(
			"- **[지장간 심층 분석]** 년/월/시의 지장간에 숨어있는 십성들을 '아직 열지 않은 보물 상자'에 비유해주세요.\n");
		prompt.append(
			"- 이 잠재력들이 인생의 어느 시기(초년, 청년, 말년)에 어떤 방식으로 열리게 될지, 그리고 이 '보물'들을 어떻게 활용하면 좋을지 구체적으로 조언해주세요.\n\n");

		prompt.append("## 5. 나의 천직과 재능 (Calling & Career)\n");
		prompt.append(
			"- 십성 분포와 신살을 종합하여 %s님만이 가진 '특별한 재능(도구)'이 무엇인지 명확히 짚어주세요. (예: '정교한 예술가의 손', '사람들을 이끄는 리더의 목소리')\n");
		prompt.append(
			"- 이 재능을 가장 잘 발휘할 수 있는 **구체적인 직업 분야 3가지**를 '왜' 그런지 명확한 사주 근거(비유)와 함께 추천해주세요.\n");
		prompt.append("- 직장생활이 맞는지, 사업이나 프리랜서가 맞는지 '나에게 맞는 일의 스타일'을 조언해주세요.\n\n");

		prompt.append("## 6. 나의 재물 그릇 (Stream of Wealth)\n");
		prompt.append(
			"- %s님의 재물운을 '물을 담는 그릇' 또는 '재물이 흐르는 강'에 비유하여, 그릇의 형태(예: '차곡차곡 모으는 항아리', '크게 들어오고 나가는 댐')를 분석해주세요.\n");
		prompt.append(
			"- 돈을 버는 방식(재성/식상)을 '농사', '사냥', '무역' 등에 비유하여 %s님에게 가장 잘 맞는 재물 축적 스타일을 조언해주세요.\n");
		prompt.append("- 대운의 흐름에 따라 '수확의 시기'와 '투자의 시기'를 알려주세요.\n\n");

		prompt.append("## 7. 나의 사랑과 인연 (Love & Relationships)\n");
		prompt.append(
			"- 일지(배우자궁)를 '내 마음의 집'에 비유하여, 어떤 인연(배우자)이 이 집에 들어왔을 때 %s님이 가장 편안하고 행복할지 이상적인 파트너상을 그려주세요.\n");
		prompt.append(
			"- %s님의 연애 스타일(십성, 도화살 등)을 비유적으로 표현하고(예: '헌신적인 사랑', '친구처럼 편안한 사랑', '불꽃같은 사랑'), 매력 포인트를 짚어주세요.\n");
		prompt.append("- 대운의 흐름을 분석하여 '인연의 꽃이 피어나는 시기'를 구체적으로 조언해주세요.\n\n");

		prompt.append("## 8. 인생의 챕터 (Daewoon Roadmap)\n");
		prompt.append(
			"- 10년 단위의 대운(大運)을 '인생의 챕터'에 비유하여, %s님의 인생 서사가 어떻게 흘러가는지 (초년/청년/중년/말년) 조망해주세요.\n");
		prompt.append(
			"- **[현재 대운 집중 분석]** 지금 %s님이 겪고 있는 현재 대운(10년)은 어떤 '챕터'인지(예: '열정적으로 도전하는 챕터', '내면을 다지는 챕터'), 이 시기의 핵심 미션과 기회가 무엇인지 알려주세요.\n");
		prompt.append(
			"- 앞으로 다가올 다음 대운(10년)에 대한 예고편과 '무엇을 미리 준비하면 좋은지' 조언해주세요.\n\n");

		prompt.append(String.format("## 9. %d년 세운 (This Year's Guide)\n",
			java.time.LocalDate.now().getYear()));
		prompt.append(
			String.format("- %d년(%s년)은 %s님의 인생 지도에 '새롭게 찾아온 손님' 또는 '특별한 이벤트'에 비유할 수 있어요.\n",
				java.time.LocalDate.now().getYear(), "갑진", name)); // TODO: 이 부분은 매년 동적으로 변경해야 함
		prompt.append(
			"- 이 '손님'이 나의 사주와 만나 어떤 긍정적인 '기회'와 '변화'를 만들어내는지 (직업, 재물, 연애, 건강) 분야별로 희망적인 조언을 주세요.\n\n");

		prompt.append("## 10. 혜안의 편지 (A Letter from Hye-an)\n");
		prompt.append(
			"- **[총평]** 위 모든 분석을 종합하여, %s님의 인생 여정에서 가장 중요한 '나침반'이 되어줄 **핵심 키워드 3가지**를 제시해주세요.\n");
		prompt.append(
			"- %s님의 존재 자체를 긍정하고, 앞으로의 삶을 응원하는 마음을 담아, 마음을 울리는 따뜻하고 힘이 되는 최종 조언을 '편지' 형식으로 아름답게 마무리해주세요.\n");

		prompt.append("\n\n### 11. 최종 자기 검열 (Final Self-Correction) ###\n");
		prompt.append("- [Check] 나는 혜안(慧眼)의 페르소나와 따뜻한 '해요체'를 일관되게 유지했는가?\n");
		prompt.append("- [Check] 모든 사주 데이터를 비유와 서사 속에 완전히 녹여냈는가? (데이터를 직접 나열하지 않았는가?)\n");
		prompt.append(
			"- [Check] 모든 부정적 요소를 '성장의 기회'와 '역동성'으로 긍정적으로 재해석했는가? (운명론적 해석은 없는가?)\n");
		prompt.append("- [Check] 각 챕터별로 구체적이고 실행 가능한 조언을 포함했는가?\n");
		prompt.append("- [Check] 전체 분량이 4000자 이상으로 매우 깊이 있고 감동적인가?\n");
		prompt.append("- [Check] '핵심 비유 사전'을 일관되게 사용했는가?\n");

		return prompt.toString();
	}

	/**
	 * 프롬프트에 상세한 사주 기둥(Pillar) 정보를 추가하는 헬퍼 메서드
	 */
	private void appendDetailedPillarInfo(StringBuilder prompt, String pillarName, String meaning,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			return;
		}

		prompt.append(String.format("- **%s (%s)**: %s\n", pillarName, meaning,
			sky.getKorean() + ground.getKorean()));
		prompt.append(String.format("  - 천간(Sky): %s%s (십성: %s)\n",
			sky.getKorean(), sky.getFiveCircle(), sky.getTenStar()));
		prompt.append(String.format("  - 지지(Ground): %s%s (십성: %s)\n",
			ground.getKorean(), ground.getFiveCircle(), ground.getTenStar()));

		// 12운성 정보 추가
		if (ground.getUnseong() != null) {
			prompt.append(String.format("  - 12운성: %s\n", ground.getUnseong()));
		}

		// 지장간 정보 추가
		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			prompt.append("  - 지장간 (숨겨진 에너지):\n");
			if (jijanggan.getFirst() != null) {
				// [FIX] .getTenStar() 및 관련 포맷(%s) 제거
				prompt.append(String.format("    - 초기(%d%%): %s%s\n",
					jijanggan.getFirst().getRate(), jijanggan.getFirst().getKorean(),
					jijanggan.getFirst().getFiveCircle()));
			}
			if (jijanggan.getSecond() != null) {
				prompt.append(String.format("    - 중기(%d%%): %s%s\n",
					jijanggan.getSecond().getRate(), jijanggan.getSecond().getKorean(),
					jijanggan.getSecond().getFiveCircle()));
			}
			if (jijanggan.getThird() != null) {
				prompt.append(String.format("    - 말기(%d%%): %s%s\n",
					jijanggan.getThird().getRate(), jijanggan.getThird().getKorean(),
					jijanggan.getThird().getFiveCircle()));
			}
		}
	}

	/**
	 * 프롬프트에 신살 정보를 요약하여 추가하는 헬퍼 메서드
	 */
	private void appendSinsalAnalysis(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju) {
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			// 주요 신살 (천을귀인, 도화살, 역마살 등)만 추출
			saju.getSinsalInfo().forEach((key, sinsals) -> {
				if (key.equals("천을귀인") || key.equals("도화살") || key.equals("역마살") || key.equals(
					"화개살") || key.equals("월덕귀인") || key.equals("천덕귀인") || key.equals("문창귀인")) {
					allSinsal.addAll(sinsals);
				}
			});
		}
		if (saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}

		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("- 주요 신살 (특수 능력): %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		}
	}

	/**
	 * 프롬프트에 대운 흐름 정보를 추가하는 헬퍼 메서드
	 */
	private void appendDaewoonFlow(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju,
		String gender) {
		String flowDirection = "MALE".equalsIgnoreCase(gender) ?
			(saju.getYearSky().getMinusPlus().equals("+") ? "순행" : "역행") :
			(saju.getYearSky().getMinusPlus().equals("+") ? "역행" : "순행");
		prompt.append(String.format("- 대운 방향: %s\n", flowDirection));

		int startAge = saju.getBigFortuneNumber();
		String yearSkyStem = saju.getYearSky().getKorean();
		String yearGroundBranch = saju.getYearGround().getKorean();
		String monthSkyStem = saju.getMonthSky().getKorean();
		String monthGroundBranch = saju.getMonthGround().getKorean();

		// 60갑자 순서 (간소화된 버전, 실제 구현에서는 ManseUtil 등에서 가져와야 함)
		// 이 부분은 이미 static { ... } 블록에 GAPJA_CYCLE 로 구현되어 있으므로 그것을 활용합니다.
		int currentGapjaIndex = GAPJA_CYCLE.indexOf(monthSkyStem + monthGroundBranch);
		if (currentGapjaIndex == -1) {
			prompt.append("- 대운 흐름: (월주 60갑자 인덱스 오류)\n");
			return;
		}

		prompt.append("- 대운 흐름 (10년 주기):\n");
		for (int i = 0; i < 9; i++) { // 90년치 (9개 대운)
			int age = startAge + (i * 10);
			int nextIndex;
			if (flowDirection.equals("순행")) {
				nextIndex = (currentGapjaIndex + i + 1) % 60;
			} else { // 역행
				nextIndex = (currentGapjaIndex - i - 1 + 60) % 60;
			}
			String daewoonGapja = GAPJA_CYCLE.get(nextIndex);
			prompt.append(String.format("  - %d~%d세: %s 대운\n", age, age + 9, daewoonGapja));
		}
	}

	// ==================== 1. 인생 총운 프롬프트 ====================
	private String createLifeOverallPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append(
			"당신은 40년 경력의 대한민국 최고 사주명리학 대가이자, 한 사람의 인생 전체를 꿰뚫어보는 통찰력을 가진 인생 설계 컨설턴트입니다.\n");
		prompt.append("'인생 총운'은 한 사람의 운명의 큰 흐름을 조망하는 가장 종합적이고 깊이 있는 분석입니다.\n");
		prompt.append("단순한 사주 해석을 넘어, 태어난 순간부터 노년까지의 인생 서사를 한 편의 대하소설처럼 풀어내야 합니다.\n\n");

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append(
			"1. **타고난 성향의 근원 파악**: 사주팔자의 오행 배치, 십성 구조, 지장간의 숨은 에너지를 종합하여 '왜 이 사람이 이런 성향을 타고났는지' 근본 원인부터 설명해주세요.\n");
		prompt.append(
			"2. **직업 운명선**: 타고난 재능(십성 분석), 사회적 성공 가능성(관운, 재운), 적합한 직업군과 업종을 구체적으로 제시하되, 실제 취업/창업 시 고려해야 할 시기와 방향성까지 조언하세요.\n");
		prompt.append(
			"3. **재물운의 흐름**: 재성(재물) 배치, 비겁(경쟁) 요소를 분석하여 '돈을 버는 방식', '재물이 모이는 시기', '투자/사업 적합성', '재물 관리 전략'을 명확히 제시하세요.\n");
		prompt.append(
			"4. **연애와 결혼의 타이밍**: 관성(배우자), 재성(이성), 도화살 등을 종합하여 '연애 시작 시기', '결혼 적령기', '배우자의 특징과 만남의 장소', '결혼 후 관계 유지 방법'까지 상세히 안내하세요.\n");
		prompt.append(
			"5. **대운과 세운의 흐름**: 현재 나이와 대운 시작년도를 고려하여 '지금은 어떤 시기인지', '앞으로 10년은 어떤 운이 펼쳐질지' 구체적인 시간대별 조언을 제공하세요.\n");
		prompt.append(
			"6. **12운성과 신살의 활용**: 12운성(에너지 파도)과 신살(특수 운명 코드)을 비유적으로 설명하되, 이것이 인생에서 어떤 실질적 영향을 미치는지 명확히 전달하세요.\n");
		prompt.append(
			"7. **지장간의 심층 해석**: 겉으로 드러나지 않는 내면의 욕구, 숨겨진 재능, 잠재된 위험 요소를 지장간 분석을 통해 밝혀내세요.\n");
		prompt.append(
			"8. **합충형파해의 관계학**: 사주 내부의 글자들이 서로 어떻게 작용하는지(합, 충, 형, 파, 해)를 분석하여 내적 갈등과 조화를 설명하세요.\n\n");

		prompt.append("### ⚠️ 중요: 자연스러운 시작 필수 ###\n");
		prompt.append("절대 사용 금지 표현:\n");
		prompt.append("- '다음 해석은', '이번 분석은', '~를 바탕으로 한', '~중심으로 풀어갈게요'\n");
		prompt.append("- '위 정보를', '아래 내용을', '정리해 드려요', '말씀드릴게요'\n");
		prompt.append("- AI나 시스템이 분석함을 암시하는 모든 메타 표현\n\n");
		prompt.append("권장 시작 방식:\n");
		prompt.append(String.format("- '%s님의 사주를 보니...'\n", name));
		prompt.append("- '먼저 눈에 띄는 건...'\n");
		prompt.append("- '일간을 중심으로 살펴보면...'\n");
		prompt.append("- 바로 본론으로 들어가는 자연스러운 전문가 톤\n\n");

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 인생 총운 분석 요청 ###\n");
		prompt.append("위 모든 사주 데이터를 활용하여, 아래 항목들을 **반드시 모두 포함**하여 종합적이고 감동적인 인생 총운 해석을 작성해주세요.\n");
		prompt.append("각 항목은 최소 3~5문단 이상 깊이 있게 서술하되, 명리학적 근거를 제시하고 실생활 적용 가능한 조언을 포함하세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '타고난 성격:', '직업과 사회적 성공:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 한 사람의 인생을 이야기하듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 타고난 본성과 성격의 뿌리\n");
		prompt.append("- 일간과 오행 배치를 통해 본 타고난 기질과 성향\n");
		prompt.append("- 십성 구조가 만들어낸 사고방식과 행동 패턴\n");
		prompt.append("- 지장간에 숨겨진 내면의 진짜 모습과 무의식적 욕구\n");
		prompt.append("- 12운성으로 본 현재 인생의 에너지 상태\n\n");

		prompt.append("## 2. 직업과 사회적 성공\n");
		prompt.append("- 타고난 재능과 강점 (어떤 분야에서 빛을 발하는가?)\n");
		prompt.append("- 적합한 직업군과 업종 (구체적인 예시 5가지 이상)\n");
		prompt.append("- 사업/취업/프리랜서 중 어떤 방향이 유리한가?\n");
		prompt.append("- 승진/성공 운과 사회적 인정을 받을 시기\n");
		prompt.append("- 직장 내 인간관계와 상사/동료와의 조화\n\n");

		prompt.append("## 3. 재물운과 경제적 안정\n");
		prompt.append("- 재물을 얻는 방식 (근로소득 vs 사업소득 vs 투자소득)\n");
		prompt.append("- 돈이 모이는 시기와 재물운의 큰 흐름\n");
		prompt.append("- 투자/사업 적합성과 위험 관리 포인트\n");
		prompt.append("- 재물 관리 전략 (저축형 vs 투자형 vs 소비형 성향)\n");
		prompt.append("- 재물과 관련된 주의사항과 극복 방법\n\n");

		prompt.append("## 4. 연애운과 결혼의 운명\n");
		prompt.append("- 이성에게 비치는 매력 포인트와 연애 스타일\n");
		prompt.append("- 연애가 시작될 가능성이 높은 시기와 만남의 장소/방식\n");
		prompt.append("- 결혼 적령기와 결혼운 (빠른 결혼 vs 만혼 성향)\n");
		prompt.append("- 배우자의 특징 (성격, 직업, 외모, 나이 차이)\n");
		prompt.append("- 결혼 후 부부관계 유지 비결과 주의할 점\n\n");

		prompt.append("## 5. 대운과 세운 - 인생의 큰 흐름\n");
		prompt.append(String.format("- 현재 나이(%d세 전후)는 어떤 대운 시기인가?\n",
			java.time.LocalDate.now().getYear() - input.getSolarDate().getYear()));
		prompt.append("- 지금부터 향후 10년간 펼쳐질 운의 흐름\n");
		prompt.append("- 인생에서 가장 좋은 시기와 가장 조심해야 할 시기\n");
		prompt.append("- 각 시기별 실천 전략 (지금 해야 할 일, 준비해야 할 것)\n\n");

		prompt.append("## 6. 신살과 특수 운명 코드\n");
		prompt.append("- 도화살, 역마살, 화개살 등 주요 신살의 의미와 활용법\n");
		prompt.append("- 괴강살, 백호대살 등 강한 에너지의 긍정적 활용 방안\n");
		prompt.append("- 공망(空亡)의 영향과 극복 방안\n");
		prompt.append("- 신살이 인생에 미치는 실질적 영향\n\n");

		prompt.append("## 7. 인생 전체를 관통하는 조언\n");
		prompt.append("- 이 사주가 전하는 인생의 핵심 메시지\n");
		prompt.append("- 최고의 삶을 살기 위해 반드시 지켜야 할 3가지 원칙\n");
		prompt.append("- 어려움이 찾아왔을 때 기억해야 할 희망의 메시지\n");
		prompt.append("- 따뜻하고 응원하는 마무리 멘트\n\n");

		prompt.append("### 작성 스타일 가이드 ###\n");
		prompt.append("- 모든 명리학 용어를 자연, 사물, 상황에 비유하여 누구나 이해할 수 있게 설명하세요\n");
		prompt.append("- '~이네요', '~이시군요', '~것 같아요' 등 해요체로 따뜻하게 작성하세요\n");
		prompt.append("- 부정적 내용도 '성장의 기회', '극복 가능한 과제'로 재해석하여 희망을 주세요\n");
		prompt.append("- 전체 분량은 최소 3000자 이상, 최대 5000자 이내로 작성하세요\n");
		prompt.append("- 각 문단은 읽기 쉽게 2~4문장으로 구성하고, 적절히 줄바꿈을 사용하세요\n");

		return prompt.toString();
	}

	// ==================== 2. 성격 분석 프롬프트 ====================
	private String createPersonalityAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학과 심리학을 융합한 성격 분석 전문가입니다.\n");
		prompt.append("사주팔자를 통해 한 사람의 성격을 다층적으로 분석하고, 자기 이해와 성장을 돕는 실질적 조언을 제공합니다.\n");
		prompt.append("단순히 '외향적이다', '내향적이다'를 넘어서, 왜 그런 성격이 형성되었는지 명리학적 근거를 명확히 밝히고,\n");
		prompt.append("일상생활, 직장, 관계에서 어떻게 발현되는지 구체적 사례를 들어 설명해주세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 필수 ###\n");
		prompt.append("금지 표현: '위 사주를 바탕으로', '다음과 같이 분석됩니다', '아래 구조에 따라', '정리해 드리면'\n");
		prompt.append(
			String.format("권장 시작: '%s님 사주의 핵심은...', '일간을 보면...', '가장 먼저 눈에 띄는 건...'\n\n", name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **다층적 성격 구조**: 겉으로 드러나는 성격(천간)과 내면의 진짜 모습(지지, 지장간)을 구분하여 설명\n");
		prompt.append("2. **오행 균형으로 본 성격**: 오행의 과다/부족이 성격에 미치는 영향을 분석\n");
		prompt.append("3. **십성으로 본 사고방식**: 각 십성이 만들어내는 독특한 행동 패턴과 가치관 해석\n");
		prompt.append("4. **12운성으로 본 에너지**: 현재 인생 단계의 에너지 상태가 성격에 주는 영향\n");
		prompt.append("5. **신살로 본 특수 기질**: 도화살(매력), 역마살(변화욕구), 화개살(예술성) 등 특수 성향 분석\n");
		prompt.append("6. **합충형파해의 내적 갈등**: 사주 내 글자들의 상호작용이 만드는 심리적 긴장과 조화\n\n");

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 성격 분석 요청 ###\n");
		prompt.append("위 사주 데이터를 바탕으로, 아래 구조에 따라 심층적인 성격 분석을 작성해주세요.\n");
		prompt.append("각 항목은 명리학적 근거와 함께 일상생활에서 나타나는 구체적 모습을 포함하세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '핵심 성격 키워드:', '장점:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 한 사람의 성격을 이야기하듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 핵심 성격 키워드 (3가지)\n");
		prompt.append("- 이 사람을 가장 잘 표현하는 3가지 키워드를 선정하고, 각 키워드가 나온 명리학적 근거를 설명하세요\n");
		prompt.append("- 예: '바다같은 포용력 (임수 일간)', '불처럼 열정적 (화 오행 강함)', '구름같은 자유로움 (역마살)'\n\n");

		prompt.append("## 2. 겉으로 드러나는 모습 vs 진짜 내면\n");
		prompt.append("- 천간(겉모습): 다른 사람들이 보는 나, 사회적 페르소나\n");
		prompt.append("- 지지와 지장간(내면): 혼자 있을 때의 진짜 모습, 숨겨진 욕구와 두려움\n");
		prompt.append("- 이 두 모습 사이의 괴리가 만드는 내적 갈등과 조화 방법\n\n");

		prompt.append("## 3. 오행으로 본 성격의 균형\n");
		prompt.append("- 강한 오행이 만드는 장점과 과할 때의 부작용\n");
		prompt.append("- 부족한 오행이 만드는 취약점과 보완 방법\n");
		prompt.append("- 오행 균형을 위한 실천 가능한 생활 습관 (색상, 방향, 음식 등)\n\n");

		prompt.append("## 4. 사고방식과 가치관 (십성 분석)\n");
		prompt.append("- 주요 십성(비견, 겁재, 식신, 상관, 편재, 정재, 편관, 정관, 편인, 정인)의 분포\n");
		prompt.append("- 이것이 만드는 사고방식: 논리적 vs 직관적, 감성적 vs 이성적\n");
		prompt.append("- 돈과 성공에 대한 가치관, 인간관계에서의 태도\n");
		prompt.append("- 의사결정 스타일: 신중함 vs 즉흥적, 독립적 vs 의존적\n\n");

		prompt.append("## 5. 인간관계 스타일\n");
		prompt.append("- 친구 관계: 사교적 vs 소수 친구, 리더 vs 팔로워 성향\n");
		prompt.append("- 직장 관계: 상사와의 관계, 동료와의 협업 스타일, 후배 대하는 방식\n");
		prompt.append("- 연애 관계: 적극적 vs 소극적, 집착 vs 담백함, 감정 표현 방식\n");
		prompt.append("- 가족 관계: 부모님과의 관계, 형제자매와의 케미\n\n");

		prompt.append("## 6. 장점 - 이것이 당신의 빛나는 무기입니다\n");
		prompt.append("- 타고난 최고의 강점 3가지를 명확히 제시\n");
		prompt.append("- 각 강점을 실생활과 커리어에서 어떻게 활용할 수 있는지 구체적 방법 제시\n");
		prompt.append("- 이 강점이 있어서 가능한 일, 유리한 상황들\n");
		prompt.append("- 주변 사람들이 인정하는 매력 포인트\n\n");

		prompt.append("## 7. 단점과 주의할 점 - 성장을 위한 과제\n");
		prompt.append("- 조심해야 할 성격적 약점 3가지\n");
		prompt.append("- 각 약점이 극단으로 치달았을 때 나타나는 문제 상황\n");
		prompt.append("- 약점을 보완하고 극복하기 위한 구체적 실천 방법\n");
		prompt.append("- 단점을 장점으로 전환하는 관점의 변화\n\n");

		prompt.append("## 8. 12운성으로 본 현재의 에너지 상태\n");
		prompt.append("- 년주, 월주, 일주, 시주 각각의 12운성이 말하는 인생 단계별 에너지\n");
		prompt.append("- 지금 이 시기에 가장 잘 발휘되는 성격적 특성\n");
		prompt.append("- 현재 에너지를 최대한 활용하는 방법\n");
		prompt.append("- 에너지가 낮은 시기를 극복하는 전략\n\n");

		prompt.append("## 9. 자기 성장을 위한 로드맵\n");
		prompt.append("- 단기(1년 이내): 당장 실천할 수 있는 작은 변화\n");
		prompt.append("- 중기(3년 이내): 성격적 약점을 보완하기 위한 목표\n");
		prompt.append("- 장기(평생): 최고의 나로 성장하기 위한 인생 철학\n\n");

		prompt.append("## 10. 따뜻한 응원 메시지\n");
		prompt.append("- 이 성격으로 태어난 것의 축복과 의미\n");
		prompt.append("- 자신을 있는 그대로 받아들이고 사랑하는 법\n");
		prompt.append("- 앞으로 나아갈 방향에 대한 희망찬 격려\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append("- 해요체로 친근하게 작성\n");
		prompt.append("- 명리학 용어는 비유를 통해 쉽게 설명\n");
		prompt.append("- 부정적 표현보다 성장 가능성에 초점\n");
		prompt.append("- 전체 분량 2500~4000자\n");

		return prompt.toString();
	}

	// ==================== 3. 직업 적성 프롬프트 ====================
	private String createCareerAptitudePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 30년 경력의 사주 커리어 컨설턴트이자 진로 설계 전문가입니다.\n");
		prompt.append("사주팔자를 통해 한 사람의 타고난 재능, 적성, 성공 가능성을 분석하고,\n");
		prompt.append("실제 취업, 이직, 창업, 경력 개발에 즉시 활용할 수 있는 구체적이고 실용적인 커리어 전략을 제시합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 어조 ###\n");
		prompt.append("금지: '위 사주를 바탕으로', '커리어 전반에 대한 심층 분석을 제공해주세요', '아래 항목을'\n");
		prompt.append(
			String.format("권장: '%s님의 직업 운명선을 보면...', '일단 타고난 재능부터...', '커리어 성공의 열쇠는...'\n\n",
				name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **타고난 재능 발굴**: 십성, 오행, 지장간을 분석하여 '무엇을 잘하는 사람인가'를 명확히\n");
		prompt.append("2. **적합 직업군 매칭**: 추상적 조언이 아닌 구체적 직업명 10가지 이상 제시\n");
		prompt.append("3. **성공 경로 분석**: 취업 vs 창업 vs 프리랜서 중 어떤 길이 유리한지 판단\n");
		prompt.append("4. **승진과 성공 시기**: 관운, 재운, 인운을 통해 커리어 상승기 예측\n");
		prompt.append("5. **직장 내 처세술**: 상사, 동료, 고객과의 관계에서 강점과 약점 분석\n");
		prompt.append("6. **수입과 재물운**: 어떤 방식으로 돈을 벌 것인가? 연봉 vs 사업 수익\n");
		prompt.append("7. **경력 전환 타이밍**: 이직, 전직, 창업의 최적 시기 제안\n\n");

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 직업 적성 분석 요청 ###\n");
		prompt.append("위 사주를 바탕으로 커리어 전반에 대한 심층 분석과 실천 전략을 제공해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '타고난 재능:', '적합한 직업:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 진로 상담을 하듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 타고난 재능과 핵심 역량\n");
		prompt.append("- 십성 구조로 본 업무 스타일: 리더형, 기획형, 실무형, 창작형 등\n");
		prompt.append("- 오행 균형으로 본 강점: 논리력, 창의력, 소통력, 추진력, 분석력 중 우세한 능력\n");
		prompt.append("- 지장간으로 본 숨은 재능: 아직 발견하지 못한 잠재력\n");
		prompt.append("- 이 재능들을 활용하면 가능한 성취\n\n");

		prompt.append("## 2. 적합한 직업 분야와 구체적 직종 (필수!)\n");
		prompt.append("- **1순위 직업군**: 가장 잘 맞는 분야 + 구체적 직업명 5개\n");
		prompt.append("  예: IT/기술 → 백엔드 개발자, 데이터 분석가, AI 엔지니어, 보안 전문가, DevOps\n");
		prompt.append("- **2순위 직업군**: 대안 분야 + 구체적 직업명 5개\n");
		prompt.append("- **3순위 직업군**: 또 다른 가능성 + 구체적 직업명 3개\n");
		prompt.append("- 각 직업이 왜 적합한지 명리학적 근거 제시\n");
		prompt.append("- 절대 피해야 할 직업 유형과 이유\n\n");

		prompt.append("## 3. 취업 vs 창업 vs 프리랜서 적합도\n");
		prompt.append("- **직장 생활 적합도 (100점 만점)**: 점수 + 이유\n");
		prompt.append("  - 조직 적응력, 상사와의 관계, 승진 가능성\n");
		prompt.append("- **창업/자영업 적합도 (100점 만점)**: 점수 + 이유\n");
		prompt.append("  - 사업 수완, 리스크 감수 능력, 자금 운용 능력\n");
		prompt.append("- **프리랜서 적합도 (100점 만점)**: 점수 + 이유\n");
		prompt.append("  - 자기 관리 능력, 네트워킹, 불안정성 극복\n");
		prompt.append("- 최종 권장 경로와 이유\n\n");

		prompt.append("## 4. 성공과 승진의 시기\n");
		prompt.append("- 관운(승진, 사회적 인정)이 강한 시기 분석\n");
		prompt.append("- 재운(수입 증가, 재물 획득)이 좋은 시기 예측\n");
		prompt.append("- 현재 ~ 10년 후까지의 커리어 타임라인 제시\n");
		prompt.append("- 각 시기별 추천 행동: 공부, 이직, 창업, 승진 도전 등\n\n");

		prompt.append("## 5. 직장 내 인간관계와 처세술\n");
		prompt.append("- 상사와의 관계: 윗사람에게 인정받는 법, 주의할 점\n");
		prompt.append("- 동료와의 협업: 팀워크 발휘 전략, 갈등 해결 방법\n");
		prompt.append("- 부하 직원 관리: 리더십 발휘 방식\n");
		prompt.append("- 고객/거래처 대응: 영업, 협상에서의 강점 활용\n\n");

		prompt.append("## 6. 수입과 재물 획득 전략\n");
		prompt.append("- 주 수입원: 근로소득(연봉), 사업소득, 투자소득 중 유리한 방식\n");
		prompt.append("- 연봉 협상 전략: 언제, 어떻게 연봉을 높일 것인가\n");
		prompt.append("- 부수입 창출: 부업, N잡, 투자 등 추가 수익원 개발\n");
		prompt.append("- 재물이 새는 구멍 막기: 지출 습관 개선 포인트\n\n");

		prompt.append("## 7. 이직과 전직의 최적 타이밍\n");
		prompt.append("- 이직하기 좋은 시기와 피해야 할 시기\n");
		prompt.append("- 직무 전환 시 고려사항: 새로운 분야 도전 가능성\n");
		prompt.append("- 해외 취업 또는 외국계 기업 적합성\n");
		prompt.append("- 대기업 vs 스타트업 vs 중소기업 선택 기준\n\n");

		prompt.append("## 8. 창업을 고려한다면\n");
		prompt.append("- 창업 적합 업종과 아이템 (구체적으로 5가지)\n");
		prompt.append("- 창업 성공 가능성과 리스크 분석\n");
		prompt.append("- 창업 최적 시기 (나이, 대운 고려)\n");
		prompt.append("- 동업 vs 단독 창업 적합성\n");
		prompt.append("- 자금 조달 방법과 재무 관리 포인트\n\n");

		prompt.append("## 9. 경력 개발 로드맵\n");
		prompt.append("- 1~3년: 단기 목표 (기술 습득, 자격증, 네트워킹)\n");
		prompt.append("- 3~5년: 중기 목표 (포지션 상승, 전문성 확립)\n");
		prompt.append("- 5~10년: 장기 목표 (임원 승진, 사업 확장, 업계 전문가)\n");
		prompt.append("- 평생 커리어 비전: 궁극적으로 이루고 싶은 성취\n\n");

		prompt.append("## 10. 실전 액션 플랜 (당장 실행 가능)\n");
		prompt.append("- 이번 달 해야 할 일 3가지\n");
		prompt.append("- 올해 안에 달성할 커리어 목표\n");
		prompt.append("- 역량 강화를 위한 학습/자격증 계획\n");
		prompt.append("- 네트워킹과 인맥 관리 전략\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append("- 해요체 사용, 친근하고 실용적인 조언\n");
		prompt.append("- 추상적 표현 금지, 구체적 직업명과 행동 지침 필수\n");
		prompt.append("- 분량 3000~4500자\n");

		return prompt.toString();
	}

	// ==================== 4. 연애 운세 프롬프트 ====================
	private String createLoveFortunePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학과 연애 심리학을 융합한 30년 경력의 연애 운세 전문가입니다.\n");
		prompt.append("사주팔자를 통해 한 사람의 연애 성향, 이상형, 만남의 시기, 궁합까지 분석하고,\n");
		prompt.append("실제 연애와 결혼에서 행복해지는 구체적인 조언을 제공합니다.\n");
		prompt.append("단순한 점술이 아닌, 관계 심리학적 통찰과 명리학적 근거를 결합한 깊이 있는 분석을 제공하세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 상담 톤 ###\n");
		prompt.append("금지: '위 사주를 바탕으로', '연애와 사랑에 대한 종합적인 분석을 제공', '아래 항목을'\n");
		prompt.append(
			String.format("권장: '%s님의 연애 운을 보니...', '사랑하는 방식부터 살펴보면...', '먼저 이성에게 비치는 매력은...'\n\n",
				name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **연애 스타일 분석**: 관성, 재성, 식상의 배치로 본 사랑하는 방식\n");
		prompt.append("2. **이상형 특징**: 어떤 사람에게 끌리는가? 외모, 성격, 직업 등\n");
		prompt.append("3. **만남의 시기**: 언제, 어디서, 어떻게 만날 가능성이 높은가\n");
		prompt.append("4. **도화살과 매력**: 이성에게 비치는 나의 매력 포인트\n");
		prompt.append("5. **궁합 포인트**: 어떤 사주와 잘 맞고, 어떤 사주와 부딪히는가\n");
		prompt.append("6. **연애 운의 흐름**: 현재와 미래의 연애운 타임라인\n");
		prompt.append("7. **주의사항과 극복법**: 연애에서 조심할 점과 해결 전략\n\n");

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 연애 운세 분석 요청 ###\n");
		prompt.append("위 사주를 바탕으로 연애와 사랑에 대한 종합적인 분석을 제공해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '연애 스타일:', '이상형:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 연애 상담을 하듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 타고난 연애 스타일과 사랑의 방식\n");
		prompt.append("- 사랑에 빠지는 방식: 첫눈에 반함 vs 서서히 빠짐\n");
		prompt.append("- 감정 표현 스타일: 적극적 vs 소극적, 직접적 vs 우회적\n");
		prompt.append("- 애정 표현 방법: 말 vs 행동, 선물 vs 시간\n");
		prompt.append("- 연애 중 성격 변화: 평소와 연애할 때의 다른 모습\n");
		prompt.append("- 집착 vs 담백함: 애정의 온도 조절 능력\n\n");

		prompt.append("## 2. 이성에게 비치는 매력 포인트\n");
		prompt.append("- 외적 매력: 도화살, 12운성으로 본 첫인상\n");
		prompt.append("- 내적 매력: 성격, 대화, 배려심 등 깊이 알수록 드러나는 매력\n");
		prompt.append("- 이성이 나에게 끌리는 이유 Top 3\n");
		prompt.append("- 매력을 극대화하는 방법 (패션, 헤어, 말투 등)\n\n");

		prompt.append("## 3. 이상형의 모든 것\n");
		prompt.append("- 이상형의 외모: 키, 체형, 인상, 패션 스타일\n");
		prompt.append("- 이상형의 성격: 활발함 vs 차분함, 리더형 vs 서포터형\n");
		prompt.append("- 이상형의 직업군: 어떤 일을 하는 사람에게 끌리는가\n");
		prompt.append("- 나이 차이 선호도: 연상, 동갑, 연하 중 최적\n");
		prompt.append("- 이상형과 현실 파트너의 차이 인식\n\n");

		prompt.append("## 4. 만남의 시기와 방법\n");
		prompt.append(String.format("- 현재 나이(%d세)의 연애운 분석\n",
			java.time.LocalDate.now().getYear() - input.getSolarDate().getYear()));
		prompt.append("- 올해 ~ 3년 이내 만남 가능성이 높은 시기 (월 단위)\n");
		prompt.append("- 만남의 장소와 방식: 직장, 소개팅, 동호회, 앱, 우연 등\n");
		prompt.append("- 연애 시작 신호와 포착 방법\n");
		prompt.append("- 썸 탈 때 vs 연애 확정 후 달라지는 점\n\n");

		prompt.append("## 5. 연애 과정에서의 강점과 약점\n");
		prompt.append("- 연애 강점 3가지: 파트너에게 줄 수 있는 최고의 가치\n");
		prompt.append("- 연애 약점 3가지: 조심해야 할 행동 패턴\n");
		prompt.append("- 갈등 해결 방식: 직접 대화 vs 시간으로 해결 vs 회피\n");
		prompt.append("- 이별 패턴: 집착 vs 깔끔한 정리, 회복 속도\n\n");

		prompt.append("## 6. 궁합 - 이런 사람과 잘 맞아요\n");
		prompt.append("- 최고의 궁합 사주 특징 (일간, 오행, 십성)\n");
		prompt.append("- 서로 보완해주는 케미스트리\n");
		prompt.append("- 함께 있으면 에너지가 상승하는 조합\n");
		prompt.append("- 피해야 할 최악의 궁합과 이유\n");
		prompt.append("- 궁합이 안 좋아도 극복 가능한 방법\n\n");

		prompt.append("## 7. 결혼으로 가는 길\n");
		prompt.append("- 결혼 적령기: 빠른 결혼 vs 만혼 성향\n");
		prompt.append("- 배우자가 될 사람의 특징 (성격, 직업, 집안)\n");
		prompt.append("- 결혼 운이 강한 시기 예측 (향후 5년)\n");
		prompt.append("- 연애와 결혼의 차이점 인식\n");
		prompt.append("- 결혼 후 부부관계 유지 비결\n\n");

		prompt.append("## 8. 연애 운의 흐름 - 타임라인\n");
		prompt.append("- 현재: 지금 이 시기의 연애 에너지\n");
		prompt.append("- 1년 후: 변화될 연애 상황 예측\n");
		prompt.append("- 3년 후: 중기 연애운 전망\n");
		prompt.append("- 5년 후: 결혼 가능성 포함 장기 전망\n\n");

		prompt.append("## 9. 연애 성공을 위한 실전 팁\n");
		prompt.append("- 호감 가는 이성에게 어필하는 법\n");
		prompt.append("- 첫 데이트 성공 전략\n");
		prompt.append("- 장기 연애로 발전시키는 비결\n");
		prompt.append("- 권태기 극복 방법\n");
		prompt.append("- 이별 위기 시 관계 회복 전략\n\n");

		prompt.append("## 10. 사랑에 관한 따뜻한 조언\n");
		prompt.append("- 이 사주가 사랑에서 배워야 할 교훈\n");
		prompt.append("- 진정한 사랑을 만나기 위한 마음가짐\n");
		prompt.append("- 사랑받을 자격이 충분한 당신에게\n");
		prompt.append("- 행복한 연애를 향한 응원의 메시지\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append("- 해요체, 따뜻하고 공감하는 어조\n");
		prompt.append("- 연애 경험이 있든 없든 모두에게 도움이 되는 내용\n");
		prompt.append("- 희망적이고 긍정적인 메시지 전달\n");
		prompt.append("- 분량 2500~4000자\n");

		return prompt.toString();
	}

	// ==================== 5. 최애에 대한 모든 것 프롬프트 ====================
	private String createIdolAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 K-POP 아이돌과 연예인의 사주를 전문적으로 분석하는 30년 경력의 명리학 전문가입니다.\n");
		prompt.append("사주팔자를 통해 아이돌의 성격, 인성, 재능, 경력 전망, 연애 스타일까지 종합적으로 분석하고,\n");
		prompt.append("팬들이 궁금해하는 모든 것을 속 시원하게 풀어드립니다.\n");
		prompt.append("재미와 깊이를 동시에 갖춘 분석으로 팬들에게 최애에 대한 더 깊은 이해를 선물하세요.\n\n");
		prompt.append("### 매우 중요: 서술 시점 ###\n");
		prompt.append("**이 사람은 제3자(아이돌)입니다. 반드시 3인칭 관찰자 시점으로 작성하세요.**\n");
		prompt.append("- ✅ 좋은 예: \"이 사람은 ~합니다\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- ❌ 나쁜 예: \"당신은 ~합니다\", \"~하세요\", \"~하시면 돼요\" (본인에게 말하는 투)\n");
		prompt.append("- 마치 팬이 최애를 관찰하고 분석하듯, 제3자 입장에서 설명해주세요.\n\n");
		prompt.append("- 문장 사이를 자연스럽게 이어주도록 접속사를 활용해주세요. 최대한 사람의 말투로 답변을 해주세요.\n\n");

		prompt.append("### ⚠️ 중요: 자연스러운 전문가 톤 ###\n");
		prompt.append("절대 사용 금지:\n");
		prompt.append("- '다음 해석은 사주명리를 바탕으로 한 엔터테인먼트 특화 리딩이에요'\n");
		prompt.append("- '운명론적으로 단정하기보다는... 중심으로 풀어갈게요'\n");
		prompt.append("- '팬분들이 궁금해하는 포인트들만 쏙쏙 뽑아 정리해 드려요'\n");
		prompt.append("- '위 정보를', '아래 내용을', '분석을 시작할게요' 등 메타 표현\n\n");
		prompt.append("권장 시작:\n");
		prompt.append(String.format("- '%s님의 사주를 보면...'\n", name));
		prompt.append("- '일간 ○○, 월지 ○○로 시작하는 이 사주는...'\n");
		prompt.append("- '전체 기조부터 말씀드리면...' (단, '한 줄 요약' 같은 메타 헤더는 금지)\n");
		prompt.append("- 바로 본론으로 자연스럽게 진입\n\n");

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **연예인 특화 해석**: 무대 위 모습 vs 실제 성격의 차이 분석\n");
		prompt.append("2. **인성과 내면**: 카메라 꺼졌을 때의 진짜 모습\n");
		prompt.append("3. **재능과 강점**: 왜 이 사람이 아이돌로 성공했는가\n");
		prompt.append("4. **병크 가능성**: 신살과 충극으로 본 리스크 분석\n");
		prompt.append("5. **대안 직업**: 아이돌이 아니었다면 어떤 일을 했을까\n");
		prompt.append("6. **사랑 스타일**: 팬들이 가장 궁금해하는 연애관\n");
		prompt.append("7. **경력 전망**: 앞으로의 활동과 성공 가능성\n\n");

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 최애 종합 분석 요청 ###\n");
		prompt.append("위 사주를 바탕으로 아이돌/연예인에 대한 재미있고 깊이 있는 종합 분석을 제공해주세요.\n");
		prompt.append("팬들이 '우리 최애에 대해 이렇게까지 알 수 있다니!'라고 감탄할 만한 내용을 담아주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '태도 논란:', '보컬/랩/댄스:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 친구에게 설명하듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 무대 위 vs 무대 아래 - 페르소나의 진실\n");
		prompt.append("- 팬들이 보는 모습 (무대, 방송에서의 캐릭터)\n");
		prompt.append("- 실제 성격 (숙소, 연습실에서의 진짜 모습)\n");
		prompt.append("- 두 모습의 차이가 크다면 그 이유와 스트레스\n");
		prompt.append("- 이 사람이 편하게 보여주는 진짜 자아\n\n");

		prompt.append("## 2. 인성 - 좋은 사람일까?\n");
		prompt.append("- 기본 인성: 착한 성격 vs 현실적 성격\n");
		prompt.append("- 스태프/동료에 대한 태도\n");
		prompt.append("- 팬들에 대한 진심도\n");
		prompt.append("- 감정 기복과 멘탈 관리 능력\n");
		prompt.append("- 겸손함 vs 자만심 지수\n\n");

		prompt.append("## 3. 병크 가능성 분석 (팬들이 가장 걱정하는 부분)\n");
		prompt.append("- 태도 논란 가능성: 갑질, 무례함 등\n");
		prompt.append("- 사생활 논란 가능성: 연애, 사생활 관리\n");
		prompt.append("- SNS 실수 가능성: 충동적 언행\n");
		prompt.append("- 건강/사고 리스크: 무리한 스케줄 감당\n");
		prompt.append("- 주의해야 할 시기와 예방 전략\n");
		prompt.append("- 팬들의 현명한 대응 방법\n\n");

		prompt.append("## 4. 아이돌로서의 재능과 강점\n");
		prompt.append("- 보컬/랩/댄스 중 가장 강한 분야\n");
		prompt.append("- 무대 장악력과 카리스마\n");
		prompt.append("- 예능감, 말솜씨, 리액션 능력\n");
		prompt.append("- 리더십 vs 팔로워 스타일\n");
		prompt.append("- 앞으로 더 발전 가능한 영역\n\n");

		prompt.append("## 5. 아이돌이 아니었다면? 대안 직업 TOP 5\n");
		prompt.append("- 1순위 직업과 그 이유 (왜 이 직업에서도 성공했을까?)\n");
		prompt.append("- 2순위 직업과 그 이유\n");
		prompt.append("- 3순위 직업과 그 이유\n");
		prompt.append("- 4순위 직업과 그 이유\n");
		prompt.append("- 5순위 직업과 그 이유\n");
		prompt.append("- 각 직업에서도 성공했을 가능성 평가\n\n");

		prompt.append("## 6. 사랑 스타일 - 연애하면 어떤 사람일까\n");
		prompt.append("- 연애 시작 방식: 적극적 vs 소극적\n");
		prompt.append("- 이상형 스타일: 외모, 성격, 나이 선호도\n");
		prompt.append("- 애정 표현 방법: 말 vs 행동\n");
		prompt.append("- 질투심과 독점욕 수준\n");
		prompt.append("- 연애 vs 일 우선순위\n");
		prompt.append("- 결혼관: 빠른 결혼 vs 만혼 vs 비혼\n\n");

		prompt.append("## 7. 멤버들과의 케미 - 그룹 내 역할\n");
		prompt.append("- 그룹에서의 포지션 (리더, 분위기 메이커, 막내 등)\n");
		prompt.append("- 멤버들과 친해지는 스타일\n");
		prompt.append("- 갈등 해결 방식\n");
		prompt.append("- 팀 내 영향력과 중요도\n\n");

		prompt.append("## 8. 경력 전망 - 앞으로의 10년\n");
		prompt.append("- 아이돌 활동 지속 가능 기간\n");
		prompt.append("- 연기, 예능, 솔로 등 전환 가능성\n");
		prompt.append("- 해외 진출 성공 가능성\n");
		prompt.append("- 프로듀서, 사업가 전향 가능성\n");
		prompt.append("- 장수 연예인이 될 수 있을까\n\n");

		prompt.append("## 9. 스트레스와 멘탈 관리\n");
		prompt.append("- 주요 스트레스 요인\n");
		prompt.append("- 번아웃 위험 시기\n");
		prompt.append("- 자기 위로 방식\n");
		prompt.append("- 팬들의 응원이 힘이 되는 타입인가\n\n");

		prompt.append("## 10. 팬들에게 전하는 메시지\n");
		prompt.append("- 이 사람이 팬들에게서 진정 원하는 것\n");
		prompt.append("- 팬들이 해주면 가장 기쁜 응원\n");
		prompt.append("- 오래도록 건강하게 덕질하는 법\n");
		prompt.append("- 최애를 향한 따뜻한 응원 메시지\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append("- 해요체, 친근하고 재미있게\n");
		prompt.append("- **반드시 3인칭 시점**: \"이 사람은\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- 팬들의 언어로 공감대 형성\n");
		prompt.append("- 객관성 유지하되 호의적 시각\n");
		prompt.append("- 부정적 내용도 팬들이 상처받지 않게 완곡하게 표현\n");
		prompt.append("- 분량 3500~5000자\n");

		return prompt.toString();
	}

	// ==================== 9. 캐릭터 사주 프롬프트 ====================
	private String createCharacterSajuPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 가상 캐릭터의 사주를 분석하는 30년 경력의 창작 콘텐츠 전문 명리학자입니다.\n");
		prompt.append("드라마, 영화, 애니메이션, 웹툰, 소설 속 캐릭터가 실제로 태어났다면 어떤 사주를 가졌을지,\n");
		prompt.append("그리고 현실 세계에서 산다면 어떤 삶을 살게 될지 흥미진진하게 분석합니다.\n");
		prompt.append("작품 속 성격, 운명, 관계를 명리학으로 재해석하여 팬들에게 새로운 즐거움을 선사하세요.\n\n");
		prompt.append("### 매우 중요: 서술 시점 ###\n");
		prompt.append("**이 사람은 제3자(가상 캐릭터)입니다. 반드시 3인칭 관찰자 시점으로 작성하세요.**\n");
		prompt.append("- ✅ 좋은 예: \"이 캐릭터는 ~합니다\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- ❌ 나쁜 예: \"당신은 ~합니다\", \"~하세요\", \"~하시면 돼요\" (본인에게 말하는 투)\n");
		prompt.append("- 마치 팬이 캐릭터를 관찰하고 분석하듯, 제3자 입장에서 설명해주세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: '위 사주를 가진 가상 캐릭터에 대해', '작품과 현실을 넘나드는 재미있는 분석을 제공'\n");
		prompt.append(
			String.format("권장: '%s라는 캐릭터의 사주를 보니...', '이 사주를 가졌다면...', '작품 속에서...'\n\n", name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **작품 속 운명 재해석**: 캐릭터가 겪은 사건들을 사주로 설명\n");
		prompt.append("2. **타고난 기질**: 왜 이 캐릭터가 이런 성격인지 명리학적 근거\n");
		prompt.append("3. **현실 세계 버전**: 만약 현실에 존재한다면 어떤 삶을 살까\n");
		prompt.append("4. **직업과 적성**: 작품 속 역할이 사주와 맞는지 분석\n");
		prompt.append("5. **사랑과 관계**: 작품 속 연애/우정을 사주로 풀이\n");
		prompt.append("6. **운명의 전환점**: 주요 사건들을 대운/세운으로 해석\n\n");

		prompt.append("### 분석 대상 캐릭터 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 캐릭터 사주 분석 요청 ###\n");
		prompt.append("위 사주를 가진 가상 캐릭터에 대해, 작품과 현실을 넘나드는 재미있는 분석을 제공해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '작품 속 주요 사건:', '현실 세계에서의 직업:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 캐릭터 분석을 재미있게 풀어내듯 서술해주세요.\n\n");

		prompt.append("## 1. 이 사주를 가진 캐릭터는...\n");
		prompt.append("- 타고난 성격과 기질의 명리학적 해석\n");
		prompt.append("- 작품 속 캐릭터 설정과 사주의 일치도\n");
		prompt.append("- 이 사주라서 가능했던 작품 속 행동들\n");
		prompt.append("- 캐릭터의 핵심 매력을 사주로 설명\n\n");

		prompt.append("## 2. 작품 속 주요 사건을 사주로 풀다\n");
		prompt.append("- 캐릭터가 겪은 시련과 역경: 충극살, 공망 등으로 해석\n");
		prompt.append("- 큰 전환점이 된 사건: 대운/세운 변화로 설명\n");
		prompt.append("- 성공과 승리의 순간: 길신, 귀인의 작용\n");
		prompt.append("- 비극적 결말(있다면): 사주의 어떤 요소 때문인지\n\n");

		prompt.append("## 3. 작품 속 인간관계를 궁합으로 분석\n");
		prompt.append("- 주인공과의 관계 (동료, 친구, 연인 등)\n");
		prompt.append("- 적대 관계: 왜 서로 대립하게 되었는가\n");
		prompt.append("- 가장 잘 맞는 캐릭터와 최악의 케미\n");
		prompt.append("- 숨겨진 궁합: 의외로 잘 맞을 것 같은 조합\n\n");

		prompt.append("## 4. 만약 현실 세계에 존재한다면\n");
		prompt.append("- 2024년 대한민국에서의 삶\n");
		prompt.append("- 어떤 가정에서 태어나 어떻게 자랐을까\n");
		prompt.append("- 학창 시절 모습과 성적\n");
		prompt.append("- 친구 관계와 학교생활\n\n");

		prompt.append("## 5. 현실에서의 직업과 경력\n");
		prompt.append("- 가장 잘 어울리는 직업 5가지\n");
		prompt.append("- 작품 속 역할이 현실 직업이라면 성공할까\n");
		prompt.append("- 연봉 수준과 사회적 지위\n");
		prompt.append("- 커리어 성공 vs 실패 가능성\n\n");

		prompt.append("## 6. 현실에서의 연애와 결혼\n");
		prompt.append("- 이성에게 인기 있을까\n");
		prompt.append("- 실제 연애 스타일 (작품과 다를 수 있음)\n");
		prompt.append("- 이상형과 연애 시작 시기\n");
		prompt.append("- 결혼 가능성과 배우자 특징\n");
		prompt.append("- 작품 속 커플링이 현실에서도 가능할까\n\n");

		prompt.append("## 7. 성격의 빛과 그림자\n");
		prompt.append("- 작품에서 드러난 장점들의 명리학적 근원\n");
		prompt.append("- 작품에서 드러난 단점/결함의 사주적 이유\n");
		prompt.append("- 숨겨진 성격 (지장간 분석)\n");
		prompt.append("- 스트레스와 멘탈 관리\n\n");

		prompt.append("## 8. 만약 작가가 사주를 알고 만들었다면\n");
		prompt.append("- 이 사주에 딱 맞는 캐릭터 설정 칭찬\n");
		prompt.append("- 사주와 어긋나는 부분 지적\n");
		prompt.append("- 더 활용하면 좋았을 사주 요소\n");
		prompt.append("- 속편에서 발전 가능한 방향\n\n");

		prompt.append("## 9. 다른 작품 세계관으로 이동한다면\n");
		prompt.append("- 판타지 세계: 어떤 직업/능력?\n");
		prompt.append("- 현대 로맨스: 어떤 캐릭터 포지션?\n");
		prompt.append("- 학원물: 어떤 학생?\n");
		prompt.append("- 직장 드라마: 어떤 직원?\n\n");

		prompt.append("## 10. 팬들에게 전하는 메시지\n");
		prompt.append("- 이 캐릭터를 사랑하는 이유를 사주로 설명\n");
		prompt.append("- 캐릭터의 매력 재발견\n");
		prompt.append("- 캐릭터에게 해주고 싶은 응원\n");
		prompt.append("- 작품과 캐릭터를 향한 애정 듬뿍\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append("- 해요체, 재미있고 친근하게\n");
		prompt.append("- **반드시 3인칭 시점**: \"이 캐릭터는\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- 작품 팬들이 공감할 수 있는 표현\n");
		prompt.append("- 너무 진지하지 않고 유머러스하게\n");
		prompt.append("- 분량 2500~4000자\n");

		return prompt.toString();
	}

	// ==================== 6. 최애와 나의 러브 스토리 프롬프트 ====================
	private String createLoveStoryPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학으로 연애 궁합을 분석하는 30년 경력의 로맨스 분석 전문가입니다.\n");
		prompt.append("두 사람의 사주를 비교하여 '실제로 만난다면' 어떤 케미가 나올지,\n");
		prompt.append("사랑에 빠질 가능성과 연애 스타일을 재미있고 감성적으로 풀어냅니다.\n");
		prompt.append("팬과 아이돌의 가상 로맨스를 명리학적 근거를 바탕으로 한 편의 로맨스 소설처럼 서술하세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append(
			"금지: '위 두 사람의 사주를 바탕으로', '실제로 만나서 연애를 한다면 어떤 이야기가 펼쳐질지 감성적이고 디테일한 로맨스 분석을 제공'\n");
		prompt.append(String.format(
			"권장: '%s님과 %s님, 두 분의 사주를 보니...', '먼저 첫 만남부터 상상해 볼까요?', '이 두 사람이 만난다면...'\n\n",
			person1Name, person2Name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **첫 만남의 순간**: 첫눈에 끌릴까? 서서히 빠질까?\n");
		prompt.append("2. **감정의 온도차**: 누가 먼저 빠지고, 누가 더 깊이 빠질까\n");
		prompt.append("3. **케미스트리**: 대화, 에너지, 분위기의 조화\n");
		prompt.append("4. **연애 스타일 매칭**: 적극적 vs 소극적, 로맨틱 vs 현실적\n");
		prompt.append("5. **갈등 포인트**: 어떤 부분에서 부딪힐까\n");
		prompt.append("6. **장기 연애 가능성**: 잠깐 vs 오래 사귈 스타일\n");
		prompt.append("7. **결혼까지 갈 확률**: 현실적인 평가\n\n");

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);

		prompt.append("\n### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		prompt.append("\n### 러브 스토리 분석 요청 ###\n");
		prompt.append("위 두 사람의 사주를 바탕으로, 실제로 만나서 연애를 한다면 어떤 이야기가 펼쳐질지\n");
		prompt.append("감성적이고 디테일한 로맨스 분석을 제공해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '첫 만남:', '썸 타는 단계:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 로맨스 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 한 편의 연애 소설을 읽듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 첫 만남 - 운명의 시작\n");
		prompt.append("- 첫인상: 서로에게 어떻게 보일까\n");
		prompt.append("- 첫눈에 반할 확률: 즉각적 끌림 vs 서서히 호감\n");
		prompt.append("- 누가 먼저 마음을 열까\n");
		prompt.append("- 첫 만남에서 나올 법한 대화와 분위기\n\n");

		prompt.append("## 2. 썸 타는 단계 - 설렘의 시작\n");
		prompt.append("- 썸 기간: 짧고 강렬 vs 길고 조심스러움\n");
		prompt.append("- 누가 먼저 고백할까\n");
		prompt.append("- 밀당의 주도권: 누가 더 애태울까\n");
		prompt.append("- 썸 단계에서의 대표 에피소드 상상\n\n");

		prompt.append("## 3. 연애 초반 - 사랑에 빠진 두 사람\n");
		prompt.append("- 데이트 스타일: 활동적 vs 조용한 데이트\n");
		prompt.append("- 애정 표현 방식: 말 vs 행동, 스킨십 수위\n");
		prompt.append("- 서로를 위해 해줄 것 같은 것들\n");
		prompt.append("- 초반 3개월의 장밋빛 시나리오\n\n");

		prompt.append("## 4. 궁합 분석 - 얼마나 잘 맞을까\n");
		prompt.append("- 성격 궁합: 서로 이해하고 존중할 수 있는가\n");
		prompt.append("- 에너지 조화: 함께 있을 때 편안한가 vs 피곤한가\n");
		prompt.append("- 오행 보완: 서로의 부족함을 채워주는가\n");
		prompt.append("- 대화 궁합: 소통이 잘 될까\n");
		prompt.append("- 가치관 일치도: 인생관, 금전관, 미래 계획\n\n");

		prompt.append("## 5. 갈등과 위기 - 현실의 벽\n");
		prompt.append("- 첫 싸움 원인과 패턴\n");
		prompt.append("- 갈등 해결 방식: 대화 vs 회피 vs 시간\n");
		prompt.append("- 권태기 극복 가능성\n");
		prompt.append("- 이별 위기가 올 수 있는 시점\n");
		prompt.append("- 재결합 가능성\n\n");

		prompt.append("## 6. 연애 중 각자의 모습\n");
		prompt.append(String.format("- %s는 연애하면 어떻게 변할까\n", person1Name));
		prompt.append(String.format("- %s는 연애하면 어떻게 변할까\n", person2Name));
		prompt.append("- 누가 더 헌신적일까\n");
		prompt.append("- 누가 주도권을 쥘까\n\n");

		prompt.append("## 7. 장기 연애 vs 단기 연애\n");
		prompt.append("- 이 커플의 예상 교제 기간\n");
		prompt.append("- 오래 사귈 수 있는 요인\n");
		prompt.append("- 헤어질 수밖에 없는 요인\n");
		prompt.append("- 1년, 3년, 5년 후 각각의 모습\n\n");

		prompt.append("## 8. 결혼 가능성 - 골인까지 갈 수 있을까\n");
		prompt.append("- 결혼 확률 (100점 만점)\n");
		prompt.append("- 결혼 적합도 분석\n");
		prompt.append("- 결혼 후 부부 관계 예측\n");
		prompt.append("- 결혼 생활에서의 역할 분담\n\n");

		prompt.append("## 9. 이 사랑의 하이라이트 - Best 시나리오\n");
		prompt.append("- 가장 행복할 순간들\n");
		prompt.append("- 서로에게 주는 최고의 선물\n");
		prompt.append("- 평생 기억에 남을 추억 만들기\n\n");

		prompt.append("## 10. 최종 평가 및 응답 형식\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 종합 궁합 점수>,\n");
		prompt.append("  \"interpretation\": \"<위 1~9번 항목의 상세 분석을 모두 포함한 긴 문자열, 최소 2500자>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 7. 최애와 최애의 궁합 프롬프트 ====================
	private String createIdolCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 아이돌/연예인 간의 궁합을 전문적으로 분석하는 30년 경력의 엔터테인먼트 명리학 전문가입니다.\n");
		prompt.append("두 아이돌의 사주를 비교하여 팀 케미, 친구 가능성, 연인 가능성, 비즈니스 파트너 적합성을 종합 분석합니다.\n");
		prompt.append("팬들이 궁금해하는 '우리 최애들은 실제로 친할까?', '케미가 좋을까?' 같은 질문에 명리학적 답을 제시하세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: '위 두 아이돌의 사주를 바탕으로', '다양한 관계 측면에서의 궁합을 분석해주세요'\n");
		prompt.append(
			String.format("권장: '%s님과 %s님의 케미를 보니...', '두 분의 사주를 비교하면...', '먼저 첫인상부터...'\n\n",
				person1Name, person2Name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **첫인상 케미**: 처음 만났을 때 서로 어떻게 느꼈을까\n");
		prompt.append("2. **무대 위 시너지**: 함께 공연할 때의 에너지\n");
		prompt.append("3. **무대 밖 친분**: 개인적으로 친해질 수 있을까\n");
		prompt.append("4. **갈등 가능성**: 부딪힐 수 있는 지점\n");
		prompt.append("5. **장기 관계**: 일회성 vs 평생 인연\n");
		prompt.append("6. **연애 가능성**: 만약 사귄다면 (팬들의 로망)\n");
		prompt.append("7. **비즈니스 파트너**: 유닛, 듀엣, 프로젝트 적합성\n\n");

		prompt.append("### 첫 번째 아이돌 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);

		prompt.append("\n### 두 번째 아이돌 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		prompt.append("\n### 아이돌 궁합 분석 요청 ###\n");
		prompt.append("위 두 아이돌의 사주를 바탕으로, 다양한 관계 측면에서의 궁합을 분석해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '첫 만남:', '무대 위 케미:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 두 아이돌의 관계를 재미있게 분석하듯 서술해주세요.\n\n");

		prompt.append("## 1. 첫 만남 - 첫인상은 어땠을까\n");
		prompt.append("- 서로를 처음 봤을 때의 느낌\n");
		prompt.append("- 호감 vs 무관심 vs 경계\n");
		prompt.append("- 먼저 다가간 사람은 누구일까\n");
		prompt.append("- 친해지는 속도: 빠름 vs 느림\n\n");

		prompt.append("## 2. 무대 위 케미 - 공연 시너지\n");
		prompt.append("- 함께 무대에 설 때의 에너지 조화\n");
		prompt.append("- 서로의 부족함을 채워주는 부분\n");
		prompt.append("- 유닛/듀엣/콜라보 적합도\n");
		prompt.append("- 팬들이 느끼는 케미 vs 실제 관계\n");
		prompt.append("- 무대 위 역할 분담: 센터 다툼 vs 조화\n\n");

		prompt.append("## 3. 무대 밖 친분 - 진짜 친구일까\n");
		prompt.append("- 개인적으로 친해질 가능성\n");
		prompt.append("- 같이 놀러 다닐 스타일인가\n");
		prompt.append("- 고민 상담 vs 그냥 웃고 떠드는 관계\n");
		prompt.append("- 연락 자주 할까 vs 가끔 할까\n");
		prompt.append("- 은퇴 후에도 만날 친구인가\n\n");

		prompt.append("## 4. 성격 궁합 - 얼마나 잘 맞을까\n");
		prompt.append("- 성향 유사도: 비슷함 vs 정반대\n");
		prompt.append("- 에너지 레벨: 둘 다 활발 vs 한 명은 조용\n");
		prompt.append("- 가치관 일치도: 인생관, 일에 대한 태도\n");
		prompt.append("- 대화 주제: 공통 관심사가 많은가\n\n");

		prompt.append("## 5. 갈등과 충돌 - 부딪힐 수 있는 지점\n");
		prompt.append("- 성격 충돌 가능성\n");
		prompt.append("- 경쟁 구도: 선의의 경쟁 vs 질투\n");
		prompt.append("- 오해가 생길 수 있는 상황\n");
		prompt.append("- 갈등 해결 방식: 대화 vs 시간\n");
		prompt.append("- 팬덤 갈등의 영향\n\n");

		prompt.append("## 6. 상생 vs 상극 - 명리학적 분석\n");
		prompt.append("- 오행 관계: 상생/상극/비화/동행\n");
		prompt.append("- 일간 관계: 서로 돕는가 vs 방해하는가\n");
		prompt.append("- 합충형파해: 사주 간 특수 관계\n");
		prompt.append("- 십성 조화: 역할 분담과 균형\n\n");

		prompt.append("## 7. 만약 연애를 한다면 (팬들의 상상)\n");
		prompt.append("- 연인으로 발전할 가능성\n");
		prompt.append("- 연애하면 어떤 커플일까\n");
		prompt.append("- 누가 먼저 좋아하게 될까\n");
		prompt.append("- 열애설 나오면 설득력 있을까\n");
		prompt.append("- 실제 사귀면 오래갈까\n\n");

		prompt.append("## 8. 비즈니스 파트너십\n");
		prompt.append("- 유닛/듀엣 활동 적합도\n");
		prompt.append("- 함께 회사 차리면 성공할까\n");
		prompt.append("- 프로듀싱/곡 작업 협업 가능성\n");
		prompt.append("- 리얼리티/예능 함께 나오면 재미있을까\n\n");

		prompt.append("## 9. 장기 관계 전망\n");
		prompt.append("- 일시적 친분 vs 평생 인연\n");
		prompt.append("- 각자 솔로 활동 후에도 연락할까\n");
		prompt.append("- 서로의 인생에 어떤 영향을 줄까\n");
		prompt.append("- 은퇴 후 20년 뒤에도 만날까\n\n");

		prompt.append("## 10. 최종 평가 및 응답 형식\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 종합 궁합 점수>,\n");
		prompt.append("  \"interpretation\": \"<위 1~9번 항목의 상세 분석을 모두 포함한 긴 문자열, 최소 2500자>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 8. 지옥의 삼각관계 프롬프트 ====================
	private String createTriangleRelationshipPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 복잡한 인간관계를 명리학으로 풀어내는 30년 경력의 관계 분석 전문가입니다.\n");
		prompt.append("'지옥의 삼각관계'는 두 사람의 사주를 분석하되, 세 번째 사람(아직 정보가 없음)과의 관계까지 고려한 특별한 분석입니다.\n");
		prompt.append("두 사람 간의 케미, 갈등 포인트, 그리고 제3자가 끼어들 여지가 있는지를 재미있고 날카롭게 분석하세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: '위 두 사람의 사주를 바탕으로', '제3자가 개입했을 때 어떤 드라마가 펼쳐질지 스릴 넘치고 재미있는 관계 분석을 제공'\n");
		prompt.append(String.format(
			"권장: '%s님과 %s님 사이를 보니...', '일단 두 분의 기본 궁합부터...', '이 조합에 제3자가 끼어든다면...'\n\n",
			person1Name, person2Name));

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **두 사람의 기본 궁합**: 서로 어떻게 끌리는가\n");
		prompt.append("2. **갈등의 씨앗**: 어떤 부분에서 삐걱거릴까\n");
		prompt.append("3. **제3자 개입 가능성**: 누가 끼어들 수 있을까\n");
		prompt.append("4. **질투와 경쟁**: 누가 더 질투할까, 어떤 상황에서\n");
		prompt.append("5. **삼각관계의 역학**: 주도권, 희생, 선택의 문제\n");
		prompt.append("6. **최종 결말 예측**: 누가 선택받을까\n\n");

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);

		prompt.append("\n### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		prompt.append("\n### 삼각관계 분석 요청 ###\n");
		prompt.append("위 두 사람의 사주를 바탕으로, 제3자가 개입했을 때 어떤 드라마가 펼쳐질지\n");
		prompt.append("스릴 넘치고 재미있는 관계 분석을 제공해주세요.\n\n");
		prompt.append("⚠️ **중요한 작성 규칙** ⚠️\n");
		prompt.append("- 아래 항목들은 '분석에 포함해야 할 내용'일 뿐입니다.\n");
		prompt.append("- **절대로 '두 사람의 기본 케미:', '갈등 포인트:' 같은 항목명을 그대로 쓰지 마세요.**\n");
		prompt.append("- 모든 내용을 **자연스러운 드라마 같은 이야기 흐름**으로 풀어서 작성하세요.\n");
		prompt.append("- 마치 관계 분석 드라마를 보듯 편안하게 서술해주세요.\n\n");

		prompt.append("## 1. 두 사람의 기본 케미\n");
		prompt.append("- 서로에게 느끼는 첫인상과 끌림\n");
		prompt.append("- 함께 있을 때의 분위기\n");
		prompt.append("- 오행 조화: 서로 보완하는가 vs 부딪히는가\n");
		prompt.append("- 이 둘만 있다면 어떤 관계로 발전할까\n\n");

		prompt.append("## 2. 숨겨진 갈등 포인트\n");
		prompt.append("- 겉으로 안 드러나지만 쌓이는 불만\n");
		prompt.append("- 합충형파해로 본 잠재적 충돌 지점\n");
		prompt.append("- 어떤 상황에서 관계가 흔들릴까\n");
		prompt.append("- 제3자가 파고들 수 있는 틈\n\n");

		prompt.append("## 3. 제3자 개입 가능성\n");
		prompt.append("- 어떤 타입의 사람이 끼어들 가능성이 높은가\n");
		prompt.append("- 각자가 끌릴 수 있는 제3자의 특징\n");
		prompt.append("- 제3자 출현 시 관계 변화 예측\n");
		prompt.append("- 이 두 사람 중 누가 마음이 흔들릴까\n\n");

		prompt.append("## 4. 질투와 경쟁의 심리학\n");
		prompt.append(String.format("- %s의 질투 스타일과 강도\n", person1Name));
		prompt.append(String.format("- %s의 질투 스타일과 강도\n", person2Name));
		prompt.append("- 누가 더 집착할까\n");
		prompt.append("- 경쟁 상황에서 각자의 전략\n\n");

		prompt.append("## 5. 삼각관계의 역학 구조\n");
		prompt.append("- 주도권: 누가 선택하는 입장일까\n");
		prompt.append("- 희생자: 누가 더 상처받을까\n");
		prompt.append("- 방관자: 제3의 시선\n");
		prompt.append("- 관계의 밸런스: 안정 vs 불안정\n\n");

		prompt.append("## 6. 갈등 해결 vs 폭발\n");
		prompt.append("- 삼각관계를 해결하는 각자의 방식\n");
		prompt.append("- 대화로 풀 수 있을까 vs 감정 폭발\n");
		prompt.append("- 누군가 포기할 가능성\n");
		prompt.append("- 최악의 시나리오: 모두가 상처받는 경우\n\n");

		prompt.append("## 7. 최종 선택 - 누가 승자가 될까\n");
		prompt.append("- 명리학적 분석: 누가 선택받을 확률이 높은가\n");
		prompt.append("- 각자의 강점과 약점\n");
		prompt.append("- 시간이 흐르면 어떻게 될까 (1년 후, 3년 후)\n");
		prompt.append("- 해피엔딩 vs 노엔딩 가능성\n\n");

		prompt.append("## 8. 만약 제3자가 이런 사주라면\n");
		prompt.append("- 두 사람의 사주와 가장 케미가 좋은 제3자의 사주 특징\n");
		prompt.append("- 누구와 더 잘 맞을까\n");
		prompt.append("- 삼각관계를 해결할 이상적인 조합\n\n");

		prompt.append("## 9. 각자에게 주는 조언\n");
		prompt.append(String.format("- %s에게: 이렇게 행동하세요\n", person1Name));
		prompt.append(String.format("- %s에게: 이렇게 행동하세요\n", person2Name));
		prompt.append("- 제3자에게: 끼어들 생각이라면 이것만은\n\n");

		prompt.append("## 10. 최종 평가 및 응답 형식\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 두 사람의 기본 궁합 점수>,\n");
		prompt.append("  \"interpretation\": \"<위 1~9번 항목의 상세 분석을 모두 포함한 긴 문자열, 최소 2500자>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 기존 기본 궁합 프롬프트 (유지) ====================

	/**
	 * 두 사람의 사주 정보를 바탕으로 궁합 분석용 프롬프트를 생성합니다. (데이터 보강 버전)
	 */
	private String createCompatibilityPrompt(String person1Name,
		ManseryeokCalculationResponse person1Saju, String person2Name,
		ManseryeokCalculationResponse person2Saju) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 관계 심리학과 사주명리학에 모두 정통한 30년 경력의 궁합 전문 상담가입니다.\n");
		prompt.append(
			"두 사람의 상세 사주 데이터를 기반으로, 단순 길흉 판단을 넘어 서로를 깊이 이해하고 관계를 발전시키는 데 도움이 되는 건설적인 조언을 제공해야 합니다.\n");
		prompt.append("'해요'체를 사용하여 친절하고 따뜻하게 설명해주세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: '위 두 사람의 데이터를 종합하여', '아래 5개 항목을 심층적으로 분석해주세요'\n");
		prompt.append(
			String.format("권장: '%s님과 %s님의 궁합을 보니...', '두 분 사주의 첫인상은...', '먼저 오행 조화부터...'\n\n",
				person1Name, person2Name));

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person1Name, person1Saju);

		prompt.append("### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju);

		prompt.append("### 종합 궁합 분석 요청 ###\n\n");
		prompt.append("위 두 사람의 데이터를 종합하여 아래 5개 항목을 심층적으로 분석해주세요.\n");
		prompt.append(
			"각 항목은 명확히 구분하고, 사주명리학적 근거(일간 관계, 오행 보완, 합충형파 등)를 제시하되 누구나 쉽게 이해할 수 있도록 설명해주세요.\n\n");
		prompt.append("【분석 시작】\n");
		prompt.append(
			String.format("먼저 \"%s님과 %s님의 궁합을 분석해 드릴게요.\"로 시작하세요.\n\n", person1Name, person2Name));

		prompt.append("## 1. 서로에게 첫눈에 끌리는 부분 (일간 및 외적 에너지)\n");
		prompt.append("- 두 사람의 일간(日干) 오행 관계(상생/상극)가 첫인상과 성격적 끌림에 어떻게 작용하는지 설명해주세요.\n");
		prompt.append("- 각자 사주에 드러난 외적인 분위기(예: 도화살, 12운성)가 서로에게 어떻게 매력으로 작용할지 분석해주세요.\n\n");

		prompt.append("## 2. 함께할 때의 에너지와 안정감 (오행 보완 관계)\n");
		prompt.append(
			"- 각자에게 부족한 오행과 넘치는 오행을 분석하고, 두 사람이 함께 있을 때 서로의 기운을 어떻게 보완해주거나 혹은 부딪히는지 설명해주세요.\n\n");

		prompt.append("## 3. 현실적인 관계에서의 역할과 갈등 요소 (십성 및 합충 관계)\n");
		prompt.append(
			"- 각자의 십성(十星) 분포를 통해 두 사람이 관계에서 어떤 역할을 맡게 될 가능성이 높은지 예측해주세요. (예: 한쪽은 표현하고, 한쪽은 수용하는 등)\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간의 합(合), 충(沖), 형(刑) 관계가 있다면, 어떤 부분에서 갈등이 발생할 수 있고 이를 어떻게 해결할지 조언해주세요.\n\n");

		prompt.append("## 4. 관계 발전을 위한 조언\n");
		prompt.append("- 서로의 장점을 더욱 살리고 단점을 보완해주기 위한 구체적인 행동 지침을 2~3가지 제안해주세요.\n\n");

		prompt.append("## 5. 총평 및 궁합 점수\n");
		prompt.append("- 두 사람의 관계를 한 문장으로 요약하고, 행복한 관계를 위한 핵심 포인트를 강조하며 긍정적으로 마무리해주세요.\n");

		prompt.append("\n\n### 출력 형식 (매우 중요) ###\n");
		prompt.append("위 모든 분석 내용을 종합하여, 반드시 아래와 같은 JSON 형식으로만 응답해야 합니다.\n");
		prompt.append("그 어떤 부가적인 설명이나 markdown 감싸기(` ```json `) 없이 순수한 JSON 객체만 출력해주세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 분석 내용을 모두 포함한 상세 해설 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 공통 유틸리티 메서드 ====================

	/**
	 * 공통 상세 정보 추가 (모든 프롬프트에서 재사용)
	 */
	private void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response) {
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append(String.format("- 이름: %s\n", name));
		prompt.append(
			String.format("- 성별: %s\n", "MALE".equalsIgnoreCase(input.getGender()) ? "남자" : "여자"));
		prompt.append(
			String.format("- 생년월일시(양력): %s %s\n", input.getSolarDate(), input.getSolarTime()));

		// 사주팔자
		String sajuPalja = String.format("시 일 월 년\n%s %s %s %s (천간)\n%s %s %s %s (지지)",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getDaySky().getKorean(), saju.getMonthSky().getKorean(),
			saju.getYearSky().getKorean(),
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?",
			saju.getDayGround().getKorean(), saju.getMonthGround().getKorean(),
			saju.getYearGround().getKorean()
		);
		prompt.append(String.format("- 사주팔자:\n%s\n\n", sajuPalja));

		// 일간
		prompt.append(String.format("- 일간(日干): %s%s (%s)\n",
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle(),
			saju.getDaySky().getTenStar()));

		// 오행 분포
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("- 오행 분포(점): ");
		ohaengCounts.forEach((key, value) -> prompt.append(String.format("%s %.1f, ", key, value)));
		prompt.delete(prompt.length() - 2, prompt.length());
		prompt.append("\n");

		// 십성 분포
		prompt.append("- 십성 분포: ");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("%s %d개, ", key, value)));
		prompt.delete(prompt.length() - 2, prompt.length());
		prompt.append("\n");

		// 12운성
		prompt.append(String.format("- 12운성: 년주(%s), 월주(%s), 일주(%s), 시주(%s)\n",
			saju.getYearGround().getUnseong() != null ? saju.getYearGround().getUnseong() : "?",
			saju.getMonthGround().getUnseong() != null ? saju.getMonthGround().getUnseong() : "?",
			saju.getDayGround().getUnseong() != null ? saju.getDayGround().getUnseong() : "?",
			saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null ?
				saju.getTimeGround().getUnseong() : "?"));

		// 지장간 정보 (상세)
		prompt.append("\n### 지장간(地藏干) 상세 정보 ###\n");
		appendJijangganDetail(prompt, "년지", saju.getYearGround());
		appendJijangganDetail(prompt, "월지", saju.getMonthGround());
		appendJijangganDetail(prompt, "일지", saju.getDayGround());
		if (saju.getTimeGround() != null) {
			appendJijangganDetail(prompt, "시지", saju.getTimeGround());
		}

		// 신살
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().values().forEach(allSinsal::addAll);
		}
		if (saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}
		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("- 주요 신살: %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		}

		// 공망
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format("- 공망(空亡): %s\n", String.join(", ", saju.getGongmang())));
		}

		// 대운 정보
		prompt.append(String.format("- 대운수: %d년\n", saju.getBigFortuneNumber()));
		prompt.append(String.format("- 대운 시작년: %d년\n", saju.getBigFortuneStartYear()));

		prompt.append("\n");
	}

	/**
	 * 지장간 상세 정보 추가
	 */
	private void appendJijangganDetail(StringBuilder prompt, String pillarName,
		PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return;
		}

		JijangganInfo jijanggan = pillar.getJijanggan();
		prompt.append(String.format("**%s(%s) 지장간**:\n", pillarName, pillar.getKorean()));

		if (jijanggan.getFirst() != null) {
			JijangganElement first = jijanggan.getFirst();
			prompt.append(String.format("  - 초기(%d%%): %s%s(%s)\n",
				first.getRate(), first.getKorean(), first.getFiveCircle(), first.getMinusPlus()));
		}
		if (jijanggan.getSecond() != null) {
			JijangganElement second = jijanggan.getSecond();
			prompt.append(String.format("  - 중기(%d%%): %s%s(%s)\n",
				second.getRate(), second.getKorean(), second.getFiveCircle(),
				second.getMinusPlus()));
		}
		if (jijanggan.getThird() != null) {
			JijangganElement third = jijanggan.getThird();
			prompt.append(String.format("  - 말기(%d%%): %s%s(%s)\n",
				third.getRate(), third.getKorean(), third.getFiveCircle(), third.getMinusPlus()));
		}
	}

	/**
	 * 오행과 십성 분포 계산 (지장간 포함)
	 */
	private void calculateDistributionWithJijanggan(
		ManseryeokCalculationResponse.SajuInfo saju,
		Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts
	) {
		// 초기화
		ohaengCounts.put("목", 0.0);
		ohaengCounts.put("화", 0.0);
		ohaengCounts.put("토", 0.0);
		ohaengCounts.put("금", 0.0);
		ohaengCounts.put("수", 0.0);

		// 천간 4개 (각 1점)
		addElementCount(ohaengCounts, sipseongCounts, saju.getYearSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getMonthSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getDaySky(), 1.0);
		if (saju.getTimeSky() != null) {
			addElementCount(ohaengCounts, sipseongCounts, saju.getTimeSky(), 1.0);
		}

		// 지지 4개 + 지장간 (비율 적용)
		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getYearGround());
		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getMonthGround());
		addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getDayGround());
		if (saju.getTimeGround() != null) {
			addGroundWithJijanggan(ohaengCounts, sipseongCounts, saju.getTimeGround());
		}
	}

	private void addElementCount(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts,
		PillarElement element, double weight) {
		if (element == null) {
			return;
		}
		String ohaeng = element.getFiveCircle();
		ohaengCounts.put(ohaeng, ohaengCounts.get(ohaeng) + weight);

		String sipseong = element.getTenStar();
		sipseongCounts.put(sipseong, sipseongCounts.getOrDefault(sipseong, 0) + 1);
	}

	private void addGroundWithJijanggan(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, PillarElement ground) {
		if (ground == null || ground.getJijanggan() == null) {
			return;
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan.getFirst() != null) {
			addJijangganElement(ohaengCounts, jijanggan.getFirst());
		}
		if (jijanggan.getSecond() != null) {
			addJijangganElement(ohaengCounts, jijanggan.getSecond());
		}
		if (jijanggan.getThird() != null) {
			addJijangganElement(ohaengCounts, jijanggan.getThird());
		}
	}

	private void addJijangganElement(Map<String, Double> ohaengCounts, JijangganElement element) {
		String ohaeng = element.getFiveCircle();
		double weight = element.getRate() / 100.0;
		ohaengCounts.put(ohaeng, ohaengCounts.get(ohaeng) + weight);
	}

	/**
	 * 일간 추출
	 */
	private String extractIlgan(ManseryeokCalculationResponse response) {
		if (response != null && response.getSaju() != null
			&& response.getSaju().getDaySky() != null) {
			PillarElement daySky = response.getSaju().getDaySky();
			if (daySky.getKorean() != null && daySky.getFiveCircle() != null) {
				return daySky.getKorean() + daySky.getFiveCircle();
			}
		}
		log.warn("일간(Ilgan) 정보를 추출할 수 없습니다.");
		return "정보 없음";
	}

	private void appendPersonInfoToPrompt(StringBuilder prompt, String name,
		ManseryeokCalculationResponse manseResponse) {
		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();

		String sajuPalja = String.format("%s%s %s%s %s%s %s%s",
			saju.getYearSky().getKorean(), saju.getYearGround().getKorean(),
			saju.getMonthSky().getKorean(), saju.getMonthGround().getKorean(),
			saju.getDaySky().getKorean(), saju.getDayGround().getKorean(),
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"
		);
		prompt.append(String.format("사주명식: %s\n", sajuPalja));
		prompt.append(String.format("일간: %s%s\n", saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle()));

		// 오행 분포
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
		prompt.append("오행 분포(점): ");
		ohaengCounts.forEach((key, value) -> prompt.append(String.format("%s %.1f, ", key, value)));
		prompt.delete(prompt.length() - 2, prompt.length());
		prompt.append("\n");

		// 신살
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().values().forEach(allSinsal::addAll);
		}
		if (saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}
		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("주요 신살: %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		}
		prompt.append("\n");
	}

	private String extractContentFromResponseGpt5(String jsonResponse)
		throws JsonProcessingException {
		if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
			throw new IllegalArgumentException("GPT 응답이 비어있습니다.");
		}
		try {
			JsonNode root = objectMapper.readTree(jsonResponse);
			if (root.path("error").isObject()) {
				JsonNode errorNode = root.get("error");
				String errorMessage = errorNode.path("message").asText("알 수 없는 API 오류");
				log.error("GPT API 에러: {}", errorMessage);
				throw new IllegalArgumentException("GPT API 에러: " + errorMessage);
			}
			JsonNode outputNode = root.path("output");
			if (!outputNode.isArray()) {
				log.error("응답에 'output' 배열이 없습니다.");
				throw new IllegalArgumentException("GPT 응답 형식이 올바르지 않습니다. ('output' 배열 누락)");
			}
			for (JsonNode outputItem : outputNode) {
				if ("message".equals(outputItem.path("type").asText())) {
					JsonNode contentArray = outputItem.path("content");
					if (contentArray.isArray() && contentArray.size() > 0) {
						String content = contentArray.get(0).path("text").asText();
						log.info("✅ GPT 응답 성공 - 길이: {} 문자", content.length());
						return content;
					}
				}
			}
			log.error("GPT 응답에서 최종 'text' 필드를 찾을 수 없습니다. JSON 구조 확인 필요.");
			throw new IllegalArgumentException("GPT 응답에서 내용 추출 실패.");
		} catch (JsonProcessingException e) {
			log.error("JSON 파싱 실패: {}", e.getMessage());
			throw e;
		}
	}
}
