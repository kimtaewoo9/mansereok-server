package com.mansereok.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Result;
import com.mansereok.server.entity.ResultStatus;
import com.mansereok.server.entity.User;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.repository.SubCategoryRepository;
import com.mansereok.server.service.request.Gpt5Request;
import com.mansereok.server.service.response.ManseryeokCalculationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.service.response.model.GptCompatibilityResponse;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

	// [수정됨] 글로벌 시스템 명령어 - 어조 혼용 지시 유지
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
			"'해요'체를 기본으로 사용하되, 전문적인 분석이나 정보를 전달할 때는 '~입니다', '~습니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 모두 갖춘 어조를 사용하세요.\n\n"
			+
			"--- USER QUERY ---\n";

	public ManseInterpretationService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl,
		ResultRepository resultRepository,
		UserService userService,
		CompatibilityResultRepository compatibilityResultRepository,
		SubCategoryRepository subCategoryRepository) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
		this.userService = userService;
	}

	@Async
	@Transactional
	public void interpret(
		String name,
		ManseryeokCalculationResponse response,
		String username,
		Long subcategoryId,
		Long paymentId
	) {
		log.info("✅ 사주 해석 요청 시작 - name: {}, subcategoryId: {}", name, subcategoryId);

		Result result = resultRepository.findByPaymentId(paymentId)
			.orElseThrow(EntityNotFoundException::new);

		String ilgan = "정보 없음";
		if (response != null && response.getSaju() != null
			&& response.getSaju().getDaySky() != null) {
			PillarElement daySky = response.getSaju().getDaySky();
			ilgan = daySky.getKorean() + daySky.getFiveCircle();
		}

		result.updateInformation(
			name,
			response.getInput().getSolarDate(),
			response.getInput().getSolarTime(),
			response.getInput().getGender(),
			response.getInput().getIsLunar(),
			ilgan
		);

		resultRepository.save(result);
		log.info("[Async] Result 정보 업데이트 및 상태 저장 완료: resultId={}", result.getId());

		try {
			String userPrompt = createPromptBySubcategory(subcategoryId, name, response);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				16384,
				// Max tokens (can be adjusted if needed, but removing length limits in prompt is key)
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

			result.completeInterpretation(interpretationText); // 해석 결과 및 상태(COMPLETED) 업데이트
			Result savedResult = resultRepository.save(result);
			log.info("Result 해석 결과 저장 및 상태 COMPLETED 변경 완료: resultId={}", savedResult.getId());
		} catch (Exception e) {
			log.error("[Async] GPT API 요청 또는 처리 중 오류 발생: paymentId={}, Error: {}", paymentId,
				e.getMessage(), e);
			// 오류 발생 시 상태 롤백 처리
			if (paymentId != null) {
				try {
					Result errorResult = resultRepository.findById(
						result != null ? result.getId() : -1L).orElse(null);

					if (errorResult != null && errorResult.getStatus() == ResultStatus.PROCESSING) {
						errorResult.setStatus(ResultStatus.INPUT_REQUIRED); // 또는 FAILED 상태
						// errorResult.setErrorMessage(e.getMessage().substring(0, Math.min(e.getMessage().length(), 250))); // 에러 메시지 저장 필드 있다면
						resultRepository.save(errorResult);
						log.info("[Async] 오류 발생으로 Result 상태 INPUT_REQUIRED로 롤백 시도: paymentId={}",
							paymentId);
					} else {
						log.warn(
							"[Async] 오류 롤백 처리 중 Result를 찾지 못했거나 상태가 PROCESSING이 아님: paymentId={}",
							paymentId);
					}
				} catch (Exception ex) {
					log.error("[Async] 오류 처리(상태 롤백) 중 추가 오류 발생: paymentId={}, Error: {}", paymentId,
						ex.getMessage(), ex);
				}
			}
		}
	}

	@Async
	@Transactional
	public void analyzeCompatibilityWithSubcategory(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response,
		Long subcategoryId,
		Long paymentId
	) {
		log.info("✅ 궁합 분석 요청 시작 - subcategoryId: {}, {} & {}", subcategoryId, person1Name,
			person2Name);

		CompatibilityResult result = null;
		try {
			result = compatibilityResultRepository.findByPaymentId(paymentId)
				.orElseThrow(EntityNotFoundException::new);

			// 두사람의 일간 정보 추출 ..
			String person1Ilgan = extractIlgan(person1Response);
			String person2Ilgan = extractIlgan(person2Response);

			result.updatePersonsInformation(person1Name, person1Ilgan, person2Name, person2Ilgan);

			compatibilityResultRepository.saveAndFlush(result);
			log.info("CompatibilityResult 상태 PROCESSING 변경 및 정보 업데이트: resultId={}", result.getId());

			// ChatGPT 해석 로직 .
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

			result.completeInterpretation(analysisText, score);
			CompatibilityResult savedResult = compatibilityResultRepository.save(result);
			log.info("CompatibilityResult 분석 결과 저장 및 상태 COMPLETED 변경 완료: resultId={}",
				savedResult.getId());
		} catch (EntityNotFoundException enfe) {
			log.error("[Async] EntityNotFoundException (궁합 초기 조회 실패): {}", enfe.getMessage());
		} catch (Exception e) {
			log.error("[Async] GPT API 궁합 분석 요청 또는 처리 중 오류 발생: paymentId={}, Error: {}", paymentId,
				e.getMessage(), e);
			// 오류 발생 시 상태 롤백 처리
			if (paymentId != null) {
				try {
					CompatibilityResult errorResult = compatibilityResultRepository.findById(
						result != null ? result.getId() : -1L).orElse(null);

					if (errorResult != null && errorResult.getStatus() == ResultStatus.PROCESSING) {
						errorResult.setStatus(ResultStatus.INPUT_REQUIRED);
						compatibilityResultRepository.save(errorResult);
						log.info(
							"[Async] 오류 발생으로 CompatibilityResult 상태 INPUT_REQUIRED로 롤백 시도: paymentId={}",
							paymentId);
					} else {
						log.warn(
							"[Async] 궁합 오류 롤백 처리 중 CompatibilityResult를 찾지 못했거나 상태가 PROCESSING이 아님: paymentId={}",
							paymentId);
					}
				} catch (Exception ex) {
					log.error("[Async] 궁합 오류 처리(상태 롤백) 중 추가 오류 발생: paymentId={}, Error: {}",
						paymentId, ex.getMessage(), ex);
				}
			}
		}
	}

	private String createPromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {

		return switch (subcategoryId.intValue()) {
			case 1 -> createLifeOverallPrompt(name, response);           // 인생 총운 (수정됨)
			case 2 -> createPersonalityAnalysisPrompt(name, response);   // 성격 분석 (수정됨)
			case 3 -> createCareerAptitudePrompt(name, response);        // 직업 적성 (수정됨)
			case 4 -> createLoveFortunePrompt(name, response);           // 연애 운세 (수정됨)
			case 5 -> createIdolAnalysisPrompt(name, response);          // 최애 분석 (수정됨 - 연애 강화 유지)
			case 9 -> createCharacterSajuPrompt(name, response);         // 캐릭터 사주 (수정됨)
			default -> createComprehensiveAnalysisPrompt(name, response); // 기본 종합 (수정됨)
		};
	}

	private String createCompatibilityPromptBySubcategory(
		Long subcategoryId,
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		return switch (subcategoryId.intValue()) {
			case 6 -> createLoveStoryPrompt(person1Name, person1Response, person2Name,
				person2Response); // 수정됨
			case 7 -> createIdolCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response); // 수정됨
			case 8 -> createTriangleRelationshipPrompt(person1Name, person1Response, person2Name,
				person2Response); // 수정됨
			default -> createCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response); // 기본 궁합 (수정됨)
		};
	}

	// ==================== 기본 종합 프롬프트 ====================
	private String createComprehensiveAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append("### 0. 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 경력의 사주명리학 대가이자, '인생 서사 상담가' 혜안(慧眼)입니다.\n");
		prompt.append(
			"당신은 한 사람의 고유한 인생 지도(사주팔자)를 해석하여, 그 사람의 잠재력을 긍정하고 삶의 여정을 응원하는 '안내자(Guide)'입니다.\n\n");

		prompt.append("### 1. 핵심 분석 원칙 (Core Principles) ###\n");
		prompt.append("1. **절대적 긍정성**: 모든 부정적 요소(충, 형, 흉살 등)는 '성장을 위한 역동적인 에너지'로만 해석합니다.\n");
		prompt.append("2. **서사적 스토리텔링**: 사주 데이터를 절대 나열하지 않고, '한 편의 이야기' 속에 자연스럽게 녹여냅니다.\n");
		prompt.append("3. **감성적 비유 활용**: '핵심 비유 사전'을 준수하여 일관되고 풍부한 비유로 설명합니다.\n");
		prompt.append(
			"4. **자연스러운 전문가 어조**: '해요체'를 기본으로 쓰되, 전문 정보 전달 시 '입니다' 체를 혼용하여 신뢰감과 친근함을 모두 전달합니다.\n");
		prompt.append("5. **깊이 있는 통찰**: 각 주제를 피상적으로 다루지 않고, 명리학적 근거를 바탕으로 심층 분석합니다.\n\n");

		prompt.append("### 2. 핵심 비유 사전 (Metaphor Lexicon) ###\n");
		prompt.append("- **사주팔자**: '인생 지도', '설계도'\n");
		prompt.append("- **일간**: '본질', '뿌리', '엔진' (자연물 비유)\n");
		prompt.append("- **월지**: '토양', '사회적 무대'\n");
		prompt.append("- **지장간**: 'DNA', '숨겨진 보물'\n");
		prompt.append("- **십성**: '10가지 도구', '사회적 역할'\n");
		prompt.append("- **12운성**: '에너지 리듬'\n");
		prompt.append("- **대운**: '10년 챕터', '계절 변화'\n");
		prompt.append("- **신살**: '특수 능력', '강력한 도구'\n");
		prompt.append("- **합/충/형/파**: '안정/변화 에너지', '역동성'\n\n");

		prompt.append("### 3. 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 운명론적 단정, 데이터 나열, AI/시스템 노출, 차갑거나 권위적인 어조 금지.\n\n");

		prompt.append("### 4. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response); // 상세 정보 주입 (헬퍼 메서드 사용)

		prompt.append("\n### 5. 종합 심층 분석 요청 ###\n");
		prompt.append("혜안 선생님, 위 데이터를 바탕으로 아래 **6가지 핵심 주제**에 대해 깊이 있는 인생 서사를 작성해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨네요.\" 로 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 나의 본질과 숨겨진 모습 (Core Essence & Hidden Self)\n");
		prompt.append("- 일간을 중심으로 타고난 성향, 강점, 약점을 '자연물 비유'를 통해 설명해주세요.\n");
		prompt.append(
			"- 일지(日支)와 지장간(地藏干)을 '내면의 DNA'에 비유하여, 겉으로 드러나지 않는 진짜 욕망과 무의식적 동기를 심층 분석해주세요.\n");
		prompt.append("- 겉모습(천간)과 속마음(지지, 지장간)의 차이가 있다면, 그 이유는 무엇이며 어떻게 조화를 이룰 수 있을지 조언해주세요.\n\n");

		prompt.append("## 2. 사회적 역할과 인간관계 (Social Role & Relationships)\n");
		prompt.append("- 월지(月支)를 '사회적 무대'에 비유하여, 어떤 환경에서 가장 편안함을 느끼고 역량을 발휘하는지 설명해주세요.\n");
		prompt.append(
			"- 십성(十星) 분포를 '내가 가진 도구들'에 비유하여, 사회생활과 인간관계에서 주로 어떤 역할을 하고 어떤 방식으로 관계를 맺는지 구체적인 예시와 함께 분석해주세요.\n");
		prompt.append("- 관계에서 중요하게 생각하는 가치와, 관계를 건강하게 유지하기 위한 조언을 포함해주세요.\n\n");

		prompt.append("## 3. 천직과 재능의 발현 (Calling & Career Path)\n");
		prompt.append("- 십성, 오행, 신살 등을 종합하여 %s님만이 가진 '핵심 재능'이 무엇인지 명확히 짚어주세요.\n");
		prompt.append(
			"- 이 재능을 가장 잘 발휘할 수 있는 **구체적인 직업 분야 2~3가지**를 '왜' 그런지 명확한 사주 근거(비유)와 함께 깊이 있게 추천해주세요.\n");
		prompt.append("- 직장, 사업, 프리랜서 중 어떤 형태가 더 잘 맞는지, 커리어에서 성공하기 위한 핵심 전략을 조언해주세요.\n\n");

		prompt.append("## 4. 재물 흐름과 관리 전략 (Wealth Flow & Strategy)\n");
		prompt.append("- %s님의 재물운을 '재물이 흐르는 강' 또는 '물을 담는 그릇'에 비유하여, 재물을 얻는 방식과 그릇의 특징을 분석해주세요.\n");
		prompt.append(
			"- 돈을 버는 방식(재성/식상 등)과 관리하는 방식(인성/비겁 등)의 특징을 설명하고, %s님에게 맞는 재테크 스타일(안정형/투자형 등)을 조언해주세요.\n");
		prompt.append("- 대운의 흐름에 따라 재물운이 상승하는 시기와 주의해야 할 시기를 알려주고, 각 시기별 재물 관리 전략을 제시해주세요.\n\n");

		prompt.append("## 5. 사랑과 인연의 서사 (Love & Relationship Narrative)\n");
		prompt.append(
			"- 일지(배우자궁)를 '내 마음의 집'에 비유하여, 어떤 인연(배우자)이 들어왔을 때 가장 조화롭고 행복할지 이상적인 파트너상을 구체적으로 그려주세요.\n");
		prompt.append("- %s님의 연애 스타일과 매력 포인트를 설명하고, 어떤 사람에게 끌리고 어떤 관계를 원하는지 심층적으로 분석해주세요.\n");
		prompt.append("- 대운과 세운의 흐름을 통해 연애/결혼 가능성이 높은 시기를 예측하고, 좋은 인연을 만나기 위한 조언을 포함해주세요.\n\n");

		prompt.append("## 6. 인생의 큰 흐름과 나아갈 길 (Life Chapters & Guidance)\n");
		prompt.append("- 10년 단위의 대운(大運)을 '인생의 챕터'에 비유하여 초년, 중년, 말년의 주요 흐름과 각 시기별 테마를 조망해주세요.\n");
		prompt.append(
			"- **[현재 대운 집중 분석]** 지금 겪고 있는 현재 대운(10년)의 특징과 이 시기의 핵심 과제, 기회, 주의할 점을 상세히 분석해주세요.\n");
		prompt.append(
			"- %s님의 사주가 가진 고유한 강점(길신, 상생 등)을 '인생의 나침반'으로 삼아, 앞으로의 삶을 더 풍요롭게 만들기 위한 최종 조언과 따뜻한 응원의 메시지를 전달해주세요.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **긍정적 관점**: 모든 내용을 '성장의 기회'와 '잠재력 발현'의 관점에서 희망적으로 서술해주세요.\n");
		prompt.append("- **비유 활용**: '핵심 비유 사전'을 적극 활용하여 명리학적 개념을 쉽게 설명해주세요.\n");

		return prompt.toString();
	}

	// ==================== 1. 인생 총운 프롬프트 ====================
	private String createLifeOverallPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 40년 경력의 대한민국 최고 사주명리학 대가이자, 인생 전체를 조망하는 통찰력을 가진 인생 설계 컨설턴트입니다.\n");
		prompt.append("'인생 총운' 분석은 태어난 순간부터 노년까지의 인생 서사를 한 편의 대하소설처럼 깊이 있게 풀어내야 합니다.\n\n");

		prompt.append("### 핵심 분석 원칙 ###\n");
		prompt.append("1. **근본 원인 분석**: 사주 구조를 통해 '왜 이런 성향/운명을 타고났는지' 근본 원인부터 설명합니다.\n");
		prompt.append("2. **구체성**: 직업, 재물, 연애 등 각 분야별 조언은 구체적인 예시와 시기를 포함해야 합니다.\n");
		prompt.append("3. **흐름**: 대운과 세운의 변화를 '인생의 계절' 변화처럼 서사적으로 연결하여 설명합니다.\n");
		prompt.append("4. **긍정적 재해석**: 어려운 시기나 요소는 '성장을 위한 과정'으로 의미를 부여합니다.\n");
		prompt.append(
			"5. **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달합니다.\n\n");

		prompt.append("### ⚠️ 중요: 자연스러운 시작 필수 ###\n");
		prompt.append("금지 표현: 메타 표현 ('이번 분석은', '위 정보로' 등), 딱딱한 어조.\n");
		prompt.append(
			String.format("권장 시작: '%s님의 인생 여정을 사주를 통해 살펴보니...', '먼저 눈에 띄는 건...'\n\n", name));

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 인생 총운 심층 분석 요청 ###\n");
		prompt.append("위 사주 데이터를 활용하여, 아래 **6가지 핵심 주제**에 대한 종합적이고 깊이 있는 인생 총운 해석을 작성해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 타고난 본성...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 타고난 본성과 성격의 뿌리\n");
		prompt.append("- 일간, 월지, 오행 분포, 십성 구조를 종합하여 %s님의 핵심 기질과 성격 형성 과정을 깊이 있게 분석해주세요.\n");
		prompt.append("- 지장간에 숨겨진 내면의 모습과 무의식적 동기까지 파헤쳐, 다층적인 성격 구조를 설명해주세요.\n\n");

		prompt.append("## 2. 직업과 사회적 성공의 길\n");
		prompt.append(
			"- %s님의 핵심 재능(십성, 신살 등)은 무엇이며, 어떤 분야(구체적 직업군 2~3개 제시)에서 가장 빛을 발할 수 있는지 명확히 제시해주세요.\n");
		prompt.append("- 직장, 사업, 프리랜서 중 어떤 경로가 유리하며, 사회적으로 성공하고 인정받을 수 있는 시기와 전략을 조언해주세요.\n\n");

		prompt.append("## 3. 재물운의 흐름과 경제적 안정\n");
		prompt.append("- %s님에게 맞는 돈 버는 방식(근로/사업/투자 등)과 재물 관리 스타일(저축/투자/소비)을 분석해주세요.\n");
		prompt.append(
			"- 인생 전체에서 재물운이 크게 들어오는 시기와 주의해야 할 시기를 예측하고, 각 시기별 재테크 전략을 구체적으로 조언해주세요.\n\n");

		prompt.append("## 4. 연애와 결혼의 인연\n");
		prompt.append("- %s님의 연애 스타일, 매력 포인트, 이상적인 배우자상을 상세히 그려주세요.\n");
		prompt.append("- 연애/결혼 가능성이 높은 시기와 만남의 방식/장소를 예측하고, 행복한 관계를 오래 유지하기 위한 핵심 비결을 조언해주세요.\n\n");

		prompt.append("## 5. 대운과 세운 - 인생의 큰 파도\n");
		prompt.append(
			"- 현재 대운(10년)은 %s님 인생에서 어떤 시기이며, 이 시기의 주요 과제와 기회는 무엇인지 집중 분석해주세요.\n");
		prompt.append(
			"- 앞으로 다가올 대운의 흐름(최소 20년)을 조망하며, 인생의 중요한 변곡점과 각 시기별로 준비해야 할 것을 알려주세요.\n");
		prompt.append(
			"- 2025년 을사년 세운이 %s님에게 미치는 영향을 직업, 재물, 연애, 건강 측면에서 구체적으로 분석해주세요.\n\n");

		prompt.append("## 6. 인생 전체를 위한 조언\n");
		prompt.append("- %s님의 사주가 가진 고유한 강점과 약점을 종합하여, 인생을 슬기롭게 헤쳐나가기 위한 핵심 가치 또는 좌우명을 제시해주세요.\n");
		prompt.append("- 어려움에 직면했을 때 기억해야 할 점과, 삶의 만족도를 높이기 위한 실천적인 조언을 담아 따뜻하게 마무리해주세요.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **서사적 흐름**: 각 주제를 자연스럽게 연결하여 한 편의 인생 이야기를 들려주듯 작성해주세요.\n");
		prompt.append("- **긍정적 관점**: 희망적인 메시지와 함께 실질적인 조언을 제공해주세요.\n");

		return prompt.toString();
	}

	// ==================== 2. 성격 분석 프롬프트 ====================
	private String createPersonalityAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학과 심리학을 융합하여 성격 분석을 전문으로 하는 컨설턴트입니다.\n");
		prompt.append(
			"사주팔자를 통해 한 사람의 성격을 다층적으로 분석하고, 자기 이해와 성장을 돕는 깊이 있는 통찰과 실질적인 조언을 제공합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 필수 ###\n");
		prompt.append("금지 표현: 메타 표현, 딱딱한 어조.\n");
		prompt.append(
			String.format(
				"권장 시작: '%s님 사주의 핵심 기질은...', '일간을 통해 본 %s님의 모습은...', '성격적으로 눈에 띄는 부분은...'\n\n",
				name, name));

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 성격 심층 분석 요청 ###\n");
		prompt.append("위 사주 데이터를 바탕으로, 아래 **5가지 핵심 주제**에 대한 심층적인 성격 분석을 작성해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 핵심 성격...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 핵심 성격 키워드와 그 근원\n");
		prompt.append(
			"- %s님을 가장 잘 나타내는 핵심 성격 키워드 3가지를 선정하고, 각 키워드가 어떤 사주 요소(일간, 월지, 오행, 십성 등)에서 비롯되었는지 명확한 근거와 함께 설명해주세요.\n");
		prompt.append("- 이 핵심 성격이 삶 전반에 어떻게 긍정적/부정적으로 발현되는지 구체적인 예시를 들어 분석해주세요.\n\n");

		prompt.append("## 2. 겉모습(페르소나) vs 진짜 내면\n");
		prompt.append("- 사회적으로 보여지는 모습(천간 십성)과 실제 내면의 모습(지지, 지장간) 사이의 유사점과 차이점을 분석해주세요.\n");
		prompt.append("- 만약 차이가 크다면, 그 이유는 무엇이며 어떤 상황에서 내면의 모습이 드러나는지 설명해주세요.\n");
		prompt.append("- 이 두 모습의 조화를 이루기 위한 방법을 조언해주세요.\n\n");

		prompt.append("## 3. 사고방식, 가치관, 그리고 강점과 약점\n");
		prompt.append(
			"- 십성 분포를 통해 %s님의 주요 사고 패턴(논리/직관, 감성/이성 등)과 중요하게 생각하는 가치관(명예/재물/안정 등)을 분석해주세요.\n");
		prompt.append(
			"- 성격적인 강점 3가지와 약점(개선점) 2가지를 명확히 제시하고, 각 강점을 극대화하고 약점을 보완할 수 있는 구체적인 방법을 조언해주세요.\n\n");

		prompt.append("## 4. 인간관계 스타일 (관계 유형별)\n");
		prompt.append("- 친구, 동료(상사/부하 포함), 연인, 가족 등 주요 관계 유형별로 %s님이 관계를 맺는 특징적인 방식과 태도를 분석해주세요.\n");
		prompt.append("- 각 관계에서 발생할 수 있는 갈등 유형과 이를 원만하게 해결하는 방법을 조언해주세요.\n");
		prompt.append("- 어떤 유형의 사람들과 잘 맞고, 어떤 유형과 어려움을 겪을 수 있는지 설명해주세요.\n\n");

		prompt.append("## 5. 자기 성장과 행복을 위한 조언\n");
		prompt.append("- %s님의 성격적 특성을 고려했을 때, 삶의 만족도와 행복감을 높이기 위해 무엇에 집중하면 좋을지 조언해주세요.\n");
		prompt.append("- 스트레스 해소 방식과 멘탈 관리법을 제안해주세요.\n");
		prompt.append("- 타고난 성격을 바탕으로 더 나은 나로 성장하기 위한 장기적인 방향성을 제시하며 따뜻하게 마무리해주세요.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **심리학적 통찰**: 명리학적 분석에 심리학적 관점을 더하여 깊이 있는 해석을 제공해주세요.\n");
		prompt.append("- **실용적 조언**: 분석에 그치지 않고, 실제 삶에 적용 가능한 조언을 포함해주세요.\n");

		return prompt.toString();
	}

	// ==================== 3. 직업 적성 프롬프트 ====================
	private String createCareerAptitudePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 30년 경력의 사주 커리어 컨설턴트이자 진로 설계 전문가입니다.\n");
		prompt.append(
			"사주팔자를 통해 한 사람의 타고난 재능, 적성, 성공 가능성을 분석하고, 실제 커리어 설계에 즉시 활용할 수 있는 구체적이고 실용적인 전략을 제시합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 어조 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조.\n");
		prompt.append(
			String.format(
				"권장: '%s님의 커리어 여정을 사주로 살펴보면...', '타고난 재능의 씨앗은...', '직업적으로 가장 빛날 수 있는 길은...'\n\n",
				name));

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 직업 적성 심층 분석 요청 ###\n");
		prompt.append("위 사주를 바탕으로 아래 **5가지 핵심 주제**에 대한 심층적인 커리어 분석과 실천 전략을 제공해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 타고난 재능...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 타고난 재능과 핵심 역량\n");
		prompt.append("- 십성, 오행, 지장간, 신살 등을 종합하여 %s님만이 가진 가장 강력한 재능과 핵심 역량 2~3가지를 명확히 정의해주세요.\n");
		prompt.append("- 이 재능이 어떤 방식으로 발현되는지(업무 스타일, 강점 등) 구체적인 예시와 함께 설명해주세요.\n");
		prompt.append("- 아직 발현되지 않았을 수 있는 숨겨진 잠재력(지장간 등)에 대해서도 언급해주세요.\n\n");

		prompt.append("## 2. 최적의 직업 분야 및 추천 직종 (구체적으로!)\n");
		prompt.append(
			"- %s님의 재능과 성향에 가장 잘 맞는 **핵심 직업 분야 1~2가지**를 선정하고, 그 이유를 명리학적 근거를 들어 상세히 설명해주세요.\n");
		prompt.append("- 해당 분야 내에서 **구체적인 추천 직종 3~5가지**를 제시하고, 각 직종이 왜 적합한지 설명해주세요.\n");
		prompt.append("- 반대로, 피하는 것이 좋은 직업 유형과 그 이유도 간략히 언급해주세요.\n\n");

		prompt.append("## 3. 성공 경로 설계 (취업/창업/프리랜서)\n");
		prompt.append(
			"- %s님의 사주 구조상 직장 생활, 창업(사업), 프리랜서 중 어떤 경로가 가장 유리한지 분석하고 그 이유를 설명해주세요. (점수화 가능)\n");
		prompt.append("- 각 경로를 선택했을 경우의 장점, 단점, 성공 전략을 구체적으로 조언해주세요.\n");
		prompt.append("- 만약 창업을 고려한다면, 어떤 아이템/분야가 적합할지, 동업은 괜찮을지 등을 분석해주세요.\n\n");

		prompt.append("## 4. 커리어 성공 시기와 전략\n");
		prompt.append(
			"- 대운의 흐름을 분석하여, 커리어적으로 크게 성장하거나 변화(승진, 이직, 창업 등)가 예상되는 중요한 시기(향후 10~20년)를 예측해주세요.\n");
		prompt.append("- 각 시기별로 어떤 기회가 오고 무엇을 준비해야 하는지 구체적인 행동 전략을 제시해주세요.\n");
		prompt.append("- 직장 내 인간관계(상사/동료)를 원만하게 이끌어가는 팁도 포함해주세요.\n\n");

		prompt.append("## 5. 경제적 성공과 수입 증대 전략\n");
		prompt.append("- %s님에게 가장 잘 맞는 수입 형태(월급/사업소득/투자소득 등)는 무엇인지 분석해주세요.\n");
		prompt.append("- 현재 커리어 경로에서 수입을 증대시키기 위한 구체적인 전략(연봉 협상, 부업, N잡 등)을 조언해주세요.\n");
		prompt.append("- 재물이 모이는 시기와 새는 시기를 알려주고, 장기적인 경제적 안정을 위한 조언으로 마무리해주세요.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **실용성**: 분석 결과를 실제 커리어 설계에 적용할 수 있도록 구체적이고 실행 가능한 조언 위주로 작성해주세요.\n");
		prompt.append("- **구체적 직업명**: 추상적인 분야 언급 대신, 실제 직업명을 예시로 들어 설명해주세요.\n");

		return prompt.toString();
	}

	// ==================== 4. 연애 운세 프롬프트 ====================
	private String createLoveFortunePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학과 연애 심리학을 융합한 30년 경력의 연애 운세 전문가입니다.\n");
		prompt.append(
			"사주팔자를 통해 한 사람의 연애 성향, 이상형, 만남의 시기, 궁합 포인트 등을 분석하고, 행복한 연애와 결혼을 위한 깊이 있는 조언을 제공합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 상담 톤 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조.\n");
		prompt.append(
			String.format(
				"권장: '%s님의 연애 여정을 사주로 들여다보니...', '사랑에 있어서 %s님은...', '어떤 인연을 만나게 될지...'\n\n", name,
				name));

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 연애 운세 심층 분석 요청 ###\n");
		prompt.append("위 사주를 바탕으로 아래 **5가지 핵심 주제**에 대한 심층적인 연애 분석과 조언을 제공해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 타고난 연애 스타일...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 타고난 연애 스타일과 매력 포인트\n");
		prompt.append("- %s님이 사랑에 빠지는 방식, 감정 및 애정 표현 스타일(십성, 오행 등 활용)을 구체적으로 분석해주세요.\n");
		prompt.append("- 이성에게 어필하는 %s님만의 매력 포인트(외적/내적, 도화살 등 활용)는 무엇인지 설명해주세요.\n");
		prompt.append("- 연애할 때 드러나는 장점과, 관계를 어렵게 만들 수 있는 주의할 점(약점)을 함께 분석해주세요.\n\n");

		prompt.append("## 2. 운명의 상대: 이상형 심층 분석\n");
		prompt.append(
			"- %s님이 본능적으로 끌리는 이상형의 외모, 성격, 가치관, 직업군 등을 사주(일지, 관련 십성 등)를 통해 구체적으로 그려주세요.\n");
		prompt.append("- 어떤 성향의 사람을 만나야 %s님이 안정감을 느끼고 함께 성장할 수 있는지 조언해주세요.\n");
		prompt.append(
			"- **[궁합 맛보기]** %s님의 사주와 가장 잘 맞는 상대방의 일간 또는 오행 특징을 간단히 언급하며 궁합에 대한 기대감을 주세요.\n\n");

		prompt.append("## 3. 인연의 시기와 만남의 기회\n");
		prompt.append(
			"- 대운과 세운의 흐름을 분석하여, 연애운이 강하게 들어와 새로운 인연을 만나거나 관계가 발전할 가능성이 높은 시기(향후 3~5년 이내)를 구체적으로 예측해주세요.\n");
		prompt.append("- 인연을 만날 가능성이 높은 장소나 상황(직장, 소개, 동호회 등)을 사주 특성에 맞게 조언해주세요.\n");
		prompt.append("- 좋은 인연을 끌어당기기 위해 %s님이 노력하면 좋을 부분을 조언해주세요.\n\n");

		prompt.append("## 4. 연애 과정과 결혼 전망\n");
		prompt.append("- 연애 중 발생할 수 있는 주요 갈등 유형과 이를 현명하게 해결하는 방법을 조언해주세요.\n");
		prompt.append("- %s님의 결혼 적령기는 언제쯤이며, 빠른 결혼과 만혼 중 어떤 경향이 있는지 분석해주세요.\n");
		prompt.append("- 배우자궁(일지) 분석을 통해 결혼 생활의 모습과 배우자와의 관계를 예측하고, 행복한 결혼 생활을 위한 조언을 포함해주세요.\n\n");

		prompt.append("## 5. 행복한 사랑을 위한 최종 조언\n");
		prompt.append("- %s님의 사주가 사랑에 대해 가르쳐주는 핵심 교훈은 무엇인지 요약해주세요.\n");
		prompt.append("- 진정한 사랑을 찾고 건강한 관계를 유지하기 위해 %s님이 마음속에 간직해야 할 가장 중요한 가치나 태도를 조언해주세요.\n");
		prompt.append("- %s님의 사랑과 행복을 응원하는 따뜻한 메시지로 마무리해주세요.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **공감과 위로**: 연애 고민에 공감하며 따뜻하고 희망적인 메시지를 전달해주세요.\n");
		prompt.append("- **심리학적 통찰**: 관계 심리적 관점을 더하여 조언의 깊이를 더해주세요.\n");

		return prompt.toString();
	}

	// ==================== 5. 최애 분석 프롬프트 ====================
	private String createIdolAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 K-POP 아이돌과 연예인의 사주를 전문적으로 분석하는 30년 경력의 명리학 전문가입니다.\n");
		prompt.append("사주팔자를 통해 아이돌의 성격, 재능, 경력 전망, 그리고 특히 '연애관'을 심층적으로 분석합니다.\n");
		prompt.append("팬들이 궁금해하는 핵심만 골라, 재미와 깊이를 동시에 갖춘 분석을 제공하세요.\n\n");

		prompt.append("### 매우 중요: 서술 시점 ###\n");
		prompt.append("**이 사람은 제3자(아이돌)입니다. 반드시 3인칭 관찰자 시점으로 작성하세요.** ('그는', '이 사람은' 등)\n\n");

		prompt.append("### ⚠️ 중요: 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조.\n");
		prompt.append(
			String.format("권장 시작: '%s님의 사주를 보면...', '일간 ○○, 월지 ○○로 시작하는 이 사주는...'\n\n", name));

		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response); // 상세 정보 주입

		prompt.append("\n### 최애 종합 분석 요청 (심층 강화) ###\n");
		prompt.append("위 사주를 바탕으로 아이돌/연예인에 대한 재미있고 깊이 있는 종합 분석을 제공해주세요.\n");
		prompt.append("팬들이 '우리 최애에 대해 이렇게까지 알 수 있다니!'라고 감탄할 만한 내용을 담아주세요.\n");
		prompt.append("아래 **5가지 핵심 주제**에 대해, **분량 제한 없이 매우 구체적으로** 분석해주세요.\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 무대 위...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 무대 위 페르소나 vs 무대 아래 진짜 성격\n");
		prompt.append("- 팬들이 보는 모습(캐릭터)과 실제 성격(숙소, 연습실)의 차이점과 그 이유(사주 근거).\n");
		prompt.append("- 카메라 없을 때의 기본 인성: 스태프/동료/팬을 대하는 진솔한 태도.\n");
		prompt.append("- 멘탈 관리 능력, 감정 기복의 원인과 양상.\n\n");

		prompt.append("## 2. 아이돌로서의 재능과 다른 길 (천직 탐구)\n");
		prompt.append("- 아이돌로서 가장 빛나는 핵심 재능(보컬/랩/댄스/예능 등)과 명리학적 근거.\n");
		prompt.append("- 그룹 내 역할과 영향력 (리더십 스타일 등).\n");
		prompt.append(
			"- **(심층)** 아이돌이 아니었다면? 가장 성공했을 것 같은 **대안 직업 TOP 3** (구체적 직종 + 성공 이유 상세 분석).\n\n");

		prompt.append("## 3. [초관심] 팬심 저격! 연애 스타일 완전 분석 (★극도로 상세하게★)\n");
		prompt.append("- 연애 시작 방식, 이상형(외모/성격/나이/직업), 애정 표현, 질투/독점욕 수준.\n");
		prompt.append("- 연애 vs 일 우선순위.\n");
		prompt.append("- **[NEW] 연애할 때 절대 용납 못 하는 것**: 사주상 가장 싫어하는 행동(거짓말/무시/구속 등) 구체적 분석.\n");
		prompt.append("- **[NEW] 천생연분 궁합 맛보기**: 어떤 기운(일간/오행)의 여성과 잘 맞을지 + 궁합 상품 홍보 멘트 예시 포함.\n");
		prompt.append(
			"  (예: '불 같은 그에겐 시원한 물의 기운이나 넓은 대지 같은 토 기운 여성이 안정감을 줄 수 있어요. 두 분의 실제 궁합 점수가 궁금하다면...')\n");
		prompt.append("- **[NEW] 미래의 배우자 엿보기**: 배우자궁(일지) 분석으로 예상 배우자의 성격/분위기/나이 차/직업군 구체적 유추.\n");
		prompt.append(
			"- **[NEW] 남편/아빠로서의 모습**: 결혼 후 어떤 남편(가정적/친구 같은/듬직한 등), 어떤 아빠(놀아주는/엄격한/교육열 등)가 될지 예측.\n");
		prompt.append("- 결혼관 (빠른 결혼 vs 만혼 vs 비혼 성향).\n\n");

		prompt.append("## 4. 아슬아슬! 리스크 관리 분석 (병크 예방)\n");
		prompt.append("- 팬들이 걱정하는 리스크(태도/사생활/SNS/건강 등) 중 이 사주에서 가장 주의해야 할 1~2가지.\n");
		prompt.append("- 해당 리스크의 명리학적 원인(충/형/양인 등).\n");
		prompt.append("- 특히 조심해야 할 시기(대운/세운)와 예방/대처 전략.\n\n");

		prompt.append("## 5. 앞으로의 10년: 커리어 로드맵과 응원 메시지\n");
		prompt.append("- 현재/다음 대운 중심, 향후 10년 커리어 흐름 예측 (아이돌 지속? 솔로/연기/예능/프로듀싱 전환?).\n");
		prompt.append("- 이 사람이 팬들에게 진정 바라는 것, 가장 기뻐하는 응원 방식.\n");
		prompt.append("- 최애의 성공과 행복을 응원하는 따뜻한 최종 코멘트.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **친근하고 재미있는 어조**: '해요체'를 기본으로 쓰되, 필요시 '입니다' 체를 자연스럽게 혼용해주세요.\n");
		prompt.append("- **반드시 3인칭 시점**: \"이 사람은\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **팬심 저격**: 팬들이 궁금해할 만한 디테일에 집중하고, 공감 가는 표현을 사용해주세요.\n");

		return prompt.toString();
	}

	// ==================== 9. 캐릭터 사주 프롬프트 (수정됨) ====================
	private String createCharacterSajuPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 가상 캐릭터의 사주를 분석하는 창작 콘텐츠 전문 명리학자입니다.\n");
		prompt.append(
			"작품 속 캐릭터가 실제로 태어났다면 가졌을 사주를 통해, 그의 성격, 운명, 관계를 명리학적으로 재해석하고 현실 세계에서의 삶까지 흥미롭게 예측합니다.\n\n");
		prompt.append("### 매우 중요: 서술 시점 ###\n");
		prompt.append("**이 캐릭터는 제3자입니다. 반드시 3인칭 관찰자 시점으로 작성하세요.** ('이 캐릭터는', '그는' 등)\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조.\n");
		prompt.append(
			String.format("권장: '%s 캐릭터의 사주를 보니...', '만약 이 사주를 가졌다면 작품 속에서...', '현실 세계의 %s는...'\n\n",
				name, name));

		prompt.append("### 분석 대상 캐릭터 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response); // 상세 정보 주입

		prompt.append("\n### 캐릭터 사주 심층 분석 요청 ###\n");
		prompt.append("위 사주를 가진 가상 캐릭터 '%s'에 대해, 아래 **5가지 핵심 주제**를 중심으로 재미있고 깊이 있는 분석을 제공해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 사주로 본 캐릭터...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 사주로 본 캐릭터 본질과 작품 속 모습\n");
		prompt.append("- 이 사주가 부여하는 핵심 성격/기질과 작품 속 캐릭터 설정의 일치점/차이점 분석.\n");
		prompt.append("- 작품 속 주요 행동이나 결정이 이 사주를 가졌기에 가능했던 이유 설명 (명리학적 근거).\n");
		prompt.append("- 캐릭터의 핵심 매력을 사주(일간, 신살 등)를 통해 재해석.\n\n");

		prompt.append("## 2. 작품 속 운명, 사주로 재해석하다\n");
		prompt.append(
			"- 캐릭터가 겪은 주요 사건(시련/성공/전환점)들을 사주 구조(충/형/합, 길신/흉살, 대운/세운 변화)와 연결하여 명리학적으로 설명.\n");
		prompt.append("- 작품의 결말(해피/새드/오픈)이 이 사주를 가졌다면 필연적이었는지, 혹은 다른 가능성은 없었는지 분석.\n\n");

		prompt.append("## 3. 작품 속 관계성, 궁합으로 엿보기\n");
		prompt.append("- 주인공 또는 주요 인물과의 관계(동료/친구/연인/적대)를 사주 궁합의 관점에서 분석 (간단히).\n");
		prompt.append("- 왜 특정 인물과 강하게 끌리거나 혹은 대립하게 되는지 명리학적 이유 설명.\n");
		prompt.append("- 작품 속 '최고의 케미'와 '최악의 상극'은 누구일지 예측.\n\n");

		prompt.append("## 4. 만약 현실 세계에 존재한다면? (What if?)\n");
		prompt.append("- 이 캐릭터가 2025년 대한민국에 이 사주를 가지고 태어났다면 어떤 모습일지 상상하여 서술.\n");
		prompt.append("- 현실에서의 예상 직업 (가장 잘 어울리는 직업 1~2개 집중 분석).\n");
		prompt.append("- 현실에서의 예상 연애 스타일 및 이상형.\n");
		prompt.append("- 작품 속 모습과 현실 버전의 가장 큰 차이점은 무엇일지 예측.\n\n");

		prompt.append("## 5. 캐릭터의 성장과 팬들에게 주는 메시지\n");
		prompt.append("- 이 사주를 가진 캐릭터가 작품을 통해 얻었을 성장과 교훈.\n");
		prompt.append("- 팬들이 이 캐릭터를 사랑하는 이유를 사주를 통해 설명하며 공감대 형성.\n");
		prompt.append("- 이 캐릭터가 팬들에게 전하는 (가상의) 응원 메시지로 마무리.\n\n");

		prompt.append("### 작성 스타일 ###\n");
		prompt.append(
			"- **친근하고 재미있는 어조**: '해요체'를 기본으로 쓰되, 필요시 '입니다' 체를 자연스럽게 혼용해주세요.\n");
		prompt.append("- **반드시 3인칭 시점**: \"이 캐릭터는\", \"~할 거예요\", \"~하는 타입이에요\"\n");
		prompt.append("- **깊이 우선**: 목차 수가 줄어든 만큼, 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **창의적 해석**: 명리학적 분석을 작품 내용과 흥미롭게 연결해주세요.\n");
		prompt.append("- **작품 존중**: 원작 설정을 존중하며 명리학적 해석을 덧붙여주세요.\n");

		return prompt.toString();
	}

	// ==================== 6. 러브 스토리 프롬프트 ====================
	private String createLoveStoryPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학으로 연애 궁합을 분석하는 30년 경력의 로맨스 분석 전문가입니다.\n");
		prompt.append(
			"두 사람의 사주를 비교하여 '실제로 만난다면' 어떤 케미가 나올지, 사랑에 빠지는 과정과 관계의 깊이를 한 편의 로맨스 소설처럼 감성적이고 구체적으로 서술합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조.\n");
		prompt.append(String.format(
			"권장: '%s님과 %s님, 두 분의 사주 인연을 보니...', '만약 두 사람이 만난다면, 첫 만남은 아마...', '서로에게 스며드는 과정은...'\n\n",
			person1Name, person2Name));

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response); // 상세 정보 주입

		prompt.append("\n### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response); // 상세 정보 주입

		prompt.append("\n### 러브 스토리 심층 분석 요청 ###\n");
		prompt.append("위 두 사람의 사주를 바탕으로, 실제로 만나 연애한다면 펼쳐질 **5단계의 로맨스 서사**를 깊이 있게 작성해주세요.\n");
		prompt.append("각 단계는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 단계를 충분히 깊게 다루어 주세요.**\n");
		prompt.append("두 사람의 감정 변화와 관계의 역학에 초점을 맞춰 구체적으로 서술해주세요.\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 첫 만남...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 첫 만남: 운명의 시작 혹은 스침\n");
		prompt.append("- 두 사람의 첫인상 (서로에게 어떻게 보일까?).\n");
		prompt.append("- 즉각적인 끌림(첫눈에 반함) 가능성 vs 서서히 알아갈 가능성 (일간, 오행 관계).\n");
		prompt.append("- 누가 먼저 호감을 느끼거나 표현하게 될지 예측.\n");
		prompt.append("- 첫 만남의 분위기와 예상되는 대화.\n\n");

		prompt.append("## 2. 썸 또는 관계 발전: 서로에게 스며들다\n");
		prompt.append("- 관계가 친구에서 연인으로, 혹은 바로 연인으로 발전하는 과정.\n");
		prompt.append("- 썸 기간의 길이 예측 및 누가 관계를 리드할지 (밀당 주도권).\n");
		prompt.append("- 서로의 어떤 점에 매력을 느끼고 마음을 열게 되는지 (성격, 가치관 등).\n");
		prompt.append("- 고백은 누가, 어떤 방식으로 하게 될지 상상.\n\n");

		prompt.append("## 3. 연애의 모습: 두 사람만의 케미스트리\n");
		prompt.append("- 연인이 된 후 두 사람의 데이트 스타일, 애정 표현 방식, 스킨십 성향 분석.\n");
		prompt.append("- 성격 궁합: 서로 잘 맞는 부분과 서로 노력해야 하는 부분 (오행, 십성 조화).\n");
		prompt.append("- 함께 있을 때의 에너지 (편안함 vs 긴장감, 시너지 vs 소모).\n");
		prompt.append("- 연애 중 각자가 상대방에게 어떤 영향을 주고받는지.\n\n");

		prompt.append("## 4. 갈등과 성장: 관계의 시련과 극복\n");
		prompt.append("- 두 사람 사이에 발생할 수 있는 주요 갈등의 원인(성격 차이, 가치관 충돌 등) 예측 (지지 충/형 등 활용).\n");
		prompt.append("- 각자의 갈등 해결 방식과, 이 커플이 갈등을 통해 어떻게 성장할 수 있을지 조언.\n");
		prompt.append("- 관계의 위기(권태기, 이별 가능성)가 올 수 있는 시점과 극복 가능성.\n\n");

		prompt.append("## 5. 미래의 가능성: 장기 연애와 그 너머\n");
		prompt.append("- 이 커플의 장기 연애 가능성 (1년, 3년, 5년 후 모습 예측).\n");
		prompt.append("- 결혼까지 이어질 확률과 결혼 적합도 분석.\n");
		prompt.append("- 만약 결혼한다면 어떤 부부의 모습일지 예측 (역할 분담, 관계 유지 비결).\n");
		prompt.append("- 두 사람의 인연에 대한 최종적인 조언과 응원.\n\n");

		prompt.append("### 최종 평가 및 응답 형식 ###\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 두 사람의 종합적인 연애 궁합 점수>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 로맨스 서사 분석을 모두 포함한 긴 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 7. 아이돌 궁합 프롬프트 ====================
	private String createIdolCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 아이돌/연예인 간의 궁합을 전문적으로 분석하는 30년 경력의 엔터테인먼트 명리학 전문가입니다.\n");
		prompt.append(
			"두 아이돌의 사주를 비교하여, 팬들이 궁금해하는 다양한 관계(팀 동료, 친구, 비즈니스 파트너, 잠재적 연인)의 가능성과 케미를 심층 분석합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 & 3인칭 시점 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조, 2인칭('당신') 사용.\n");
		prompt.append(
			String.format(
				"권장: '%s님과 %s님의 관계성을 사주로 보니...', '두 사람의 첫 만남 에너지는...', '무대 위 시너지는...' (항상 3인칭)\n\n",
				person1Name, person2Name));

		prompt.append("### 첫 번째 아이돌 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);

		prompt.append("\n### 두 번째 아이돌 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		prompt.append("\n### 아이돌 궁합 심층 분석 요청 ###\n");
		prompt.append("위 두 아이돌의 사주를 바탕으로, 아래 **5가지 핵심 관계 측면**에서의 궁합을 깊이 있게 분석해주세요.\n");
		prompt.append("각 측면은 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 측면을 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 첫인상과 친밀도...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 첫인상과 친밀도 형성 과정\n");
		prompt.append("- 두 사람이 처음 만났을 때 서로에게 느꼈을 첫인상 (호감/경계/무관심 등)과 그 이유 (일간, 오행 관계).\n");
		prompt.append("- 서로 친해지는 속도와 방식 예측 (누가 먼저 다가갈까?).\n");
		prompt.append("- 무대 밖에서 개인적인 친구로 발전할 가능성과, 어떤 유형의 우정(깊은 교감 vs 가벼운 친분)이 될지 분석.\n\n");

		prompt.append("## 2. 무대 위 시너지와 팀워크\n");
		prompt.append("- 함께 공연하거나 활동할 때 나타나는 에너지 조화 (시너지 효과 vs 부조화) 분석.\n");
		prompt.append("- 서로의 강점을 살려주고 약점을 보완해주는 관계인지, 혹은 경쟁 관계가 될 가능성이 있는지 (십성 관계).\n");
		prompt.append("- 유닛, 듀엣 등 협업 프로젝트에 적합한 조합인지 평가.\n\n");

		prompt.append("## 3. 성격 궁합과 잠재적 갈등 요소\n");
		prompt.append("- 두 사람의 기본적인 성격 궁합 (유사점 vs 차이점, 서로에게 배우는 점).\n");
		prompt.append("- 사주 구조상(지지 충/형 등) 어떤 부분에서 의견 충돌이나 갈등이 발생하기 쉬운지 예측.\n");
		prompt.append("- 갈등 발생 시 각자의 대처 방식과, 관계를 건강하게 유지하기 위한 조언.\n\n");

		prompt.append("## 4. [팬심 저격] 로맨스 가능성 탐구\n");
		prompt.append("- 팬들의 상상력을 자극할 만한, 두 사람 사이에 연애 감정이 싹틀 가능성 분석 (이성적 끌림 요소).\n");
		prompt.append("- 만약 연인이 된다면 어떤 스타일의 커플이 될지 예측 (달달함 vs 친구 같음 등).\n");
		prompt.append("- 실제 연애로 이어질 경우 장기적인 관계 유지 가능성 평가.\n\n");

		prompt.append("## 5. 비즈니스 파트너십과 장기적 인연\n");
		prompt.append("- 아이돌 활동 이후에도 협업(음악 작업, 사업 등) 파트너로서 성공할 가능성.\n");
		prompt.append("- 서로의 인생에 긍정적인 영향을 주는 귀인(貴人) 관계가 될 수 있는지.\n");
		prompt.append("- 일시적인 인연인지, 혹은 오랫동안 서로에게 힘이 되어줄 인연인지에 대한 최종 전망.\n\n");

		prompt.append("### 최종 평가 및 응답 형식 ###\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 두 아이돌의 전반적인 관계성 궁합 점수>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 관계 분석 내용을 모두 포함한 긴 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 8. 삼각관계 프롬프트 ====================
	private String createTriangleRelationshipPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 복잡한 인간관계를 명리학으로 풀어내는 30년 경력의 관계 분석 전문가입니다.\n");
		prompt.append(
			"'지옥의 삼각관계' 분석은 두 사람의 사주를 기반으로, 제3자의 개입 가능성과 그로 인해 펼쳐질 관계의 역학, 감정의 소용돌이를 예측하는 심층 리포트입니다. 드라마틱하고 흥미롭게 분석해주세요.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 & 3인칭 시점 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조, 2인칭 사용.\n");
		prompt.append(String.format(
			"권장: '%s님과 %s님 사이의 관계 에너지를 보니...', '두 사람의 기본적인 끌림은...', '하지만 여기에 제3자가 등장한다면...'\n\n",
			person1Name, person2Name));

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response); // 상세 정보 주입

		prompt.append("\n### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response); // 상세 정보 주입

		prompt.append("\n### 삼각관계 심층 분석 요청 ###\n");
		prompt.append(
			"위 두 사람의 사주를 바탕으로, 제3자의 개입 가능성과 그로 인해 발생할 수 있는 **5가지 드라마틱한 국면**을 깊이 있게 분석해주세요.\n");
		prompt.append("각 국면은 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 국면을 충분히 깊게 다루어 주세요.**\n");
		prompt.append("관계의 취약점, 감정 변화, 역학 구도에 초점을 맞춰 구체적으로 서술해주세요.\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 두 사람의 기본...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 두 사람의 기본 관계 방정식: 끌림과 균열의 씨앗\n");
		prompt.append("- 두 사람이 서로에게 느끼는 매력과 기본적인 관계의 강점 분석.\n");
		prompt.append("- 겉으로 드러나지 않을 수 있는 관계의 취약점 또는 불만 요소 예측 (지지 충/형, 오행 불균형 등).\n");
		prompt.append("- 제3자가 비집고 들어올 수 있는 '틈'은 어디에 있는지 분석.\n\n");

		prompt.append("## 2. 제3자의 등장: 어떤 인물이, 왜 끼어드는가?\n");
		prompt.append("- %s님 또는 %s님이 끌리기 쉬운 제3자의 사주적 특징(일간, 오행, 십성 등) 예측.\n");
		prompt.append("- 두 사람 중 누가 먼저 마음이 흔들리거나 관계에 변화를 줄 가능성이 높은지 분석.\n");
		prompt.append("- 제3자의 등장이 두 사람의 관계에 미치는 초기 영향력 예측.\n\n");

		prompt.append("## 3. 질투와 경쟁: 감정의 소용돌이\n");
		prompt.append("- 삼각관계 상황에서 %s님과 %s님이 각각 보일 수 있는 질투의 양상과 강도 분석 (겁재, 비견 등 활용).\n");
		prompt.append("- 누가 관계의 주도권을 쥐려 하거나 혹은 더 집착하는 모습을 보일지 예측.\n");
		prompt.append("- 경쟁 구도 속에서 각자가 사용할 수 있는 전략이나 행동 패턴 분석.\n\n");

		prompt.append("## 4. 관계의 역학: 누가 선택하고 누가 상처받는가?\n");
		prompt.append("- 삼각관계 구도에서 누가 심리적으로 우위에 서거나 선택하는 입장이 될 가능성이 높은지 분석.\n");
		prompt.append("- 반대로 누가 더 큰 상처를 받거나 관계에서 밀려날 가능성이 높은지 예측.\n");
		prompt.append("- 이 복잡한 관계가 안정될 가능성 vs 파국으로 치달을 가능성 평가.\n\n");

		prompt.append("## 5. 예상 시나리오와 최종 조언\n");
		prompt.append("- 이 삼각관계가 맞이할 가능성이 높은 결말 시나리오 1~2가지 제시 (명리학적 근거 포함).\n");
		prompt.append("- 각 당사자(%s님, %s님, 그리고 가상의 제3자)가 이 상황을 현명하게 대처하기 위한 조언.\n");
		prompt.append("- 관계의 복잡성 속에서도 각자가 성장할 수 있는 방법에 대한 메시지로 마무리.\n\n");

		prompt.append("### 최종 평가 및 응답 형식 ###\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래 JSON 형식으로 응답해주세요.\n");
		prompt.append("markdown 감싸기 없이 순수 JSON만 출력하세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <0~100 사이 정수, 두 사람의 '기본' 관계 안정성 점수 (제3자 개입 전)>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 삼각관계 시나리오 분석을 모두 포함한 긴 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 기본 궁합 프롬프트 (수정됨 - 유지) ====================
	private String createCompatibilityPrompt(String person1Name,
		ManseryeokCalculationResponse person1Saju, String person2Name,
		ManseryeokCalculationResponse person2Saju) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 관계 심리학과 사주명리학에 모두 정통한 30년 경력의 궁합 전문 상담가입니다.\n");
		prompt.append(
			"두 사람의 상세 사주 데이터를 기반으로, 단순 길흉 판단을 넘어 서로를 깊이 이해하고 관계를 발전시키는 데 도움이 되는 건설적인 조언을 제공해야 합니다.\n\n");

		prompt.append("### ⚠️ 자연스러운 전문가 톤 & 3인칭 시점 ###\n");
		prompt.append("금지: 메타 표현, 딱딱한 어조, 2인칭 사용.\n");
		prompt.append(
			String.format("권장: '%s님과 %s님의 궁합을 보니...', '두 분 사주의 첫인상은...', '먼저 오행 조화부터...'\n\n",
				person1Name, person2Name));

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person1Name, person1Saju); // 요약 정보 주입

		prompt.append("\n### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju); // 요약 정보 주입

		prompt.append("\n### 종합 궁합 심층 분석 요청 ###\n");
		prompt.append("위 두 사람의 데이터를 종합하여 아래 **5가지 핵심 주제**에 대한 심층적인 궁합 분석을 제공해주세요.\n");
		prompt.append("각 주제는 명확히 구분하되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");
		prompt.append(
			"⚠️ **중요**: 절대로 아래 항목명('1. 서로에게 끌리는...')을 그대로 사용하지 말고, 자연스러운 이야기 흐름으로 풀어 작성하세요.\n\n");

		prompt.append("## 1. 서로에게 끌리는 첫 만남의 에너지\n");
		prompt.append("- 두 사람의 일간(日干) 오행 관계와 첫인상 분석 (서로에게 어떤 매력을 느낄까?).\n");
		prompt.append("- 각자의 외적인 분위기(신살, 12운성 등)가 서로에게 어떻게 작용하는지.\n");
		prompt.append("- 관계 초반의 발전 속도 예측 (빠르게 가까워질까? 서서히 알아갈까?).\n\n");

		prompt.append("## 2. 함께할 때의 조화와 보완 (오행 궁합)\n");
		prompt.append(
			"- 각자의 오행 분포를 비교하여, 서로의 부족한 기운을 채워주는 '상생' 관계인지, 혹은 에너지가 부딪히는 '상극' 관계인지 심층 분석.\n");
		prompt.append("- 함께 있을 때 느끼는 감정(안정감/편안함 vs 긴장감/불편함) 예측.\n");
		prompt.append("- 서로의 성장을 돕는 긍정적 측면과, 주의해야 할 부정적 측면 설명.\n\n");

		prompt.append("## 3. 현실적인 관계에서의 역할과 갈등 (십성, 합충)\n");
		prompt.append("- 각자의 십성(十星) 분포를 통해 관계에서의 역할 분담 예측 (주도/보조, 표현/수용 등).\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간 합(合)/충(沖)/형(刑) 관계 분석: 어떤 부분에서 조화를 이루고, 어떤 부분에서 갈등이 발생하기 쉬운지.\n");
		prompt.append("- 예상되는 주요 갈등 유형과 이를 해결하기 위한 구체적인 조언.\n\n");

		prompt.append("## 4. 관계 발전을 위한 맞춤 조언\n");
		prompt.append("- 서로의 장점을 더욱 살리고 단점을 보완해주기 위한 구체적인 소통 방식이나 행동 지침 2~3가지 제안.\n");
		prompt.append("- 두 사람이 함께 성장하고 행복한 관계를 오래 유지하기 위해 각자 노력해야 할 부분.\n\n");

		prompt.append("## 5. 총평: 관계의 본질과 미래\n");
		prompt.append("- 두 사람 관계의 핵심적인 특징과 잠재력을 한두 문장으로 요약.\n");
		prompt.append("- 행복한 관계를 위한 가장 중요한 조언을 강조하며 긍정적으로 마무리.\n\n");

		prompt.append("### 최종 평가 및 응답 형식 (매우 중요) ###\n");
		prompt.append("위 모든 분석 내용을 종합하여, 반드시 아래와 같은 JSON 형식으로만 응답해야 합니다.\n");
		prompt.append("그 어떤 부가적인 설명이나 markdown 감싸기(` ```json `) 없이 순수한 JSON 객체만 출력해주세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 종합 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 분석 내용을 모두 포함한 상세 해설 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// ==================== 공통 유틸리티 메서드 (기존 유지) ====================

	private void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response) {
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		prompt.append(String.format("- 이름: %s\n", name));
		prompt.append(
			String.format("- 성별: %s\n", "MALE".equalsIgnoreCase(input.getGender()) ? "남자" : "여자"));
		prompt.append(
			String.format("- 생년월일시(양력): %s %s\n", input.getSolarDate(), input.getSolarTime()));
		prompt.append(String.format("- 현재 년도: %d년\n\n", java.time.LocalDate.now().getYear()));

		prompt.append("**사주 원국(팔자)**\n");
		String sajuPalja = String.format(" 시 일 월 년\n %s %s %s %s (천간)\n %s %s %s %s (지지)",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : " ",
			saju.getDaySky().getKorean(), saju.getMonthSky().getKorean(),
			saju.getYearSky().getKorean(),
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : " ",
			saju.getDayGround().getKorean(), saju.getMonthGround().getKorean(),
			saju.getYearGround().getKorean()
		);
		prompt.append(sajuPalja + "\n\n");

		prompt.append(String.format("**일간(본질)**: %s%s (%s)\n\n",
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle(),
			saju.getDaySky().getTenStar()));

		prompt.append("**오행 분포(점수)**\n");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
		ohaengCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %.1f\n", key, value)));
		prompt.append("\n");

		prompt.append("**십성 분포(개수)**\n");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d\n", key, value)));
		prompt.append("\n");

		prompt.append("**12운성 (에너지 리듬)**\n");
		prompt.append(String.format("- 년주: %s | 월주: %s | 일주: %s | 시주: %s\n\n",
			saju.getYearGround().getUnseong() != null ? saju.getYearGround().getUnseong() : "-",
			saju.getMonthGround().getUnseong() != null ? saju.getMonthGround().getUnseong() : "-",
			saju.getDayGround().getUnseong() != null ? saju.getDayGround().getUnseong() : "-",
			saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null ?
				saju.getTimeGround().getUnseong() : "-"));

		prompt.append("**지장간 (숨겨진 DNA)**\n");
		appendJijangganDetail(prompt, "년지", saju.getYearGround());
		appendJijangganDetail(prompt, "월지", saju.getMonthGround());
		appendJijangganDetail(prompt, "일지", saju.getDayGround());
		if (saju.getTimeGround() != null) {
			appendJijangganDetail(prompt, "시지", saju.getTimeGround());
		}
		prompt.append("\n");

		prompt.append("**주요 신살 (특수 능력/주의점)**\n");
		appendSinsalAnalysis(prompt, saju); // 주요 신살 요약 추가
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format("- 공망: %s\n", String.join(", ", saju.getGongmang())));
		}
		prompt.append("\n");

		prompt.append("**대운 (10년 주기 인생 챕터)**\n");
		prompt.append(String.format("- 대운수: %d\n", saju.getBigFortuneNumber()));
		appendDaewoonFlow(prompt, saju, input.getGender()); // 대운 흐름 추가
		prompt.append("\n");
	}

	private void appendJijangganDetail(StringBuilder prompt, String pillarName,
		PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return;
		}
		JijangganInfo jijanggan = pillar.getJijanggan();
		prompt.append(String.format("- %s(%s): ", pillarName, pillar.getKorean()));
		List<String> jijangganElements = new ArrayList<>();
		if (jijanggan.getFirst() != null) {
			jijangganElements.add(String.format("%s%s(%d%%)", jijanggan.getFirst().getKorean(),
				jijanggan.getFirst().getFiveCircle(), jijanggan.getFirst().getRate()));
		}
		if (jijanggan.getSecond() != null) {
			jijangganElements.add(String.format("%s%s(%d%%)", jijanggan.getSecond().getKorean(),
				jijanggan.getSecond().getFiveCircle(), jijanggan.getSecond().getRate()));
		}
		if (jijanggan.getThird() != null) {
			jijangganElements.add(String.format("%s%s(%d%%)", jijanggan.getThird().getKorean(),
				jijanggan.getThird().getFiveCircle(), jijanggan.getThird().getRate()));
		}
		prompt.append(String.join(", ", jijangganElements) + "\n");
	}

	private void calculateDistributionWithJijanggan(
		ManseryeokCalculationResponse.SajuInfo saju,
		Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts
	) {
		ohaengCounts.put("목", 0.0);
		ohaengCounts.put("화", 0.0);
		ohaengCounts.put("토", 0.0);
		ohaengCounts.put("금", 0.0);
		ohaengCounts.put("수", 0.0);

		addElementCount(ohaengCounts, sipseongCounts, saju.getYearSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getMonthSky(), 1.0);
		addElementCount(ohaengCounts, sipseongCounts, saju.getDaySky(), 1.0);
		if (saju.getTimeSky() != null) {
			addElementCount(ohaengCounts, sipseongCounts, saju.getTimeSky(), 1.0);
		}

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
		if (ohaeng != null) {
			ohaengCounts.compute(ohaeng, (k, v) -> (v == null ? 0 : v) + weight);
		}
		String sipseong = element.getTenStar();
		if (sipseong != null) {
			sipseongCounts.compute(sipseong, (k, v) -> (v == null ? 0 : v) + 1);
		}
	}


	private void addGroundWithJijanggan(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, PillarElement ground) {
		if (ground == null) {
			return;
		}

		// 지지 자체의 오행과 십성도 계산에 포함 (1점으로 계산)
		addElementCount(ohaengCounts, sipseongCounts, ground, 1.0);

		// 지장간 계산 (가중치 적용)
		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			if (jijanggan.getFirst() != null) {
				addJijangganElement(ohaengCounts, jijanggan.getFirst());
				// 십성은 지장간 요소에 없으므로 십성 카운트는 하지 않음
			}
			if (jijanggan.getSecond() != null) {
				addJijangganElement(ohaengCounts, jijanggan.getSecond());
			}
			if (jijanggan.getThird() != null) {
				addJijangganElement(ohaengCounts, jijanggan.getThird());
			}
		}
	}


	private void addJijangganElement(Map<String, Double> ohaengCounts, JijangganElement element) {
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			ohaengCounts.compute(ohaeng, (k, v) -> (v == null ? 0 : v) + weight);
		}
	}


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

	// 기본 궁합 프롬프트용 요약 정보 주입
	private void appendPersonInfoToPrompt(StringBuilder prompt, String name,
		ManseryeokCalculationResponse manseResponse) {
		if (manseResponse == null || manseResponse.getSaju() == null) {
			prompt.append(String.format("- %s님 정보 로드 오류\n", name));
			return;
		}
		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();

		// Check for null PillarElements before accessing methods
		String yearSkyKorean = saju.getYearSky() != null ? saju.getYearSky().getKorean() : "?";
		String yearGroundKorean =
			saju.getYearGround() != null ? saju.getYearGround().getKorean() : "?";
		String monthSkyKorean = saju.getMonthSky() != null ? saju.getMonthSky().getKorean() : "?";
		String monthGroundKorean =
			saju.getMonthGround() != null ? saju.getMonthGround().getKorean() : "?";
		String daySkyKorean = saju.getDaySky() != null ? saju.getDaySky().getKorean() : "?";
		String dayGroundKorean =
			saju.getDayGround() != null ? saju.getDayGround().getKorean() : "?";
		String timeSkyKorean = saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?";
		String timeGroundKorean =
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?";
		String daySkyFiveCircle = saju.getDaySky() != null ? saju.getDaySky().getFiveCircle() : "?";

		String sajuPalja = String.format("%s%s %s%s %s%s %s%s",
			yearSkyKorean, yearGroundKorean, monthSkyKorean, monthGroundKorean,
			daySkyKorean, dayGroundKorean, timeSkyKorean, timeGroundKorean);
		prompt.append(String.format("- 사주명식: %s\n", sajuPalja));
		prompt.append(String.format("- 일간: %s%s\n", daySkyKorean, daySkyFiveCircle));

		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		// 오행/십성 계산 전 null 체크 강화
		if (saju.getYearSky() != null && saju.getMonthSky() != null && saju.getDaySky() != null) {
			calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
			prompt.append("- 오행 분포(요약): ");
			ohaengCounts.forEach(
				(key, value) -> prompt.append(String.format("%s(%.1f) ", key, value)));
			prompt.append("\n");
		} else {
			prompt.append("- 오행 분포: (계산 불가 - 필수 정보 누락)\n");
		}

		appendSinsalAnalysis(prompt, saju); // 주요 신살 요약 추가 (null 처리 내장됨)
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

			// More robust path checking
			JsonNode outputNode = root.path("output");
			if (!outputNode.isArray() || outputNode.isEmpty()) {
				log.error("응답에 유효한 'output' 배열이 없습니다. JSON: {}", jsonResponse);
				// Attempt to find choices/message structure as a fallback
				JsonNode choicesNode = root.path("choices");
				if (choicesNode.isArray() && !choicesNode.isEmpty()) {
					JsonNode messageNode = choicesNode.get(0).path("message");
					if (messageNode.isObject()) {
						JsonNode contentNode = messageNode.path("content");
						if (contentNode.isTextual()) {
							String content = contentNode.asText();
							log.info("✅ GPT 응답 성공 (Fallback Choices) - 길이: {} 문자",
								content.length());
							return content;
						}
					}
				}
				// Fallback failed, throw original error
				throw new IllegalArgumentException(
					"GPT 응답 형식이 올바르지 않습니다. ('output' 배열 누락 또는 비어있음)");
			}

			// Original logic for 'output' array
			for (JsonNode outputItem : outputNode) {
				if ("message".equals(outputItem.path("type").asText())) {
					JsonNode contentArray = outputItem.path("content");
					if (contentArray.isArray() && !contentArray.isEmpty()) {
						JsonNode textNode = contentArray.get(0).path("text");
						if (textNode.isTextual()) {
							String content = textNode.asText();
							log.info("✅ GPT 응답 성공 - 길이: {} 문자", content.length());
							return content;
						}
					}
				}
			}

			log.error("GPT 응답에서 최종 'text' 필드를 찾을 수 없습니다. JSON 구조 확인 필요. JSON: {}", jsonResponse);
			throw new IllegalArgumentException("GPT 응답에서 내용 추출 실패.");
		} catch (JsonProcessingException e) {
			log.error("JSON 파싱 실패: {}", e.getMessage());
			throw e;
		}
	}

	// --- Helper Methods (appendDetailedPillarInfo, appendSinsalAnalysis, appendDaewoonFlow) ---
	// These methods should be included here as defined in previous responses.
	// Make sure the fixed version of appendDetailedPillarInfo (without getTenStar for Jijanggan) is used.

	/**
	 * 프롬프트에 상세한 사주 기둥(Pillar) 정보를 추가하는 헬퍼 메서드 [FIXED] JijangganElement에서 .getTenStar() 호출을 제거하여
	 * DTO와 일치시킴
	 */
	private void appendDetailedPillarInfo(StringBuilder prompt, String pillarName, String meaning,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append(String.format("- **%s**: (정보 없음)\n", pillarName));
			return;
		}

		prompt.append(String.format("- **%s (%s)**: %s%s\n", pillarName, meaning,
			sky.getKorean() != null ? sky.getKorean() : "?",
			ground.getKorean() != null ? ground.getKorean() : "?"));
		prompt.append(String.format("  - 천간: %s%s (십성: %s)\n",
			sky.getKorean() != null ? sky.getKorean() : "?",
			sky.getFiveCircle() != null ? sky.getFiveCircle() : "?",
			sky.getTenStar() != null ? sky.getTenStar() : "?"));
		prompt.append(String.format("  - 지지: %s%s (십성: %s)\n",
			ground.getKorean() != null ? ground.getKorean() : "?",
			ground.getFiveCircle() != null ? ground.getFiveCircle() : "?",
			ground.getTenStar() != null ? ground.getTenStar() : "?"));

		if (ground.getUnseong() != null) {
			prompt.append(String.format("  - 12운성: %s\n", ground.getUnseong()));
		}

		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			prompt.append("  - 지장간:\n");
			if (jijanggan.getFirst() != null) {
				prompt.append(String.format("    - 초기(%d%%): %s%s\n",
					jijanggan.getFirst().getRate() != null ? jijanggan.getFirst().getRate() : 0,
					jijanggan.getFirst().getKorean() != null ? jijanggan.getFirst().getKorean()
						: "?",
					jijanggan.getFirst().getFiveCircle() != null ? jijanggan.getFirst()
						.getFiveCircle() : "?"));
			}
			if (jijanggan.getSecond() != null) {
				prompt.append(String.format("    - 중기(%d%%): %s%s\n",
					jijanggan.getSecond().getRate() != null ? jijanggan.getSecond().getRate() : 0,
					jijanggan.getSecond().getKorean() != null ? jijanggan.getSecond().getKorean()
						: "?",
					jijanggan.getSecond().getFiveCircle() != null ? jijanggan.getSecond()
						.getFiveCircle() : "?"));
			}
			if (jijanggan.getThird() != null) {
				prompt.append(String.format("    - 말기(%d%%): %s%s\n",
					jijanggan.getThird().getRate() != null ? jijanggan.getThird().getRate() : 0,
					jijanggan.getThird().getKorean() != null ? jijanggan.getThird().getKorean()
						: "?",
					jijanggan.getThird().getFiveCircle() != null ? jijanggan.getThird()
						.getFiveCircle() : "?"));
			}
		}
	}


	/**
	 * 프롬프트에 신살 정보를 요약하여 추가하는 헬퍼 메서드
	 */
	private void appendSinsalAnalysis(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju) {
		if (saju == null) {
			return;
		}
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			// 주요 신살 (천을귀인, 도화살, 역마살 등)만 추출
			saju.getSinsalInfo().forEach((key, sinsals) -> {
				if (sinsals != null && (key.equals("천을귀인") || key.equals("도화살") || key.equals("역마살")
					|| key.equals(
					"화개살") || key.equals("월덕귀인") || key.equals("천덕귀인") || key.equals("문창귀인"))) {
					allSinsal.addAll(
						sinsals.stream().filter(s -> s != null).collect(Collectors.toList()));
				}
			});
		}
		if (saju.getHasGoegang() != null && saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho() != null && saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}
		// Note: getHasYangsalsal() was not in the DTO, removed. If needed, add boolean field to SajuInfo

		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("- 주요 신살: %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		} else {
			prompt.append("- 주요 신살: 해당 없음\n");
		}
	}

	/**
	 * 프롬프트에 대운 흐름 정보를 추가하는 헬퍼 메서드
	 */
	private void appendDaewoonFlow(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju, String gender) {
		if (saju == null || saju.getYearSky() == null || saju.getMonthSky() == null
			|| saju.getMonthGround() == null || saju.getBigFortuneNumber() == null
			|| gender == null) {
			prompt.append("- 대운 흐름: (계산 불가 - 필수 정보 누락)\n");
			return;
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		if (yearSkyMinusPlus == null) {
			prompt.append("- 대운 흐름: (계산 불가 - 연간 음양 정보 누락)\n");
			return;
		}

		String flowDirection = "MALE".equalsIgnoreCase(gender) ?
			(yearSkyMinusPlus.equals("+") ? "순행" : "역행") :
			(yearSkyMinusPlus.equals("+") ? "역행" : "순행");
		prompt.append(String.format("- 대운 방향: %s\n", flowDirection));

		int startAge = saju.getBigFortuneNumber();
		String monthSkyStem = saju.getMonthSky().getKorean();
		String monthGroundBranch = saju.getMonthGround().getKorean();

		if (monthSkyStem == null || monthGroundBranch == null) {
			prompt.append("- 대운 흐름: (계산 불가 - 월주 간지 정보 누락)\n");
			return;
		}

		int currentGapjaIndex = GAPJA_CYCLE.indexOf(monthSkyStem + monthGroundBranch);
		if (currentGapjaIndex == -1) {
			prompt.append(String.format("- 대운 흐름: (월주 '%s%s' 60갑자 인덱스 오류)\n", monthSkyStem,
				monthGroundBranch));
			return;
		}

		prompt.append("- 대운 흐름 (10년 주기):\n");
		for (int i = 0; i < 9; i++) { // Show 9 Daewoon periods
			int age = startAge + (i * 10);
			if (age > 120) {
				break; // Avoid excessively high ages
			}

			int nextIndex;
			if (flowDirection.equals("순행")) {
				nextIndex = (currentGapjaIndex + i + 1) % 60;
			} else { // 역행
				// Ensure positive index after subtraction
				nextIndex = (currentGapjaIndex - (i + 1) % 60 + 60) % 60;
			}
			String daewoonGapja = GAPJA_CYCLE.get(nextIndex);
			prompt.append(String.format("  - %d세 ~ %d세: %s 대운\n", age, age + 9, daewoonGapja));
		}
	}
}
