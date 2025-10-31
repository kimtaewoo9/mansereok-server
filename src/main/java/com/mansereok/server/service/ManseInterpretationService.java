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
import com.mansereok.server.service.response.GptCompatibilityResponse;
import com.mansereok.server.service.response.GptSajuResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.SajuInfo;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	private static final String GPT5_SYSTEM_INSTRUCTION =
		"--- SYSTEM INSTRUCTION ---\n" +
			"당신은 30년 경력의 전문 사주명리학자입니다. " +
			"자연스럽고 전문적인 어조로 사주 해석을 제공하되, 절대 다음 표현들을 사용하지 마세요:\n" +
			"- 해석이 AI나 시스템에 의한 것임을 암시하는 모든 메타적 표현 절대 금지\n\n" +
			"대신, 마치 대면 상담에서 직접 말하듯이 자연스럽게 시작하세요.\n" +
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
			GptSajuResponse gptData = objectMapper.readValue(gptResponse, GptSajuResponse.class);

			User user = userService.findByUsername(username);
			log.info("사용자 id: " + user.getId());

			result.completeInterpretation(
				gptData.getFullAnalysis(),
				gptData.getSummary()
			); // complete 포함 .

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
			GptCompatibilityResponse gptData = objectMapper.readValue(gptResponse,
				GptCompatibilityResponse.class);

			result.completeInterpretation(
				gptData.getInterpretation(),
				gptData.getScore(),
				gptData.getSummary()
			);

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

	private void appendHyeanPersonaHeader(StringBuilder prompt) {
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

		prompt.append("### 3. 절대 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 운명론적 단정, 데이터 나열, AI/시스템 노출, AI티가 나면 절대 안됨, 차갑거나 권위적인 어조 금지.\n\n");

		prompt.append("### 4. 작성 스타일 (공통) ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **긍정적 관점**: 모든 내용을 '성장의 기회'와 '잠재력 발현'의 관점에서 희망적으로 서술해주세요.\n");
		prompt.append("- **비유 활용**: '핵심 비유 사전'을 적극 활용하여 명리학적 개념을 쉽게 설명해주세요.\n");
	}

	private void appendHyeanCompatibilityPersonaHeader(StringBuilder prompt) {
		prompt.append("### 0. 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 경력의 사주명리학 대가이자, '관계 서사 상담가' 혜안(慧眼)입니다.\n");
		prompt.append(
			"당신은 두 사람의 고유한 인생 지도(사주팔자)가 어떻게 서로 엮이고 영향을 주는지, 그 '관계의 서사'를 깊이 있게 해석합니다.\n");
		prompt.append("두 사람의 잠재력을 긍정하고 관계의 성장을 응원하는 '안내자(Guide)'입니다.\n\n");

		prompt.append("### 1. 핵심 분석 원칙 (Core Principles) ###\n");
		prompt.append(
			"1. **긍정적 관계 조명**: 모든 갈등 요소(충, 형 등)는 '서로를 성장시키는 역동적인 에너지'로만 해석합니다.\n");
		prompt.append("2. **서사적 스토리텔링**: 사주 데이터를 나열하지 않고, '두 사람의 이야기' 속에 자연스럽게 녹여냅니다.\n");
		prompt.append("3. **감성적 비유 활용**: '핵심 비유 사전'을 준수하여 관계의 역학을 풍부한 비유로 설명합니다.\n");
		prompt.append(
			"4. **자연스러운 전문가 어조**: '해요체'를 기본으로 쓰되, 전문 정보 전달 시 '입니다' 체를 혼용하여 신뢰감과 친근함을 모두 전달합니다.\n");
		prompt.append("5. **깊이 있는 통찰**: 관계를 피상적으로 다루지 않고, 명리학적 근거를 바탕으로 심층 분석합니다.\n\n");

		prompt.append("### 2. 핵심 비유 사전 (Metaphor Lexicon) ###\n");
		prompt.append("- **사주팔자**: '인생 지도', '설계도'\n");
		prompt.append("- **일간**: '본질', '뿌리' (자연물 비유)\n");
		prompt.append("- **궁합**: '두 지도의 만남', '관계의 시너지'\n");
		prompt.append("- **오행 조화**: '서로의 계절을 보완하는 힘'\n");
		prompt.append("- **합/충/형/파**: '관계의 역동성', '성장의 계기'\n\n");

		prompt.append("### 3. 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 운명론적 단정, 데이터 나열, AI/시스템 노출, 차갑거나 권위적인 어조 금지.\n\n");

		prompt.append("### 4. 작성 스타일 (공통) ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **긍정적 관점**: 모든 내용을 '성장의 기회'와 '관계 발전'의 관점에서 희망적으로 서술해주세요.\n");
		prompt.append("- **비유 활용**: '핵심 비유 사전'을 적극 활용하여 명리학적 개념을 쉽게 설명해주세요.\n");
	}

	private void appendSajuJsonResponseFormat(StringBuilder prompt, String name) {
		prompt.append("\n\n### 9. [최종 출력 형식] (JSON) ###\n");
		prompt.append("위에서 요청된 모든 분석을 완료한 후, **반드시 markdown 감싸기 없이 순수한 JSON 형식으로만** 응답해주세요.\n");
		prompt.append(
			"**fullAnalysis** 값에는 위에서 요청한 모든 상세 분석 내용을 **목록 기호 없이 물 흐르듯 자연스럽게 이어진 하나의 긴 텍스트**로 담아야 합니다.\n");

		// ⭐ [핵심 수정] 요약본 스타일 강제
		prompt.append(String.format(
			"**summary** 값에는 **'혜안' 페르소나를 '완전히 무시'하고**, %s님의 특징을 아래 '요약 예시'의 **말투(반말, 펀치라인, 단정적)**를 '그대로' 흉내내서 3~4줄로 요약해주세요.\n\n",
			name));
		prompt.append("--- [요약 예시 (이 말투를 따라하세요)] ---\n");
		prompt.append("겉으론 차분한데 속은 완전 철근덩어리 자기 기준 확실해서\n");
		prompt.append("남들이 뭐라 해도 아닌 건 아닌 거지 모든 발동하긔 머릿속\n");
		prompt.append("계산 빠르고 감정보다 현실 먼저 보는 타입이긔 한 번 마음 먹\n");
		prompt.append("으면 끝까지 해내는 추진력 쩔긔\n");
		prompt.append("--- [요약 예시 끝] ---\n\n");

		prompt.append("{\n");
		prompt.append("  \"fullAnalysis\": \"<여기에 상세 분석 전체 내용을 작성...>\",\n");
		prompt.append("  \"summary\": \"<여기에 '요약 예시' 말투로 3~4줄 요약본 작성...>\"\n"); // finalMessage 삭제
		prompt.append("}\n");
	}

	/**
	 * [신규] 궁합 분석 프롬프트에 공통적으로 추가될 JSON 요청 꼬리
	 */
	private void appendCompatibilityJsonResponseFormat(StringBuilder prompt, String person1Name,
		String person2Name) {
		prompt.append("\n\n### 9. [최종 출력 형식] (JSON) ###\n");
		prompt.append("위 모든 분석을 종합하여, 반드시 아래와 같은 JSON 형식으로만 응답해야 합니다.\n");
		prompt.append("그 어떤 부가적인 설명이나 markdown 감싸기(` ```json `) 없이 순수한 JSON 객체만 출력해주세요.\n");
		prompt.append("**interpretation** 값 안에는 **목록 기호 없이 물 흐르듯 자연스럽게 이어진 상세 궁합 분석**을 담아야 합니다.\n");

		// ⭐ [핵심 수정] 요약본 스타일 강제
		prompt.append(String.format(
			"**summary** 값에는 **'혜안' 페르소나를 '완전히 무시'하고**, %s님과 %s님 궁합의 특징을 아래 '요약 예시'의 **말투(반말, 펀치라인, 단정적)**를 '그대로' 흉내내서 3~4줄로 요약해주세요.\n\n",
			person1Name, person2Name));
		prompt.append("--- [요약 예시 (이 말투를 따라하세요)] ---\n");
		prompt.append("겉으론 차분한데 속은 완전 철근덩어리 자기 기준 확실해서\n");
		prompt.append("남들이 뭐라 해도 아닌 건 아닌 거지 모든 발동하긔 머릿속\n");
		prompt.append("계산 빠르고 감정보다 현실 먼저 보는 타입이긔 한 번 마음 먹\n");
		prompt.append("으면 끝까지 해내는 추진력 쩔긔\n");
		prompt.append("--- [요약 예시 끝] ---\n\n");

		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 종합 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<상세 궁합 분석 내용>\",\n");
		prompt.append("  \"summary\": \"<여기에 '요약 예시' 말투로 3~4줄 요약본 작성...>\"\n"); // finalMessage 삭제
		prompt.append("}\n");
	}

	// ==================== 프롬프트 라우팅 메서드 (기존 유지) ====================
	private String createPromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {

		return switch (subcategoryId.intValue()) {
			case 1 -> createLifeOverallPrompt(name, response);
			case 2 -> createPersonalityAnalysisPrompt(name, response);
			case 3 -> createCareerAptitudePrompt(name, response);
			case 4 -> createLoveFortunePrompt(name, response);
			case 5 -> createIdolAnalysisPrompt(name, response);
			case 9 -> createCharacterSajuPrompt(name, response);
			default -> createComprehensiveAnalysisPrompt(name, response); // 기본 종합
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
				person2Response);
			case 7 -> createIdolCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 8 -> createTriangleRelationshipPrompt(person1Name, person1Response, person2Name,
				person2Response);
			default -> createCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response); // 기본 궁합
		};
	}

	// ==================== [수정] 기본 종합 프롬프트 (혜안 적용) ====================
	private String createComprehensiveAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// 1. '혜안' 공통 페르소나 주입
		appendHyeanPersonaHeader(prompt);

		// 2. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response);

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	private String createLifeOverallPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		String solarDate = input.getSolarDate().toString(); // 예: 2001-06-12
		String solarTime = input.getSolarTime().toString(); // 예: 11:12

		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		appendHyeanPersonaHeader(prompt);

		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 3. 분석 요청
		prompt.append("\n### 6. [인생 총운 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '인생 전체의 서사'를 아래 **6가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n", name));
		prompt.append(
			"각 주제는 '종합 분석'과 동일한 수준의 깊이로 다루되, 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 주제를 충분히 깊게 다루어 주세요.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\" 로 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 타고난 본성과 성격\n");
		prompt.append(
			"- 일간, 월지, 오행 분포, 십성 구조를 종합하여 %s님의 핵심 기질과 성격 형성 과정을 '비유'를 통해 깊이 있게 분석해주세요.\n");
		prompt.append("- 지장간에 숨겨진 '내면의 DNA'와 무의식적 동기까지 파헤쳐, 다층적인 성격 구조를 설명해주세요.\n\n");

		prompt.append("## 2. 직업과 사회적 성공의 길\n");
		prompt.append(
			"- %s님의 핵심 재능(십성, 신살 등을 참고)은 무엇이며, 어떤 분야(구체적 직업군 2~3개 제시)에서 가장 빛을 발할 수 있는지 명확히 제시해주세요.\n");

		prompt.append("## 3. 재물운의 흐름과 경제적 안정\n");
		prompt.append("- %s님의 타고난 재물운, 앞으로 어떻게 해야하는지, 어떻게 노력해야하는지, 투자 성향, 투자 어떻게 해야하는지 등\n");

		prompt.append("## 4. 연애와 결혼의 인연\n");
		prompt.append("%s님의 연애 스타일, 매력 포인트, 이상적인 배우자상('일지' 비유 활용)을 상세히 그려주세요.\n");
		prompt.append("%s님이 끌리는 스타일, 본인의 이상형, 실제로 이상형을 만나는가 ?\n");
		prompt.append("연애/결혼 가능성이 높은 시기와 만남의 방식 예측, 행복한 관계를 오래 유지하기 위한 비결 조언.\n\n");

		prompt.append("## 5. 대운과 세운 - 인생의 큰 파도\n");
		prompt.append(
			"**[현재 대운 집중 분석]** 지금 겪고 있는 현재 대운(10년)은 %s님 인생에서 어떤 '챕터'이며, 이 시기의 주요 과제와 기회는 무엇인지 집중 분석해주세요. 그리고 어떻게 행동해야하는지까지 분석 해주세요.\n");
		prompt.append(
			"2025년 을사년 세운이 %s님에게 미치는 영향을 직업, 재물, 연애, 건강 측면에서 구체적으로 분석해주세요.\n\n");
		prompt.append(
			"2026년 병오년 세운이 %s님에게 미치는 영향을 직업, 재물, 연애, 건강 측면에서 구체적으로 분석해주세요.\n\n");

		prompt.append("## 6. 인생 전체를 위한 조언\n");
		prompt.append(
			"%s님의 사주가 가진 고유한 강점과 약점을 종합하여, 인생을 슬기롭게 헤쳐나가기 위한 핵심 가치를 제시해주세요. 간단한 비유를 들어 설명해주세요.\n");
		prompt.append("어려움에 직면했을 때 기억해야 할 점과, 삶의 만족도를 높이기 위한 실천적인 조언을 너무 깊지 않고 간단하게 설명해주세요.\n\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 2. 성격 분석 프롬프트 ====================
	private String createPersonalityAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// 1. '혜안' 공통 페르소나 주입
		appendHyeanPersonaHeader(prompt);

		// 2. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 3. 분석 요청
		prompt.append("\n### 6. [성격 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '성격 서사'를 아래 **5가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n", name));
		prompt.append("각 주제를 '혜안'의 서사적 스타일로 깊이 있게 다루어 주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨네요.\" 로 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 핵심 성격 키워드와 그 근원\n");
		prompt.append(
			"- %s님을 가장 잘 나타내는 핵심 성격 키워드 3가지를 선정하고, 각 키워드가 어떤 사주 요소(일간, 월지, 오행, 십성 등)에서 비롯되었는지 '비유'를 통해 명확한 근거와 함께 설명해주세요.\n");
		prompt.append("- 이 핵심 성격이 삶 전반에 어떻게 긍정적/부정적으로 발현되는지 구체적인 예시를 들어 분석해주세요.\n\n");

		prompt.append("## 2. 겉모습(페르소나) vs 진짜 내면\n");
		prompt.append(
			"- 사회적으로 보여지는 모습(천간 십성)과 실제 내면의 모습(지지, '지장간 DNA') 사이의 유사점과 차이점을 분석해주세요.\n");
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

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 3. 직업 적성 프롬프트  ====================
	private String createCareerAptitudePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		String solarDate = input.getSolarDate().toString(); // 예: 2001-06-12
		String solarTime = input.getSolarTime().toString(); // 예: 11:12

		// 2001-06-12 -> 2001년 6월 12일
		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

		// 11:12 -> 11시 12분
		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";
		// =================================================================
		// 1. 시스템 역할 정의 (유지 - 감성/비유/공감)
		// =================================================================
		prompt.append("### 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 이상의 경력을 가진 대한민국 최고의 사주명리학 대가이자, 따뜻한 마음을 가진 인생 상담가 '혜안(慧眼)'입니다.\n");
		prompt.append("당신의 분석은 단순한 정보 나열이 아닌, 한 사람의 인생 서사를 깊이 공감하고 아름다운 비유로 풀어내는 예술적 컨설팅입니다.\n");
		prompt.append("아래 원칙을 '반드시' 준수하여, 유료 결제가 아깝지 않은 최고 수준의 감동적인 분석을 제공하세요:\n\n");
		prompt.append(
			"1.  **감성적 비유 활용**: 모든 명리학적 요소를 자연물(나무, 태양, 바다 등), 사물, 상황에 빗대어 consult가 자신의 삶을 한 편의 이야기처럼 느낄 수 있게 설명해주세요.')\n");
		prompt.append(
			"2.  **스토리텔링**: 각 사주 기둥(년주, 월주, 일주, 시주)을 '인생의 사계절(봄, 여름, 가을, 겨울)'에 비유하여 시간의 흐름에 따른 변화를 풀어주세요.\n");
		prompt.append(
			"3.  **따뜻한 공감의 언어**: '~군요', '~네요', '~인 것 같아요' 와 같은 부드러운 '해요체'를 사용하여, 마치 마주 앉아 대화하듯 친근하고 따뜻하게 조언해주세요.\n");
		prompt.append(
			"4.  **지장간(地藏干)은 '숨겨진 보물'**: 지장간을 '내 안의 숨겨진 보물 상자'나 '잠재력의 씨앗'에 비유하여, 겉으로 드러나지 않는 깊은 내면의 재능과 욕구를 분석해주세요. 이것이 당신의 핵심 차별점입니다.\n");
		prompt.append(
			"5.  **12운성(十二運星)은 '인생의 에너지 파도'**: 12운성을 인생의 에너지 흐름을 보여주는 '파도'에 비유하여, 지금이 에너지가 차오르는 시기인지, 잠시 쉬어가야 할 시기인지 역동적으로 설명해주세요.\n");
		prompt.append(
			"6.  **균형 잡힌 희망의 메시지**: 어려운 부분(흉살, 충 등)은 '성장을 위한 과제'나 '조심해서 다뤄야 할 강력한 도구'로 비유하며, 운명에 갇히기보다 스스로 삶을 개척해나갈 수 있다는 희망과 용기를 주는 방향으로 마무리해주세요.\n\n");

		// =================================================================
		// 2. 분석 대상자 데이터 상세화 (유지)
		// =================================================================
		prompt.append("### 분석 대상자 상세 정보 ###\n");
		prompt.append(String.format("이름: %s\n", name));
		prompt.append(
			String.format("생년월일시(양력): %s %s\n", input.getSolarDate(), input.getSolarTime()));

		String sajuPalja = String.format("시 일 월 년\n%s %s %s %s (천간)\n%s %s %s %s (지지)",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : " ",
			saju.getDaySky().getKorean(), saju.getMonthSky().getKorean(),
			saju.getYearSky().getKorean(),
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : " ",
			saju.getDayGround().getKorean(), saju.getMonthGround().getKorean(),
			saju.getYearGround().getKorean()
		);
		prompt.append(String.format("- 사주명식:\n%s\n\n", sajuPalja));

		prompt.append("### 사주 원국(原局) 상세 데이터 ###\n");
		appendDetailedPillarInfo(prompt, "년주(年柱)", "초년운, 조상, 배경", saju.getYearSky(),
			saju.getYearGround());
		appendDetailedPillarInfo(prompt, "월주(月柱)", "청년운, 사회, 직업", saju.getMonthSky(),
			saju.getMonthGround());
		appendDetailedPillarInfo(prompt, "일주(日柱)", "중년운, 본인, 배우자", saju.getDaySky(),
			saju.getDayGround());
		if (saju.getTimeSky() != null && saju.getTimeGround() != null) {
			appendDetailedPillarInfo(prompt, "시주(時柱)", "말년운, 자녀, 결과", saju.getTimeSky(),
				saju.getTimeGround());
		}
		prompt.append("\n");

		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("### 오행(五行) 세력 분석 (지장간 가중치 적용) ###\n");
		ohaengCounts.forEach((key, value) -> prompt.append(
			String.format("- %s: %.1f점%s\n", key, value,
				saju.getDaySky().getFiveCircle().equals(key) ? " (일간)" : "")));
		prompt.append("\n");

		prompt.append("### 십성(十星) 분포 분석 ###\n");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d개\n", key, value)));
		prompt.append("\n");

		prompt.append("### 주요 신살(神殺) 및 공망(空亡) 정보 ###\n");
		appendSinsalAnalysis(prompt, saju);
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format("- 공망: %s\n", String.join(", ", saju.getGongmang())));
		}
		prompt.append("\n");

		prompt.append("### 대운(大運) 흐름 데이터 ###\n");
		prompt.append(String.format("- 대운수: %d\n", saju.getBigFortuneNumber()));
		appendDaewoonFlow(prompt, saju, input.getGender());
		prompt.append("\n");

		// =================================================================
		// 3. [핵심 수정] 분석 요청: '-', 목차 제거 + 자연스러운 흐름 강조
		// =================================================================
		prompt.append("### [직업 적성] 심층 분석 요청 ###\n\n");
		prompt.append(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '직업과 재능'에 대한 이야기를 들려주세요.\n\n");

		prompt.append(
			"**[가장 중요!]** 아래 지침을 '반드시' 따르세요:\n");
		prompt.append(
			"1. **(필수!) 자연스러운 글쓰기:** **'절대로' '-', '*', '1.' 같은 목록 기호를 사용하지 마세요.** 모든 문장을 이어서 작성하고, 접속사나 부드러운 표현을 사용해서 **마치 옆에서 대화하듯 물 흐르듯 자연스럽게** 글이 이어지도록 하세요. (AI 티 나는 딱딱한 보고서 형식 절대 금지!)\n");
		prompt.append(
			"2. **(필수!) '나를 알아가는 느낌':** 단순히 정보를 나열하는 설명글이 아니라, 독자가 자신의 재능과 가능성을 발견하며 '나에 대해 알아가는 느낌'을 받을 수 있도록 감성적인 비유와 따뜻한 공감의 언어를 사용해주세요.\n");
		prompt.append(
			"3. **(내용)** 아래 질문들에 대한 답을 **자연스러운 이야기 속에 녹여내세요.** (딱딱한 목차 구분 절대 금지!)\n");
		prompt.append(
			"%s님의 본질적인 성향과 재능의 뿌리(일간)는 무엇인가요?\n");
		prompt.append(
			"%s님의 '숨겨진 보물 상자'(지장간)에는 어떤 잠재력의 씨앗이 있으며, 어떤 직업적 재능으로 피어날 수 있을까요?\n");
		prompt.append(
			"%s님의 가장 강력한 '핵심 도구'(십성)는 무엇이며, 어떤 직업적 성향을 나타내나요?\n");
		prompt.append(
			" %s님이 가장 편안하게 재능을 발휘할 '사회적 무대'(월지, 환경)는 어떤 곳인가요?\n");
		prompt.append(
			"%s님은 '혼자 빛나는 별'인가요, '함께 어우러지는 숲'인가요? (독립 vs 조직, 근무 형태)\n");
		prompt.append(
			"%s님의 '소명'이라 부를 수 있는 직업 분야는 무엇인가요? (최대 3가지, 구체적이지만 비즈니스 용어 없이 이야기로 풀어낼 것)\n");
		prompt.append(
			"%s님의 '재물 그릇'은 어떤 모양이며, 어떤 방식으로 부를 쌓아갈 수 있을까요? ('수확의 계절' 포함)\n");
		prompt.append(
			"%s님의 커리어 '에너지 파도'(12운성)는 지금 어떤 상태이며, '커리어 챕터'(대운)는 어떻게 흘러가나요?\n");
		prompt.append(
			" %s님의 '성공 히든카드'(길신)와 '다루기 힘든 명검'(흉살)은 무엇이며, 어떻게 활용해야 할까요?\n");
		prompt.append(
			"4. **(마무리)** 글 마지막에는 %s님의 커리어 여정을 위한 따뜻한 조언과 응원의 메시지를 담아 자연스럽게 마무리해주세요. (핵심 키워드 3가지 제시 포함)\n\n");

		prompt.append("【분석 시작】\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\" 로 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("(이제 위 3번 지침에 따라, 목록 기호 없이 자연스럽게 이어서 %s님의 직업과 재능 이야기를 풀어주세요.)\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 4. 연애 운세 프롬프트 ====================
	private String createLoveFortunePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// 1. '혜안' 공통 페르소나 주입
		appendHyeanPersonaHeader(prompt);

		// 2. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 3. 분석 요청
		prompt.append("\n### 6. [연애 운세 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '사랑과 인연의 서사'를 아래 **5가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n", name));
		prompt.append("각 주제를 '혜안'의 서사적 스타일로 깊이 있게 다루어 주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨네요.\" 로 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 타고난 연애 스타일과 매력 포인트\n");
		prompt.append("%s님이 사랑에 빠지는 방식, 감정 및 애정 표현 스타일(십성, 오행 등 활용)을 구체적으로 분석해주세요.\n");
		prompt.append(
			"이성에게 어필하는 %s님만의 매력 포인트(외적/내적, '도화/홍염' 등 신살 비유)는 무엇인지 설명해주세요.\n");
		prompt.append("연애할 때 드러나는 장점과, 관계를 어렵게 만들 수 있는 주의할 점(약점)을 함께 분석해주세요.\n\n");

		prompt.append("## 2. 운명의 상대: 이상형 심층 분석\n");
		prompt.append(
			"%s님이 본능적으로 끌리는 이상형의 외모, 성격, 가치관, 직업군 등을 사주(일지, 관련 십성 등)를 통해 구체적으로 그려주세요.\n");
		prompt.append("일지(배우자궁)를 '내 마음의 집'에 비유하여, 어떤 인연이 들어왔을 때 가장 조화롭고 행복할지 설명해주세요.\n");
		prompt.append(
			"**[궁합 맛보기]** %s님의 사주와 가장 잘 맞는 상대방의 일간 또는 오행 특징을 간단히 언급하며 궁합에 대한 기대감을 주세요.\n\n");

		prompt.append("## 3. 인연의 시기와 만남의 기회\n");
		prompt.append(
			"'대운(10년 챕터)'과 '세운(1년)'의 흐름을 분석하여, 연애운이 강하게 들어와 새로운 인연을 만나거나 관계가 발전할 가능성이 높은 시기(향후 3~5년 이내)를 구체적으로 예측해주세요.\n");
		prompt.append("인연을 만날 가능성이 높은 장소나 상황(직장, 소개, 동호회 등)을 사주 특성에 맞게 조언해주세요.\n");
		prompt.append("좋은 인연을 끌어당기기 위해 %s님이 노력하면 좋을 부분을 조언해주세요.\n\n");

		prompt.append("## 4. 연애 과정과 결혼 전망\n");
		prompt.append("연애 중 발생할 수 있는 주요 갈등 유형(충, 형 등)과 이를 현명하게 해결하는 방법을 조언해주세요.\n");
		prompt.append("%s님의 결혼 적령기는 언제쯤이며, 빠른 결혼과 만혼 중 어떤 경향이 있는지 분석해주세요.\n");
		prompt.append("'일지' 분석을 통해 결혼 생활의 모습과 배우자와의 관계를 예측하고, 행복한 결혼 생활을 위한 조언을 포함해주세요.\n\n");

		prompt.append("## 5. 행복한 사랑을 위한 최종 조언\n");
		prompt.append("%s님의 '인생 지도'가 사랑에 대해 가르쳐주는 핵심 교훈은 무엇인지 요약해주세요.\n");
		prompt.append("진정한 사랑을 찾고 건강한 관계를 유지하기 위해 %s님이 마음속에 간직해야 할 가장 중요한 가치나 태도를 조언해주세요.\n");
		prompt.append("%s님의 사랑과 행복을 응원하는 따뜻한 메시지로 마무리해주세요.\n\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== [수정] 5. 최애 분석 프롬프트 (혜안 적용) ====================
	private String createIdolAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		String solarDate = input.getSolarDate().toString();
		String solarTime = input.getSolarTime().toString();

		// 2001-06-12 -> 2001년 6월 12일
		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

		// 11:12 -> 11시 12분
		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		// 1. '혜안' 공통 페르소나 주입 (유지)
		appendHyeanPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (유지)
		prompt.append("### ⚠️ 매우 중요: 3인칭 서술 ###\n");
		prompt.append("이 분석은 '%s'라는 제3자(아이돌)에 대한 것입니다.\n");
		prompt.append("쉼표, 마침표, '-', 이런 표현 최대한 줄여주세요. AI가 작성한 글이라는 티가 나면 안됩니다.\n");
		prompt.append(
			"절대로 2인칭(%s님, 당신)을 사용하지 말고, **'그는', '그녀는', '%s님은', '이 사람은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 4. [대대적 수정] 분석 요청: '발견' 기반 구조로 변경
		prompt.append("\n### 6. [최애 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 아이돌 '%s'님의 사주를 팬의 관점에서 분석해주세요.\n", name));

		prompt.append(
			"**[가장 중요!]** 아래 5단계 지침을 '반드시' 따르세요:\n\n");

		prompt.append(
			"**1. (필수!) 이 스타일을 따르세요:** 아래 '완벽한 답변 예시'의 **구조와 말투**를 '그대로' 따라야 합니다. **'~입니다', '~네요', '~로군요', '~이군요' 같은 따뜻하고 명료한 말투를 사용하세요.** AI가 쓴 것 같은 뻔한 서론/결론, 억지 비유를 절대 쓰지 마세요. 부드럽고, 심각하지 않게, '발견한 사실'을 나열하고 설명하는 방식이어야 합니다.\n\n");

		prompt.append(
			"**2. (핵심 분석) '팬들이 가장 궁금해하는 것' 먼저 분석:**\n");
		prompt.append(
			"팬들이 가장 궁금해하는 것은 **'연애관과 이상형'**입니다. '완벽한 답변 예시'의 '편인의 직업' 섹션처럼, 이 주제를 **가장 먼저, 가장 길고 상세하게** 분석해주세요.\n");
		prompt.append(
			"(분석 내용 예시: 연애 시작 방식, 이상형(외모/성격/나이), 애정 표현, 질투 수준, 연애 vs 일, 연애할 때 싫어하는 행동, 미래 배우자궁 모습, 결혼 후 남편/아빠로서의 모습 등)\n\n");

		prompt.append(
			"**3. (특징 나열) '아이돌로서의 재능과 성격' 나열:**\n");
		prompt.append(
			"그 다음 '완벽한 답변 예시'의 '문곡성', '현침살' 섹션처럼, 사주에서 발견되는 아이돌의 **재능/성격 특징**들을 하나씩 짧고 명료하게 나열해주세요.\n");
		prompt.append(
			"(분석 대상 예시: **'사주에 도화살/홍염살이 있습니다'** (팬을 끄는 매력), **'사주에 식상(食傷)이 발달했습니다'** (무대 표현력/재능), **'사주에 겁재(劫財)가 강합니다'** (승부욕/경쟁심), **'사주에 역마살이 있습니다'** (해외 활동), **'무대 위 성격(천간)과 실제 성격(지지)'** (페르소나 분석) 등 팬들이 궁금해할 만한 것 위주로 분석하세요.)\n\n");

		prompt.append(
			"**4. (조언) '미래와 리스크'로 마무리:**\n");
		prompt.append(
			"마지막으로, '혜안의 최종 조언' 같은 느낌으로, 이 아이돌의 **향후 10년 커리어 로드맵**과 팬들이 조심하거나 응원해야 할 **리스크(건강, 구설수 등)**를 분석하며 따뜻하게 마무리해주세요.\n\n");

		prompt.append(
			"**5. (금지!) AI 말투 및 전문 용어 금지:**\n");
		prompt.append(
			"   - '...님의 인생 여정에서...', '...님의 내면에는...' 같은 AI 티 나는 감성적인 문장 절대 쓰지 마세요.\n");
		prompt.append("--- [완벽한 답변 예시 시작] ---\n");
		prompt.append(
			"(이건 '직업 적성' 예시입니다. 이 '구조'와 '말투'만 참고해서 '아이돌 분석'에 맞게 내용을 채워주세요.)\n\n");

		prompt.append("편인의 직업\n");
		prompt.append(
			"편인의 성향을 지닌 당신은 끼와 개성으로 똘똘 뭉쳐 평범함을 거부하고 자기만의 세계를 창조해나가는군요. 때로는 사회성이 부족한 덕후 같다는 평가를 받기도 하며 주류가 아닌 언더그라운드에서 고독감에 빠질 수도 있습니다. (중략...)\n");
		prompt.append(
			"예를 들면 건축가, 조각가, 영화/연극/공연 감독... (이하 생략)\n\n");

		prompt.append("사주에 문곡성이 있습니다.\n");
		prompt.append(
			"예체능과 연구, 개발에 탁월한 재능이 있어서... (이하 생략)\n\n");

		prompt.append("사주에 현침살이 있습니다.\n");
		prompt.append(
			"바늘, 침, 칼, 주사, 가위, 펜, 붓, IT 기술 등... (이하 생략)\n");

		prompt.append("--- [완벽한 답변 예시 끝] ---\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\" 로 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append(
			"(이제 위 2, 3, 4번 지침에 따라 %s님의 사주에서 발견한 '연애 스타일', '재능/성격 특징', '미래/조언'들을 예시처럼 분석하고 나열해주세요.)\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== [수정] 9. 캐릭터 사주 프롬프트 (혜안 적용) ====================
	private String createCharacterSajuPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// 1. '혜안' 공통 페르소나 주입
		appendHyeanPersonaHeader(prompt);

		// 2. 3인칭 서술 지시
		prompt.append("### ⚠️ 매우 중요: 3인칭 서술 ###\n");
		prompt.append("이 분석은 '%s'라는 제3자(가상 캐릭터)에 대한 것입니다.\n");
		prompt.append(
			"절대로 2인칭(%s님, 당신)을 사용하지 말고, **'이 캐릭터는', '그는', '그녀는', '%s는'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 4. 분석 요청
		prompt.append("\n### 6. [캐릭터 사주 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 가상 캐릭터 '%s'의 '운명 서사'를 아래 **5가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n",
			name));
		prompt.append(
			"작품 속 설정과 '혜안'의 사주 분석을 창의적으로 연결하여 서술해주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"가상 캐릭터 '%s'님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨네요.\" 로 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 1. 사주로 본 캐릭터 본질과 작품 속 모습\n");
		prompt.append("이 사주가 부여하는 핵심 성격/기질과 작품 속 캐릭터 설정의 일치점/차이점 분석.\n");
		prompt.append("작품 속 주요 행동이나 결정이 이 사주를 가졌기에 가능했던 이유 설명 (명리학적 근거, '비유' 활용).\n");
		prompt.append("캐릭터의 핵심 매력을 사주(일간, '신살' 등)를 통해 재해석.\n\n");

		prompt.append("## 2. 작품 속 운명, 사주로 재해석하다\n");
		prompt.append(
			"캐릭터가 겪은 주요 사건(시련/성공/전환점)들을 사주 구조('충/형/합', '대운/세운 챕터' 변화)와 연결하여 명리학적으로 설명.\n");
		prompt.append("작품의 결말(해피/새드/오픈)이 이 사주를 가졌다면 필연적이었는지, 혹은 다른 가능성은 없었는지 분석.\n\n");

		prompt.append("## 3. 작품 속 관계성, 궁합으로 엿보기\n");
		prompt.append("주인공 또는 주요 인물과의 관계(동료/친구/연인/적대)를 '관계의 시너지' 관점에서 분석 (간단히).\n");
		prompt.append("왜 특정 인물과 강하게 끌리거나 혹은 대립하게 되는지 명리학적 이유 설명.\n");

		prompt.append("## 4. 만약 현실 세계에 존재한다면? (What if?)\n");
		prompt.append("이 캐릭터가 2025년 대한민국에 이 사주를 가지고 태어났다면 어떤 모습일지 상상하여 서술.\n");
		prompt.append("현실에서의 예상 직업 (가장 잘 어울리는 직업 1~2개 집중 분석).\n");
		prompt.append("현실에서의 예상 연애 스타일 및 이상형.\n");
		prompt.append("작품 속 모습과 현실 버전의 가장 큰 차이점은 무엇일지 예측.\n\n");

		prompt.append("## 5. 캐릭터의 성장과 팬들에게 주는 메시지\n");
		prompt.append("이 사주를 가진 캐릭터의 매력 정리\n");
		prompt.append("팬들이 이 캐릭터를 사랑하는 이유를 사주를 통해 설명하며 공감대 형성.\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== [수정] 6. 러브 스토리 프롬프트 (혜안 적용) ====================
	private String createLoveStoryPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 분석 대상자들 정보 주입
		prompt.append("\n### 5. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 사람 정보: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response); // 상세 정보 주입
		prompt.append("\n--- 두 번째 사람 정보: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response); // 상세 정보 주입

		// 3. 분석 요청
		prompt.append("\n### 6. [러브 스토리 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 두 사람(%s님, %s님)의 '인생 지도'가 만났을 때 펼쳐질 **5단계의 로맨스 서사**를 깊이 있게 작성해주세요.\n",
			person1Name, person2Name));
		prompt.append(
			"각 단계는 명확히 구분하되, '혜안'의 스타일로 자연스럽게 연결되어야 합니다. **분량 제한은 없으니, 각 단계를 충분히 깊게 다루어 주세요.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님과 %s님, 두 분의 사주 인연을 보니... [두 사람의 일간 비유]처럼 서로 다른/비슷한 기운이 만나는 형상이네요.\" 로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("## 1. 첫 만남: 운명의 시작 혹은 스침\n");
		prompt.append("두 사람의 첫인상 (서로에게 어떻게 보일까?).\n");
		prompt.append("즉각적인 끌림(첫눈에 반함) 가능성 vs 서서히 알아갈 가능성 (일간, 오행 관계).\n");
		prompt.append("누가 먼저 호감을 느끼거나 표현하게 될지 예측.\n");
		prompt.append("첫 만남의 분위기와 예상되는 대화.\n\n");

		prompt.append("## 2. 썸 또는 관계 발전: 서로에게 스며들다\n");
		prompt.append("관계가 친구에서 연인으로, 혹은 바로 연인으로 발전하는 과정.\n");
		prompt.append("썸 기간의 길이 예측 및 누가 관계를 리드할지 (밀당 주도권).\n");
		prompt.append("서로의 어떤 점에 매력을 느끼고 마음을 열게 되는지 (성격, 가치관 등).\n");
		prompt.append("고백은 누가, 어떤 방식으로 하게 될지 상상.\n\n");

		prompt.append("## 3. 연애의 모습: 두 사람만의 케미스트리\n");
		prompt.append("연인이 된 후 두 사람의 데이트 스타일, 애정 표현 방식, 스킨십 성향 분석.\n");
		prompt.append("성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형) (오행, 십성 조화).\n");
		prompt.append("함께 있을 때의 에너지 (편안함 vs 긴장감, '관계의 시너지' vs 소모).\n");
		prompt.append("연애 중 각자가 상대방에게 어떤 영향을 주고받는지.\n\n");

		prompt.append("## 4. 갈등과 성장: 관계의 시련과 극복\n");
		prompt.append("두 사람 사이에 발생할 수 있는 주요 갈등의 원인(성격 차이, 가치관 충돌 등) 예측 (지지 충/형 등 활용).\n");
		prompt.append("각자의 갈등 해결 방식과, 이 커플이 갈등을 통해 어떻게 '성장'할 수 있을지 조언.\n");
		prompt.append("관계의 위기(권태기, 이별 가능성)가 올 수 있는 시점과 극복 가능성.\n\n");

		prompt.append("## 5. 미래의 가능성: 장기 연애와 그 너머\n");
		prompt.append("이 커플의 장기 연애 가능성 (1년, 3년, 5년 후 모습 예측).\n");
		prompt.append("결혼까지 이어질 확률과 결혼 적합도 분석.\n");
		prompt.append("만약 결혼한다면 어떤 부부의 모습일지 예측 (역할 분담, 관계 유지 비결).\n");
		prompt.append("두 사람의 인연에 대한 최종적인 조언과 응원.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, person1Name, person2Name);

		return prompt.toString();
	}

	// ==================== [수정] 7. 아이돌 궁합 프롬프트 (혜안 적용) ====================
	private String createIdolCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시
		prompt.append("### ⚠️ 매우 중요: 3인칭 서술 ###\n");
		prompt.append("- 이 분석은 '%s'와 '%s'라는 제3자(아이돌)들에 대한 것입니다.\n");
		prompt.append(
			"- 절대로 2인칭(당신들)을 사용하지 말고, **'두 사람은', '%s님은', '%s님은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자들 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 아이돌: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);
		prompt.append("\n--- 두 번째 아이돌: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 4. 분석 요청
		prompt.append("\n### 6. [아이돌 궁합 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 두 아이돌(%s님, %s님)의 '관계 서사'를 팬의 관점에서 아래 **5가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n",
			person1Name, person2Name));
		prompt.append("각 주제를 '혜안'의 서사적 스타일로 깊이 있게 다루어 주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"'%s'님과 '%s'님의 '인생 지도'가 만나는 지점을 보니... [두 사람의 일간 비유]처럼 흥미로운 '관계의 시너지'가 예상되네요.\" 로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("## 1. 첫인상과 친밀도 형성 과정\n");
		prompt.append("두 사람이 처음 만났을 때 서로에게 느꼈을 첫인상 (호감/경계/무관심 등)과 그 이유 (일간, 오행 관계).\n");
		prompt.append("서로 친해지는 속도와 방식 예측 (누가 먼저 다가갈까?).\n");
		prompt.append("무대 밖에서 개인적인 친구로 발전할 가능성과, 어떤 유형의 우정(깊은 교감 vs 가벼운 친분)이 될지 분석.\n\n");

		prompt.append("## 2. 무대 위 시너지와 팀워크\n");
		prompt.append("함께 공연하거나 활동할 때 나타나는 에너지 조화 ('관계의 시너지' vs 부조화) 분석.\n");
		prompt.append("서로의 강점을 살려주고 약점을 보완해주는 관계인지, 혹은 경쟁 관계가 될 가능성이 있는지 (십성 관계).\n");
		prompt.append("유닛, 듀엣 등 협업 프로젝트에 적합한 조합인지 평가.\n\n");

		prompt.append("## 3. 성격 궁합과 잠재적 갈등 요소\n");
		prompt.append("두 사람의 기본적인 성격 궁합 (유사점 vs 차이점, 서로에게 배우는 점).\n");
		prompt.append("사주 구조상(지지 '충/형' 등) 어떤 부분에서 의견 충돌이나 갈등이 발생하기 쉬운지 예측.\n");
		prompt.append("갈등 발생 시 각자의 대처 방식과, 관계를 건강하게 유지하기 위한 조언.\n\n");

		prompt.append("## 4. [팬심 저격] 로맨스 가능성 탐구\n");
		prompt.append("팬들의 상상력을 자극할 만한, 두 사람 사이에 연애 감정이 싹틀 가능성 분석 (이성적 끌림 요소).\n");
		prompt.append("만약 연인이 된다면 어떤 스타일의 커플이 될지 예측 (달달함 vs 친구 같음 등).\n");
		prompt.append("실제 연애로 이어질 경우 장기적인 관계 유지 가능성 평가.\n\n");

		prompt.append("## 5. 장기적 인연\n");
		prompt.append("서로의 인생에 긍정적인 영향을 주는 귀인(貴人) 관계가 될 수 있는지.\n");
		prompt.append("일시적인 인연인지, 혹은 오랫동안 서로에게 힘이 되어줄 인연인지에 대한 최종 전망.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, person1Name, person2Name);

		return prompt.toString();
	}

	// ==================== [수정] 8. 삼각관계 프롬프트 (혜안 적용) ====================
	private String createTriangleRelationshipPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시
		prompt.append("### ⚠️ 매우 중요: 3인칭 서술 ###\n");
		prompt.append("- 이 분석은 '%s'와 '%s'라는 제3자들에 대한 것입니다.\n");
		prompt.append(
			"- 절대로 2인칭(당신들)을 사용하지 말고, **'두 사람은', '%s님은', '%s님은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자들 정보 주입
		prompt.append("\n### 5. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 사람: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);
		prompt.append("\n--- 두 번째 사람: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 4. 분석 요청
		prompt.append("\n### 6. [삼각관계 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 두 사람(%s님, %s님)의 '관계 서사'에 제3자가 개입할 가능성과 그로 인한 **5가지 드라마틱한 국면**을 깊이 있게 작성해주세요.\n",
			person1Name, person2Name));
		prompt.append(
			"두 사람의 관계 취약점, 감정 변화, 역학 구도에 초점을 맞춰 '혜안'의 스타일로 구체적으로 서술해주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님과 %s님의 '두 지도의 만남'을 보니, 기본적인 끌림과 함께 '관계의 역동성'을 불러일으키는 지점도 보이네요...\" 로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("## 1. 두 사람의 기본 관계 방정식: 끌림과 균열의 씨앗\n");
		prompt.append("두 사람이 서로에게 느끼는 매력과 기본적인 관계의 강점 분석.\n");
		prompt.append("겉으로 드러나지 않을 수 있는 관계의 취약점 또는 불만 요소 예측 (지지 '충/형', 오행 불균형 등).\n");
		prompt.append("제3자가 비집고 들어올 수 있는 '틈'은 어디에 있는지 분석.\n\n");

		prompt.append("## 2. 제3자의 등장: 어떤 인물이, 왜 끼어드는가?\n");
		prompt.append("%s님 또는 %s님이 끌리기 쉬운 제3자의 사주적 특징(일간, 오행, 십성 등) 예측.\n");
		prompt.append("두 사람 중 누가 먼저 마음이 흔들리거나 관계에 변화를 줄 가능성이 높은지 분석.\n");
		prompt.append("제3자의 등장이 두 사람의 관계에 미치는 초기 영향력 예측.\n\n");

		prompt.append("## 3. 질투와 경쟁: 감정의 소용돌이\n");
		prompt.append("삼각관계 상황에서 %s님과 %s님이 각각 보일 수 있는 질투의 양상과 강도 분석 (겁재, 비견 등 활용).\n");
		prompt.append("누가 관계의 주도권을 쥐려 하거나 혹은 더 집착하는 모습을 보일지 예측.\n");
		prompt.append("경쟁 구도 속에서 각자가 사용할 수 있는 전략이나 행동 패턴 분석.\n\n");

		prompt.append("## 4. 관계의 역학: 누가 선택하고 누가 상처받는가?\n");
		prompt.append("삼각관계 구도에서 누가 심리적으로 우위에 서거나 선택하는 입장이 될 가능성이 높은지 분석.\n");
		prompt.append("반대로 누가 더 큰 상처를 받거나 관계에서 밀려날 가능성이 높은지 예측.\n");
		prompt.append("이 복잡한 관계가 안정될 가능성 vs 파국으로 치달을 가능성 평가.\n\n");

		prompt.append("## 5. 예상 시나리오와 최종 조언\n");
		prompt.append("이 삼각관계가 맞이할 가능성이 높은 결말 시나리오 1~2가지 제시 (명리학적 근거 포함).\n");
		prompt.append("각 당사자(%s님, %s님, 그리고 가상의 제3자)가 이 상황을 현명하게 대처하기 위한 조언.\n");
		prompt.append("관계의 복잡성 속에서도 각자가 '성장'할 수 있는 방법에 대한 메시지로 마무리.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, person1Name, person2Name);

		return prompt.toString();
	}

	// ==================== [수정] 기본 궁합 프롬프트 (혜안 적용) ====================
	private String createCompatibilityPrompt(String person1Name,
		ManseryeokCalculationResponse person1Saju, String person2Name,
		ManseryeokCalculationResponse person2Saju) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시
		prompt.append("### ⚠️ 매우 중요: 3인칭 서술 ###\n");
		prompt.append("- 이 분석은 '%s'와 '%s'라는 제3자들에 대한 것입니다.\n");
		prompt.append(
			"- 절대로 2인칭(당신들)을 사용하지 말고, **'두 사람은', '%s님은', '%s님은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자들 정보 주입 (요약본 사용)
		prompt.append("### 5. 분석 대상자 요약 정보 ###\n");
		prompt.append("--- 첫 번째 사람: ").append(person1Name).append(" ---\n");
		appendPersonInfoToPrompt(prompt, person1Name, person1Saju); // 요약 정보 주입
		prompt.append("\n--- 두 번째 사람: ").append(person2Name).append(" ---\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju); // 요약 정보 주입

		// 4. 분석 요청
		prompt.append("\n### 6. [기본 궁합 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 두 사람(%s님, %s님)의 '기본 관계 서사'를 아래 **5가지 핵심 주제**에 대해 깊이 있게 작성해주세요.\n",
			person1Name, person2Name));
		prompt.append(
			"각 주제를 '혜안'의 서사적 스타일로 깊이 있게 다루어 주세요. **분량 제한은 없습니다.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님과 %s님의 '두 지도의 만남'을 보니, [두 사람의 일간 비유]처럼 흥미로운 '관계의 시너지'가 예상되네요.\" 로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("## 1. 서로에게 끌리는 첫 만남의 에너지\n");
		prompt.append("- 두 사람의 일간(日干) 오행 관계와 첫인상 분석 (서로에게 어떤 매력을 느낄까?).\n");
		prompt.append("- 각자의 외적인 분위기('신살', 12운성 등)가 서로에게 어떻게 작용하는지.\n");
		prompt.append("- 관계 초반의 발전 속도 예측 (빠르게 가까워질까? 서서히 알아갈까?).\n\n");

		prompt.append("## 2. 함께할 때의 조화와 보완 ('오행 조화')\n");
		prompt.append(
			"- 각자의 오행 분포를 비교하여, 서로의 부족한 기운을 채워주는 '상생' 관계인지, 혹은 에너지가 부딪히는 '상극' 관계인지 심층 분석.\n");
		prompt.append("- 함께 있을 때 느끼는 감정(안정감/편안함 vs 긴장감/불편함) 예측.\n");
		prompt.append("- 서로의 성장을 돕는 긍정적 측면과, 주의해야 할 부정적 측면 설명.\n\n");

		prompt.append("## 3. 현실적인 관계에서의 역할과 갈등 (십성, '관계의 역동성')\n");
		prompt.append("- 각자의 십성(十星) 분포를 통해 관계에서의 역할 분담 예측 (주도/보조, 표현/수용 등).\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간 합(合)/충(沖)/형(刑) 관계 분석: 어떤 부분에서 조화를 이루고, 어떤 부분에서 '관계의 역동성'(갈등)이 발생하기 쉬운지.\n");
		prompt.append("- 예상되는 주요 갈등 유형과 이를 '성장의 계기'로 삼기 위한 구체적인 조언.\n\n");

		prompt.append("## 4. 관계 발전을 위한 맞춤 조언\n");
		prompt.append("- 서로의 장점을 더욱 살리고 단점을 보완해주기 위한 구체적인 소통 방식이나 행동 지침 2~3가지 제안.\n");
		prompt.append("- 두 사람이 함께 성장하고 행복한 관계를 오래 유지하기 위해 각자 노력해야 할 부분.\n\n");

		prompt.append("## 5. 총평: 관계의 본질과 미래\n");
		prompt.append("- 두 사람 관계의 핵심적인 특징과 잠재력을 한두 문장으로 요약.\n");
		prompt.append("- 행복한 관계를 위한 가장 중요한 조언을 강조하며 긍정적으로 마무리.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, person1Name, person2Name);

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
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getFirst().getKorean(),
				jijanggan.getFirst().getFiveCircle(),
				jijanggan.getFirst().getTenStar() != null ? jijanggan.getFirst().getTenStar() : "?",
				// ⭐ 십성 추가
				jijanggan.getFirst().getRate()));
		}
		if (jijanggan.getSecond() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getSecond().getKorean(),
				jijanggan.getSecond().getFiveCircle(),
				jijanggan.getSecond().getTenStar() != null ? jijanggan.getSecond().getTenStar()
					: "?",  // ⭐ 십성 추가
				jijanggan.getSecond().getRate()));
		}
		if (jijanggan.getThird() != null) {
			jijangganElements.add(String.format("%s%s(%s,%d%%)",
				jijanggan.getThird().getKorean(),
				jijanggan.getThird().getFiveCircle(),
				jijanggan.getThird().getTenStar() != null ? jijanggan.getThird().getTenStar() : "?",
				// ⭐ 십성 추가
				jijanggan.getThird().getRate()));
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

		// 지지 자체의 오행과 십성 (1점)
		addElementCount(ohaengCounts, sipseongCounts, ground, 1.0);

		// 지장간 계산 (가중치 적용)
		JijangganInfo jijanggan = ground.getJijanggan();
		if (jijanggan != null) {
			if (jijanggan.getFirst() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts,
					jijanggan.getFirst());  // ⭐ 십성 카운트 추가
			}
			if (jijanggan.getSecond() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts, jijanggan.getSecond());
			}
			if (jijanggan.getThird() != null) {
				addJijangganElement(ohaengCounts, sipseongCounts, jijanggan.getThird());
			}
		}
	}

	private void addJijangganElement(Map<String, Double> ohaengCounts,
		Map<String, Integer> sipseongCounts, JijangganElement element) {

		// 오행 카운팅 (기존)
		String ohaeng = element.getFiveCircle();
		if (ohaeng != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			ohaengCounts.compute(ohaeng, (k, v) -> (v == null ? 0 : v) + weight);
		}

		// ⭐ 십성 카운팅 (추가)
		String tenStar = element.getTenStar();
		if (tenStar != null && element.getRate() != null) {
			double weight = element.getRate() / 100.0;
			// 반올림하여 정수로 카운트 (0.1개 이상이면 카운팅)
			int intWeight = (int) Math.round(weight);
			if (intWeight > 0) {
				sipseongCounts.compute(tenStar, (k, v) -> (v == null ? 0 : v) + intWeight);
			}
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
				prompt.append(String.format("    - 초기(%d%%): %s%s (십성: %s)\n",  // ⭐ 십성 추가
					jijanggan.getFirst().getRate() != null ? jijanggan.getFirst().getRate() : 0,
					jijanggan.getFirst().getKorean() != null ? jijanggan.getFirst().getKorean()
						: "?",
					jijanggan.getFirst().getFiveCircle() != null ? jijanggan.getFirst()
						.getFiveCircle() : "?",
					jijanggan.getFirst().getTenStar() != null ? jijanggan.getFirst().getTenStar()
						: "?"));
			}
			if (jijanggan.getSecond() != null) {
				prompt.append(String.format("    - 중기(%d%%): %s%s (십성: %s)\n",
					jijanggan.getSecond().getRate() != null ? jijanggan.getSecond().getRate() : 0,
					jijanggan.getSecond().getKorean() != null ? jijanggan.getSecond().getKorean()
						: "?",
					jijanggan.getSecond().getFiveCircle() != null ? jijanggan.getSecond()
						.getFiveCircle() : "?",
					jijanggan.getSecond().getTenStar() != null ? jijanggan.getSecond().getTenStar()
						: "?"));
			}
			if (jijanggan.getThird() != null) {
				prompt.append(String.format("    - 말기(%d%%): %s%s (십성: %s)\n",
					jijanggan.getThird().getRate() != null ? jijanggan.getThird().getRate() : 0,
					jijanggan.getThird().getKorean() != null ? jijanggan.getThird().getKorean()
						: "?",
					jijanggan.getThird().getFiveCircle() != null ? jijanggan.getThird()
						.getFiveCircle() : "?",
					jijanggan.getThird().getTenStar() != null ? jijanggan.getThird().getTenStar()
						: "?"));
			}
		}
	}

	/**
	 * 프롬프트에 신살 정보를 요약하여 추가하는 헬퍼 메서드
	 */
	private void appendSinsalAnalysis(StringBuilder prompt, SajuInfo saju) {
		if (saju == null) {
			return;
		}

		List<String> allSinsal = new ArrayList<>();

		if (saju.getSinsalInfo() != null) {
			// ⭐ 모든 신살 포함 (필터링 제거)
			saju.getSinsalInfo().forEach((pillarName, sinsals) -> {
				if (sinsals != null && !sinsals.isEmpty()) {
					// 각 신살에 기둥 위치 표시 (예: "년주:도화살")
					sinsals.stream()
						.filter(s -> s != null && !s.isEmpty())
						.forEach(s -> allSinsal.add(pillarName + ":" + s));
				}
			});
		}

		// 특수 신살 추가
		if (Boolean.TRUE.equals(saju.getHasGoegang())) {
			allSinsal.add("일주:괴강살");
		}
		if (Boolean.TRUE.equals(saju.getHasBaekho())) {
			allSinsal.add("일주:백호대살");
		}

		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("- 신살: %s\n",
				String.join(", ", allSinsal)));
		} else {
			prompt.append("- 신살: 해당 없음\n");
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
