package com.mansereok.server.domain.interpret.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.domain.interpret.dto.response.GptCompatibilityResponse;
import com.mansereok.server.domain.interpret.dto.response.GptSajuResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.UserService;
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
	private final OgImageGenerationService ogImageGenerationService;
	private final DiscordNotificationService discordNotificationService; // 👈 Slack -> Discord

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
		OgImageGenerationService ogImageGenerationService,
		DiscordNotificationService discordNotificationService
	) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
		this.userService = userService;
		this.ogImageGenerationService = ogImageGenerationService;
		this.discordNotificationService = discordNotificationService;
	}

	@Async("gptTaskExecutor")
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
			User user = userService.findByUsername(username);
			String birthdate = response.getInput().getSolarDate().toString();

			discordNotificationService.sendInterpretationRequestNotification(
				name,
				user.getEmail(),
				birthdate,
				subcategoryId
			);
		} catch (Exception e) {
			log.error("Discord 사주 요청 알림 전송 실패", e);
			// 알림 실패해도 작업은 계속 진행
		}

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

			String content = extractContentFromResponseGpt5(gptResponse);
			log.info("✅ content 추출 완료 - 길이: {}", content.length());

			GptSajuResponse gptData = objectMapper.readValue(content, GptSajuResponse.class);
			log.info("✅ gptData 파싱 완료");
			log.info("✅ fullAnalysis 길이: {}", gptData.getFullAnalysis().length());
			log.info("✅ summary 길이: {}", gptData.getSummary().length());

			log.info("✅ gptData.getFullAnalysis(): " + gptData.getFullAnalysis());
			log.info("✅ gptData.getSummary(): " + gptData.getSummary());

			User user = userService.findByUsername(username);
			log.info("사용자 id: " + user.getId());

			result.completeInterpretation(
				gptData.getFullAnalysis(),
				gptData.getSummary()
			); // complete 포함 .

			Result savedResult = resultRepository.save(result);

			ogImageGenerationService.generateAndUploadOgImage(savedResult);

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

	@Async("gptTaskExecutor")
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

			try {
				String person1Birthdate = person1Response.getInput().getSolarDate().toString();
				String person2Birthdate = person2Response.getInput().getSolarDate().toString();

				discordNotificationService.sendCompatibilityRequestNotification(
					person1Name,
					person1Birthdate,
					person2Name,
					person2Birthdate
				);
			} catch (Exception e) {
				log.error("Discord 궁합 요청 알림 전송 실패", e);
				// 알림 실패해도 작업은 계속 진행
			}

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

			String content = extractContentFromResponseGpt5(gptResponse);
			log.info("✅ content 추출 완료 - 길이: {}", content.length());

			GptCompatibilityResponse gptData = objectMapper.readValue(content,
				GptCompatibilityResponse.class);
			log.info("✅ gptData 파싱 완료");
			log.info("✅ interpretation 길이: {}", gptData.getInterpretation().length());
			log.info("✅ score: {}", gptData.getScore());
			log.info("✅ summary 길이: {}", gptData.getSummary().length());

			log.info("GPT 응답. gptData.getSummary(): " + gptData.getSummary());

			result.completeInterpretation(
				gptData.getInterpretation(),
				gptData.getScore(),
				gptData.getSummary()
			);

			CompatibilityResult savedResult = compatibilityResultRepository.save(result);
			log.info("CompatibilityResult 분석 결과 저장 및 상태 COMPLETED 변경 완료: resultId={}",
				savedResult.getId());

			ogImageGenerationService.generateAndUploadOgImage(savedResult);
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
		prompt.append("1.. **서사적 스토리텔링**: 사주 데이터를 절대 나열하지 않고, '한 편의 이야기' 속에 자연스럽게 녹여냅니다.\n");
		prompt.append(
			"2. **자연스러운 전문가 어조**: '해요체'를 기본으로 쓰되, 전문 정보 전달 시 '입니다' 체를 혼용하여 신뢰감과 친근함을 모두 전달합니다.\n");
		prompt.append(
			"3. **깊이 있는 통찰**: 각 주제를 피상적으로 다루지 않고, 명리학적 근거를 바탕으로 심층 분석하되 쉽고 재미있게 풀어서 써주세요.\n\n");

		prompt.append("### 2. 절대 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 운명론적 단정, 데이터 나열, AI/시스템 노출, AI티가 나면 절대 안됨, 차갑거나 권위적인 어조 금지.\n\n");

		prompt.append("### 3. 작성 스타일 (공통) ###\n");
		prompt.append("- **깊이 우선**ㅇ: 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
		prompt.append("- **비유 활용**: 명리학적 개념을 쉽고 재밌게 풀어서 설명해주세요.\n");
	}

	private void appendHyeanCompatibilityPersonaHeader(StringBuilder prompt) {
		prompt.append("### 0. 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 경력의 사주명리학 대가이자, '관계 서사 상담가' 혜안(慧眼)입니다.\n");
		prompt.append(
			"당신은 두 사람의 고유한 인생 지도(사주팔자)가 어떻게 서로 엮이고 영향을 주는지, 그 '관계의 서사'를 깊이 있게 해석합니다.\n");

		prompt.append("### 1. 핵심 분석 원칙 (Core Principles) ###\n");
		prompt.append(
			"1. **서사적 스토리텔링**: 사주 데이터를 나열하지 않고, '두 사람의 이야기' 속에 자연스럽게 녹여내어 사주를 쉽고 재미있게 풀어냅니다.\n");
		prompt.append(
			"4. **자연스러운 전문가 어조**: '해요체'를 기본으로 쓰되, 전문 정보 전달 시 '입니다' 체를 혼용하여 신뢰감과 친근함을 모두 전달합니다.\n");
		prompt.append("5. **깊이 있는 통찰**: 관계를 피상적으로 다루지 않고, 명리학적 근거를 바탕으로 심층 분석합니다.\n\n");

		prompt.append("### 2. 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 데이터 나열, 글에서 AI티가 나면 절대 안됨\n\n");

		prompt.append("### 3. 작성 스타일 (공통) ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");
	}

	private void appendSajuJsonResponseFormat(StringBuilder prompt, String name) {
		prompt.append("\n\n### 9. [최종 출력 형식] (JSON) ###\n");
		prompt.append("위에서 요청된 모든 분석을 완료한 후, **반드시 markdown 감싸기 없이 순수한 JSON 형식으로만** 응답해주세요.\n");
		prompt.append(
			"**fullAnalysis** 값에는 위에서 요청한 모든 상세 분석 내용을 **목록 기호 없이 물 흐르듯 자연스럽게 이어진 하나의 긴 텍스트**로 담아야 합니다.\n");

		prompt.append("--- [fullAnalysis 작성 규칙] ---\n");
		prompt.append("1. **(매우 중요)** 프롬프트에 `##`로 시작하는 주제(제목)가 있으면, `##` 기호는 **절대 출력하지 마세요.**\n");
		prompt.append("2. 대신, 그 주제(제목) 텍스트를 **대괄호(`[]`)**로 감싸고, 그 뒤에 **줄바꿈(\\n)**을 한 번만 추가해주세요.\n");
		prompt.append("   (예시: `## 1. 핵심 성격` -> [핵심 성격]\\n)\n");
		prompt.append(
			"3. **(매우 중요)** 프롬프트에 `**`로 감싸진 단어(강조)는, `**` 기호 없이 **그냥 텍스트**로만 출력해주세요. (굵게 표시 금지)\n");
		prompt.append("4. 한 문단이 6~7줄을 넘으면 안됨.\n");
		prompt.append("5. 목록 기호(-, *, 1.) 사용 금지, 자연스러운 문장으로 연결\n");
		prompt.append(
			"6. **(카드 UI용)** 가독성을 위해, 본문 내용 4~5 문장마다 **줄바꿈을 두 번(\\n\\n)** 하여 다음 카드로 넘어가는 것처럼 문단을 나눠주세요.\n");

		prompt.append("--- [summary 말투 규칙 - 매우 중요!!!] ---\n");
		prompt.append("**summary는 '혜안' 페르소나를 완전히 무시하고, 아래 규칙만 100% 따라야 합니다.**\n\n");

		prompt.append("🎯 **필수 규칙 (절대 엄수)**\n");
		prompt.append(
			"1. **페르소나 (가장 중요)**: 너는 내 **찐친(best friend)**이야. 완전 반말로, 핵심만 콕 집어서 재치있게(witty) 말해줘. **딱딱한 정보 요약이 절대 아니야.**\n");
		prompt.append("2. **주제 (총평)**: 이 사람 사주에 대한 **'핵심 총평'**을 해줘. 성격, 재능, 매력 같은 거 팍팍 찝어서.\n");
		prompt.append("3. **줄바꿈**: 한 문장이 끝나면 **반드시 줄바꿈(\\n)** 해주고, 마침표는 찍지 마.\n");
		prompt.append("4. **분량**: 총 220자 이내.\n");

		prompt.append("✅ **자연스러운 예시 (이런 느낌!)**\n");
		prompt.append("예시1: (성격)\n");
		prompt.append("\"겉으론 조용? 속은 완전 불도저 그 자체\n");
		prompt.append("고집 개셈 마이웨이 장난 아님\n");
		prompt.append("꽂히면 앞만 보고 달림\n");
		prompt.append("현실 계산은 또 빨라서 절대 손해 안 봐\n");
		prompt.append("한마디로 '차가운 심장을 가진 폭주기관차'랄까\"\n\n");

		prompt.append("{\n");
		prompt.append(
			"  \"fullAnalysis\": \"<여기에 상세 분석 전체 내용을 작성. 상세 분석 전체 내용 작성할때 보기 편하게 문단을 잘 나눠야함>\",\n");
		prompt.append(
			"  \"summary\": \"<여기에 '올바른 예시'처럼 '~임' 말투를 사용하고, 문장 끝마다 '\\n'으로 줄바꿈된 200자 이내 요약본 작성>\"\n");
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

		prompt.append("--- [fullAnalysis 작성 규칙] ---\n");
		prompt.append("1. **(매우 중요)** 프롬프트에 `##`로 시작하는 주제(제목)가 있으면, `##` 기호는 **절대 출력하지 마세요.**\n");
		prompt.append("2. 대신, 그 주제(제목) 텍스트를 **대괄호(`[]`)**로 감싸고, 그 뒤에 **줄바꿈(\\n)**을 한 번만 추가해주세요.\n");
		prompt.append("   (예시: `## 첫 만남` -> [첫 만남]\\n)\n");
		prompt.append(
			"3. **(매우 중요)** 프롬프트에 `**`로 감싸진 단어(강조)는, `**` 기호 없이 **그냥 텍스트**로만 출력해주세요. (굵게 표시 금지)\n");
		prompt.append("4. 한 문단이 6~7줄을 넘지 않도록 적절히 끊어서 작성\n");
		prompt.append("5. 목록 기호(-, *, 1.) 사용 금지, 자연스러운 문장으로 연결\n");
		prompt.append(
			"6. **(카드 UI용)** 가독성을 위해, 본문 내용 4~5 문장마다 **줄바꿈을 두 번(\\n\\n)** 하여 다음 카드로 넘어가는 것처럼 문단을 나눠주세요.\n");

		prompt.append("--- [summary 말투 규칙 - 매우 중요!!!] ---\n");
		prompt.append("**summary는 '혜안' 페르소나를 완전히 무시하고, 아래 규칙만 100% 따라야 합니다.**\n\n");

		prompt.append("🎯 **필수 규칙 (절대 엄수)**\n");
		prompt.append(
			"1. **페르소나 (가장 중요)**: 너는 내 **찐친(best friend)**이야. 완전 반말로, 두 사람 궁합을 재치있게(witty) 팩폭해줘.\n");
		prompt.append(
			"2. **주제 (총평)**: 두 사람의 **'궁합 총평'**을 해줘. 둘의 케미, 제일 조심할 거, 미래 예측 같은 거 팍팍 찝어서.\n");
		prompt.append("3. **줄바꿈**: 한 문장이 끝나면 **반드시 줄바꿈(\\n)** 해주고, 마침표는 찍지 마.\n");
		prompt.append("4. **분량**: 총 220자 이내.\n");

		prompt.append("✅ **자연스러운 예시 (이런 느낌!)**\n");
		prompt.append("예시1: (궁합)\n");
		prompt.append("\"물 만난 고기? 아니 물 만난 밭이네\n");
		prompt.append("임수 강물이 기토 밭을 싹 적셔주니 찰떡궁합\n");
		prompt.append("근데 둘 다 속도 조절 못하면 큰일남\n");
		prompt.append("남자가 좀 달래주고 여자가 템포 맞추면\n");
		prompt.append("2029년쯤엔 진짜 결혼각 잡힐 각\"\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 종합 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<상세 궁합 분석 내용>\",\n");
		prompt.append(
			"  \"summary\": \"<여기에 '올바른 예시'처럼 '~임' 말투를 사용하고, 문장 끝마다 '\\n'으로 줄바꿈된 200자 이내 요약본 작성>\"\n");
		prompt.append("}\n");
	}

	// ==================== 프롬프트 라우팅 메서드 (기존 유지) ====================
	private String createPromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {

		return switch (subcategoryId.intValue()) {
			case 1 -> createLifeOverallPrompt(name, response);
			case 2 -> createPersonalityAnalysisPrompt(name, response);
			case 3 -> createCareerAptitudePrompt(name, response);
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
			case 4, 6 -> createLoveStoryPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 7 -> createIdolCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 8 -> createTriangleRelationshipPrompt(person1Name, person1Response, person2Name,
				person2Response);
			default -> createCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
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

		prompt.append("## 타고난 본성과 성격\n");
		prompt.append(
			" 일간, 월지, 오행 분포, 십성 구조를 종합하여 %s님의 핵심 기질과 성격 형성 과정을 '비유'를 통해 깊이 있게 분석해주세요.\n");
		prompt.append("지장간에 숨겨진 '내면의 DNA'와 무의식적 동기까지 파헤쳐, 다층적인 성격 구조를 설명해주세요.\n\n");

		prompt.append("## 직업과 사회적 성공의 길\n");
		prompt.append(
			" %s님의 핵심 재능(십성, 신살 등을 참고)은 무엇이며, 어떤 분야(구체적 직업군 2~3개 제시)에서 가장 빛을 발할 수 있는지 명확히 제시해주세요.\n");

		prompt.append("## 재물운의 흐름과 경제적 안정\n");
		prompt.append(" %s님의 타고난 재물운, 앞으로 어떻게 해야하는지, 어떻게 노력해야하는지, 투자 성향, 투자 어떻게 해야하는지 등\n");

		prompt.append("## 연애와 결혼의 인연\n");
		prompt.append("%s님의 연애 스타일, 매력 포인트, 이상적인 배우자상('일지' 비유 활용)을 상세히 그려주세요.\n");
		prompt.append("%s님이 끌리는 스타일, 본인의 이상형, 실제로 이상형을 만나는가 ?\n");
		prompt.append("연애/결혼 가능성이 높은 시기와 만남의 방식 예측, 행복한 관계를 오래 유지하기 위한 비결 조언.\n\n");

		prompt.append("## 대운과 세운 - 인생의 큰 파도\n");
		prompt.append(
			"**[현재 대운 집중 분석]** 지금 겪고 있는 현재 대운(10년)은 %s님 인생에서 어떤 '챕터'이며, 이 시기의 주요 과제와 기회는 무엇인지 집중 분석해주세요. 그리고 어떻게 행동해야하는지까지 분석 해주세요.\n");
		prompt.append(
			"2025년 을사년 세운이 %s님에게 미치는 영향을 직업, 재물, 연애, 건강 측면에서 구체적으로 분석해주세요.\n\n");
		prompt.append(
			"2026년 병오년 세운이 %s님에게 미치는 영향을 직업, 재물, 연애, 건강 측면에서 구체적으로 분석해주세요.\n\n");

		prompt.append("## 인생 전체를 위한 조언\n");
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

		prompt.append("## 핵심 성격 키워드와 그 근원\n");
		prompt.append(
			"%s님을 가장 잘 나타내는 핵심 성격 키워드 3가지를 선정하고, 각 키워드가 어떤 사주 요소(일간, 월지, 오행, 십성 등)에서 비롯되었는지 '비유'를 통해 명확한 근거와 함께 설명해주세요. 사주 용어를 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append("이 핵심 성격이 삶 전반에 어떻게 긍정적/부정적으로 발현되는지 구체적인 예시를 들어 분석해주세요.\n\n");

		prompt.append("## 겉모습(페르소나) vs 진짜 내면\n");
		prompt.append(
			"사회적으로 보여지는 모습(천간 십성)과 실제 내면의 모습(지지, '지장간 DNA') 사이의 유사점과 차이점을 분석해주세요.\n");
		prompt.append("만약 차이가 크다면, 그 이유는 무엇이며 어떤 상황에서 내면의 모습이 드러나는지 설명해주세요.\n");
		prompt.append("이 두 모습의 조화를 이루기 위한 방법을 쉽고 재미있게 풀어서 조언해주세요\n\n");

		prompt.append("## 사고방식, 가치관, 그리고 강점과 약점\n");
		prompt.append(
			"십성 분포를 통해 %s님의 주요 사고 패턴(논리/직관, 감성/이성 등)과 중요하게 생각하는 가치관(명예/재물/안정 등)을 쉽고 재미있게 분석해주세요.\n");
		prompt.append(
			"성격적인 강점 3가지와 약점(개선점) 2가지를 명확히 제시해주세요. 쉽고 재미있게 풀어서 설명해주세요. \n\n");

		prompt.append("## 인간관계 스타일 (관계 유형별)\n");
		prompt.append("친구, 동료(상사/부하 포함), 연인, 가족 등 주요 관계 유형별로 %s님이 관계를 맺는 특징적인 방식과 태도를 분석해주세요.\n");
		prompt.append("각 관계에서 발생할 수 있는 갈등 유형과 이를 원만하게 해결하는 방법을 조언해주세요.\n");
		prompt.append("어떤 유형의 사람들과 잘 맞고, 어떤 유형과 어려움을 겪을 수 있는지 설명해주세요.\n\n");

		prompt.append("## 자기 성장과 행복을 위한 조언\n");
		prompt.append("%s님의 성격적 특성을 고려했을 때, 삶의 만족도와 행복감을 높이기 위해 무엇에 집중하면 좋을지 조언해주세요.\n");
		prompt.append("타고난 성격을 바탕으로 더 나은 나로 성장하기 위한 장기적인 방향성을 제시하며 따뜻하게 마무리해주세요.\n\n");

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

		// 3. [핵심 수정] 분석 요청: '-', 목차 제거 + 자연스러운 흐름 강조
		prompt.append("### [직업 적성] 심층 분석 요청 ###\n\n");
		prompt.append(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '직업과 재능'에 대한 이야기를 들려주세요.\n\n");

		prompt.append(
			"**[가장 중요!]** 아래 지침을 '반드시' 따르세요:\n");
		prompt.append(
			"1. **(필수!) 자연스러운 글쓰기:** **'절대로' '-', '*', '1.' 같은 목록 기호를 사용하지 마세요.** 모든 문장을 이어서 작성하고, 접속사나 부드러운 표현을 사용해서 **마치 옆에서 대화하듯 물 흐르듯 자연스럽게** 글이 이어지도록 하세요. (AI 티 나는 딱딱한 보고서 형식 절대 금지!)\n");
		prompt.append(
			"2. **(필수!) 쉽고 재미있게 사주 해석을 풀어서 설명해주세요.\n");
		prompt.append(
			"3. **(내용)** 아래 질문들에 대한 답을 **자연스러운 이야기 속에 녹여내세요.** (딱딱한 목차 구분 절대 금지!)\n");
		prompt.append(
			"%s님의 본질적인 성향과 재능은 무엇인가요?\n");
		prompt.append(
			"%s님의 '숨겨진 보물 상자'(지장간)에는 어떤 잠재력의 씨앗이 있으며, 어떤 직업적 재능으로 피어날 수 있을까요? 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append(
			"%s님의 가장 강력한 '핵심 도구'(십성)는 무엇이며, 어떤 직업적 성향을 나타내나요?\n");
		prompt.append(
			"%s님은 '혼자 빛나는 별'인가요, '함께 어우러지는 숲'인가요? (독립 vs 조직, 근무 형태)\n");
		prompt.append(
			"%s님에게 잘어울리는 직업 분야는 무엇인가요? (최대 3가지, 구체적이지만 어려운 비즈니스 용어 나열 절대 금지)\n");
		prompt.append(
			"%s님은 어떤 방식으로 부를 쌓아갈 수 있을까요? ('수확의 계절' 구체적인 시기 포함)\n");
		prompt.append(
			"%s님의 커리어 '에너지 파도'(12운성)는 지금 어떤 상태이며, '커리어 챕터'(대운)는 어떻게 흘러가나요?\n");
		prompt.append(
			" %s님의 '성공 히든카드'(길신)와 '다루기 힘든 명검'(흉살)은 무엇이며, 어떻게 활용해야 할까요?\n");
		prompt.append(
			"4. **(마무리)** 글 마지막에는 %s님의 커리어 여정을 위한 따뜻한 조언과 응원의 메시지를 담아 자연스럽게 마무리해주세요.\n\n");

		prompt.append("【분석 시작】\n");
		prompt.append(String.format(
			"\"%s %s에 태어나신 %s 님은 [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\" 로 시작해주세요.\n\n",
			formattedDate, formattedTime, name,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("【분석 시작】\n");

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

		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";

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

		// 4. [⭐️ 수정된 분석 요청]
		prompt.append("\n### 6. [최애 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 아이돌 '%s'님의 사주를 팬의 관점에서 **아래 요청된 순서대로** 깊이 있게 분석해주세요.\n", name));

		prompt.append(
			"**[가장 중요!]** 말투는 **'~입니다', '~네요', '~로군요', '~이군요' 같은 따뜻하고 명료한 말투**를 사용하세요.\n");
		prompt.append(
			"AI가 쓴 것 같은 뻔한 서론/결론, 억지 비유는 절대 쓰지 마세요. 부드럽고, 심각하지 않게, '발견한 사실'을 설명하는 방식이어야 합니다.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\"와 같이 자연스럽게 분석을 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 타고난 기질, 성격, 인성, 그룹 내 역할)\n");
		prompt.append(String.format(
			"(일간, 월지, 십성 분포를 바탕으로 %s님의 근본적인 성격과 인성을 심층 분석해주세요.)\n", name));
		prompt.append(
			"(만약 그룹이라면 이 성격이 팀 내에서 어떻게 작용할지, 어떤 역할(리더형, 조율자형, 마이웨이형 등)을 맡을지도 함께 예측해주세요.)\n\n");

		prompt.append("## 병크 및 리스크 예측\n");
		prompt.append("(사주 원국과 신살, 운의 흐름을 볼 때, 이 아이돌이 아이돌 활동 중 가장 조심해야 할 '병크'나 리스크는 무엇인가요?)\n");
		prompt.append(
			"(예: 구설수, 건강 문제, 재물 문제, 이성 문제 등. 흉살이나 충/형을 근거로 설명하되, 알아듣기 쉽게 풀어서 설명해주세요.)\n\n");

		prompt.append("## 아이돌이 아니었다면? (타고난 재능)\n");
		prompt.append(
			"(사주에 나타난 핵심 재능(식상, 인성, 관성 등)을 바탕으로, 아이돌이 아니었다면 어떤 직업에서 성공 했을지, 1~2가지 구체적으로 분석해주세요.)\n\n");

		prompt.append("## 연애관 및 이상형 (가장 마지막)\n");
		prompt.append("(팬들이 궁금해하는 부분입니다. 이 사람의 연애 스타일, 본능적으로 끌리는 이상형(외모, 성격)을 솔직하게 분석해주세요.)\n");
		prompt.append("(결혼은 언제쯤 할지 정확한 년도 예측, 배우자궁(일지)의 모습은 어떤지도 포함해주세요.)\n\n");

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
			"\"'%s'님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨네요.\" 로 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime(),
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 사주로 본 캐릭터 본질과 작품 속 모습\n");
		prompt.append("이 사주가 부여하는 핵심 성격/기질과 작품 속 캐릭터 설정의 일치점/차이점 분석.\n");
		prompt.append("작품 속 주요 행동이나 결정이 이 사주를 가졌기에 가능했던 이유 설명 (명리학적 근거, '비유' 활용).\n");
		prompt.append("캐릭터의 핵심 매력을 사주(일간, '신살' 등)를 통해 재해석.\n\n");

		prompt.append("## 작품 속 운명, 사주로 재해석하다\n");
		prompt.append(
			"캐릭터가 겪은 주요 사건(시련/성공/전환점)들을 사주 구조('충/형/합', '대운/세운 챕터' 변화)와 연결하여 명리학적으로 설명.\n");
		prompt.append("작품의 결말(해피/새드/오픈)이 이 사주를 가졌다면 필연적이었는지, 혹은 다른 가능성은 없었는지 분석.\n\n");

		prompt.append("## 작품 속 관계성, 궁합으로 엿보기\n");
		prompt.append("주인공 또는 주요 인물과의 관계(동료/친구/연인/적대)를 '관계의 시너지' 관점에서 분석 (간단히).\n");
		prompt.append("왜 특정 인물과 강하게 끌리거나 혹은 대립하게 되는지 명리학적 이유 설명.\n");

		prompt.append("## 만약 현실 세계에 존재한다면? \n");
		prompt.append("이 캐릭터가 2025년 대한민국에 이 사주를 가지고 태어났다면 어떤 모습일지 상상하여 서술.\n");
		prompt.append("현실에서의 예상 직업 (가장 잘 어울리는 직업 1~2개 집중 분석).\n");
		prompt.append("현실에서의 예상 연애 스타일 및 이상형.\n");
		prompt.append("작품 속 모습과 현실 버전의 가장 큰 차이점은 무엇일지 예측.\n\n");

		prompt.append("## 캐릭터의 성장과 팬들에게 주는 메시지\n");
		prompt.append("이 사주를 가진 캐릭터의 매력 정리\n");
		prompt.append("팬들이 이 캐릭터를 사랑하는 이유를 사주를 통해 설명하며 공감대 형성.\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== [수정] 4,6 러브 스토리 프롬프트 (혜안 적용) ====================
	private String createLoveStoryPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 분석 대상자들 정보 주입
		prompt.append("\n### 5. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 사람 정보: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);
		prompt.append("\n--- 두 번째 사람 정보: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		// ===== 3. 분석 구조 설명 =====
		prompt.append("\n### 6. [분석 구조] ###\n");
		prompt.append(String.format("%s님에 대한 분석\n", person1Name));
		prompt.append(String.format("%s님과 %s님의 궁합\n\n", person1Name, person2Name));

		// ===== 4. 1단계: 첫 번째 사람 개인 분석 =====
		prompt.append("### 7. [").append(person1Name).append("님 개인 분석] ###\n");
		prompt.append(String.format(
			"먼저 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.\n\n",
			person1Name));

		prompt.append("타고난 성격과 가치관\n");
		prompt.append(String.format(
			"%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.\n",
			person1Name));
		prompt.append(String.format(
			"%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.\n",
			person1Name));
		prompt.append("일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.\n\n");

		prompt.append("연애 스타일과 특징\n");
		prompt.append(String.format(
			"%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)\n",
			person1Name));
		prompt.append(String.format(
			"%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려)\n",
			person1Name));
		prompt.append(String.format(
			"%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?\n",
			person1Name));
		prompt.append(String.format(
			"%s님의 질투심 수준과 연애 vs 일의 우선순위는?\n\n",
			person1Name));

		prompt.append("이상형과 끌리는 타입\n");
		prompt.append(String.format(
			"%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.\n",
			person1Name));
		prompt.append(String.format(
			"%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.\n",
			person1Name));
		prompt.append(String.format(
			"반대로 %s님과 갈등이 생기기 쉬운 타입은 어떤 사람인지도 언급해주세요.\n\n",
			person1Name));

		// ===== 5. 2단계: 두 사람 궁합 분석 =====
		prompt.append("\n8. [2단계: ").append(person1Name).append("님과 ").append(person2Name)
			.append("님 궁합 분석] ###\n");
		prompt.append(String.format(
			"이제 %s님과 %s님 두 분의 궁합을 단계별로 분석해주세요.\n\n",
			person1Name, person2Name));

		prompt.append(String.format(
			"\"%s님과 %s님, 두 분의 사주 인연을 보니... [두 사람의 일간을 자연물에 비유]처럼 [어떤 느낌]의 만남이네요.\" 라는 느낌으로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("첫 만남: 서로의 첫인상\n");
		prompt.append(String.format(
			"%s님이 %s님을 처음 봤을 때 어떤 인상을 받을까요? (호감/무관심/경계)\n",
			person1Name, person2Name));
		prompt.append(String.format(
			"%s님이 %s님을 처음 봤을 때는 어떨까요?\n",
			person2Name, person1Name));
		prompt.append("누가 먼저 다가가거나 호감을 표현할 가능성이 높은지 예측해주세요.\n");
		prompt.append(String.format(
			"%s님의 이상형 분석 결과, %s님이 그 이상형에 얼마나 부합하는지 설명해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("썸과 관계 발전\n");
		prompt.append("관계가 친구에서 연인으로 발전하는 속도는? (빠른 편/천천히)\n");
		prompt.append("썸 기간 동안 누가 관계를 리드하고, 밀당 주도권은 누가 쥘까요?\n");
		prompt.append("서로의 어떤 점에 매력을 느끼고 마음을 열게 될까요? (구체적으로)\n");
		prompt.append(
			"두 사람의 오행 관계(상생/상극)를 쉽게 풀어서 설명하고, 서로에게 어떤 영향을 주는지 분석해주세요.\n\n");

		prompt.append("연애의 모습: 두 사람만의 케미\n");
		prompt.append("연인이 된 후 두 사람의 데이트 스타일은? (활동적/조용한/감성적)\n");
		prompt.append("애정 표현 방식이 서로 잘 맞을까요? 차이가 있다면 어떻게 조율해야 할까요?\n");
		prompt.append("스킨십 성향과 친밀도는?\n");
		prompt.append(
			"성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형)을 **사주 용어를 쉽게 풀어서** 설명해주세요. AI 티 나는 말투 절대 금지\n");
		prompt.append("연애 중 서로에게 주는 긍정적 영향은?\n\n");

		prompt.append("갈등과 극복\n");
		prompt.append(
			"두 사람 사이에 발생할 수 있는 주요 갈등 원인 3가지 (성격 차이, 가치관 충돌, 생활 패턴 등)를 예측하고, **지지 충/형을 쉽게 풀어서** 설명해주세요.\n");
		prompt.append("관계의 위기(권태기, 이별 위기)가 올 수 있는 시점은 언제일까요?\n");
		prompt.append("각자의 특성을 고려했을 때, 갈등을 어떻게 극복하는 유형인지 구체적으로 설명해주세요.\n");
		prompt.append("이 커플이 오래 지속되려면 서로 어떤 노력이 필요한가요?\n\n");

		prompt.append("결혼 가능성과 장기 전망\n");
		prompt.append(String.format(
			"%s님의 결혼관과 %s님의 결혼관을 각각 분석하고, 두 분이 결혼에 대해 어떻게 생각하고 있을지 예측해주세요.\n",
			person1Name, person2Name));
		prompt.append("이 커플의 결혼 가능성은? (냉철하게 판단해도 됩니다. 높음/중간/낮음)\n");
		prompt.append(
			"만약 결혼한다면 몇 년도에 할 가능성이 높은지, 대운과 세운을 참고하여 **구체적인 연도** 예측해주세요.\n");
		prompt.append(
			"두 사람의 인연에 대한 최종 조언과 응원 메시지로 따뜻하게 마무리해주세요.\n\n");

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

		// 2. 3인칭 서술 지시 (createIdolCompatibilityPrompt에서 가져와 강화)
		prompt.append("\n### ⚠️ 매우 중요: 3인칭 서술 (아이돌 팬픽 관점) ###\n");
		prompt.append(
			String.format("- 이 분석은 '%s'와 '%s'라는 제3자(아이돌)들에 대한 것입니다.\n", person1Name, person2Name));
		prompt.append("- 절대로 2인칭(당신, 당신들)을 사용하지 마세요.\n");
		prompt.append(
			String.format("- [개인 분석]은 '%s님은...', '%s님은...' 처럼 3인칭 단수로 서술해야 합니다.\n", person1Name,
				person2Name));
		prompt.append("- [궁합 분석]은 '두 사람은...', '%s님과 %s님은...' 처럼 3인칭 관찰자 시점으로 서술해야 합니다.\n");
		prompt.append("- 팬들의 상상력을 자극할 수 있는 서사적이고 감성적인 어조를 사용해주세요.\n");

		// 3. 분석 대상자들 정보 주입
		prompt.append("\n### 3. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 아이돌: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);
		prompt.append("\n--- 두 번째 아이돌: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 4. 분석 구조 설명 (3단계로 수정)
		prompt.append("\n### 4. [분석 구조] ###\n");
		prompt.append(String.format("1. %s님 개인 심층 분석\n", person1Name));
		prompt.append(String.format("2. %s님 개인 심층 분석\n", person2Name));
		prompt.append(String.format("3. %s님과 %s님의 관계 서사 (궁합)\n\n", person1Name, person2Name));

		// ===== 5. 1단계: 첫 번째 사람 개인 분석 =====
		prompt.append("### 5. [1단계: ").append(person1Name).append("님 개인 심층 분석] ###\n");
		prompt.append(String.format(
			"먼저 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.\n\n",
			person1Name));

		prompt.append("타고난 성격과 가치관\n");
		prompt.append(String.format(
			"%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.\n",
			person1Name));
		prompt.append(String.format(
			"%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.\n",
			person1Name));
		prompt.append("일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.\n\n");

		prompt.append("연애 스타일과 특징\n");
		prompt.append(String.format(
			"%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)\n",
			person1Name));
		prompt.append(String.format(
			"%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 분석\n",
			person1Name));
		prompt.append(String.format(
			"%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?\n\n",
			person1Name));

		prompt.append("이상형과 끌리는 타입\n");
		prompt.append(String.format(
			"%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.\n",
			person1Name));
		prompt.append(String.format(
			"%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.\n\n",
			person1Name));

		// ===== 6. 2단계: 두 번째 사람 개인 분석 (추가된 부분) =====
		prompt.append("### 6. [2단계: ").append(person2Name).append("님 개인 심층 분석] ###\n");
		prompt.append(String.format(
			"다음으로 %s님의 사주를 통해 이 분이 어떤 사람인지, 연애에서 어떤 모습을 보이는지 분석해주세요.\n\n",
			person2Name));

		prompt.append("타고난 성격과 가치관\n");
		prompt.append(String.format(
			"%s님의 일간, 오행, 십성을 바탕으로 연애 할때의 성격을 쉽고 재미있게 풀어서 설명해주세요.\n",
			person2Name));
		prompt.append(String.format(
			"%s님이 인생에서 중요하게 생각하는 가치관(사랑/일/돈/안정 등)은 무엇인지 설명해주세요.\n",
			person2Name));
		prompt.append("일지(배우자궁)와 지장간을 통해 내면의 모습과 결혼관을 분석해주세요.\n\n");

		prompt.append("연애 스타일과 특징\n");
		prompt.append(String.format(
			"%s님은 어떤 방식으로 사랑에 빠지나요? (적극적/소극적, 빠르게/천천히)\n",
			person2Name));
		prompt.append(String.format(
			"%s님의 애정 표현 방식은? (말로 표현/행동으로 표현/조용히 배려), 만세력 기반으로 질투나 집착이 많은 타입인지 재미있게 풀어서 설명\n",
			person2Name));
		prompt.append(String.format(
			"%s님은 연애할 때 어떤 장점이 있고, 어떤 점을 조심해야 하나요?\n\n",
			person2Name));

		prompt.append("이상형과 끌리는 타입\n");
		prompt.append(String.format(
			"%s님이 본능적으로 끌리는 사람의 특징 (외모, 성격, 분위기, 직업 등)을 구체적으로 설명해주세요.\n",
			person2Name));
		prompt.append(String.format(
			"%s님과 궁합이 잘 맞는 오행/일간은 무엇이며, 어떤 성향의 사람이 좋은지 설명해주세요.\n\n",
			person2Name));

		// ===== 7. 3단계: 두 사람 궁합 분석 (번호 수정 및 내용 보강) =====
		prompt.append("\n### 7. [3단계: ").append(person1Name).append("님과 ").append(person2Name)
			.append("님의 관계 서사] ###\n");
		prompt.append(String.format(
			"이제 [1단계]와 [2단계]의 개인 분석을 바탕으로, %s님과 %s님 두 분의 궁합을 단계별로 분석해주세요.\n\n",
			person1Name, person2Name));

		prompt.append(String.format(
			"\"%s님과 %s님, 두 분의 사주 인연을 보니... [두 사람의 일간을 자연물에 비유]처럼 [어떤 느낌]의 만남이네요.\" 라는 느낌으로 시작해주세요.\n\n",
			person1Name, person2Name));

		prompt.append("첫 만남: 서로의 첫인상\n");
		prompt.append(String.format(
			"%s님이 %s님을 처음 봤을 때 어떤 인상을 받을까요? (호감/무관심/경계)\n",
			person1Name, person2Name));
		prompt.append(String.format(
			"%s님이 %s님을 처음 봤을 때는 어떨까요?\n",
			person2Name, person1Name));
		prompt.append("누가 먼저 다가가거나 호감을 표현할 가능성이 높은지 [1, 2단계 성격]을 기반으로 예측해주세요.\n");
		prompt.append(String.format(
			"**[중요]** %s님의 이상형([1단계 분석])과 %s님의 매력([2단계 분석])이 얼마나 부합하는지 교차 분석해주세요.\n",
			person1Name, person2Name));
		prompt.append(String.format(
			"**[중요]** %s님의 이상형([2단계 분석])과 %s님의 매력([1단계 분석])이 얼마나 부합하는지 교차 분석해주세요.\n\n",
			person2Name, person1Name));

		prompt.append("썸과 관계 발전\n");
		prompt.append("관계가 친구에서 연인으로 발전하는 속도는? (빠른 편/천천히)\n");
		prompt.append("썸 기간 동안 누가 관계를 리드하고, 밀당 주도권은 누가 쥘까요?\n");
		prompt.append("서로의 어떤 점에 매력을 느끼고 마음을 열게 될까요? (구체적으로)\n");
		prompt.append(
			"두 사람의 오행 관계(상생/상극)를 쉽게 풀어서 설명하고, 서로에게 어떤 영향을 주는지 분석해주세요.\n\n");

		prompt.append("연애의 모습: 두 사람만의 케미\n");
		prompt.append("연인이 된 후 두 사람의 데이트 스타일은? (활동적/조용한/감성적)\n");
		prompt.append("애정 표현 방식이 서로 잘 맞을까요? 차이가 있다면 어떻게 조율해야 할까요?\n");
		prompt.append("스킨십 성향과 친밀도는?\n");
		prompt.append(
			"**[질투 분석] 누가 더 질투심이 많을까요?** 각자의 사주(비겁, 관성, 일간 특성 등)를 근거로 누가 어떤 상황에서 질투를 느끼는지, 그리고 어떻게 표현하는지 서사적으로 분석해주세요.\n");
		prompt.append("연애 vs 일, 두 사람의 우선순위는 비슷할까요? 다를까요?\n");
		prompt.append(
			"성격 궁합: 서로 잘 맞는 부분(합)과 서로 노력해야 하는 부분(충/형)을 **사주 용어를 쉽게 풀어서** 설명해주세요. AI 티 나는 말투 절대 금지\n\n");

		prompt.append("갈등과 극복\n");
		prompt.append(
			"두 사람 사이에 발생할 수 있는 주요 갈등 원인 3가지 (성격 차이, 가치관 충돌, 생활 패턴 등)를 예측하고, **지지 충/형을 쉽게 풀어서** 설명해주세요.\n");
		prompt.append("관계의 위기(권태기, 이별 위기)가 올 수 있는 시점은 언제일까요?\n");
		prompt.append(
			"각자의 특성을 고려했을 때, 갈등을 어떻게 극복하는 유형인지 구체적으로 설명해주세요.\n\n");

		prompt.append("미래: 결혼 가능성과 장기 전망\n");
		prompt.append(String.format(
			"%s님의 결혼관([1단계 분석])과 %s님의 결혼관([2단계 분석])을 비교 분석해주세요.\n",
			person1Name, person2Name));
		prompt.append("이 커플의 결혼 가능성은? (냉철하게 판단해도 됩니다. 높음/중간/낮음)\n");
		prompt.append(
			"만약 결혼한다면 몇 년도에 할 가능성이 높은지, 대운과 세운을 참고하여 **구체적인 연도** 예측해주세요.\n");
		prompt.append(
			"두 사람의 인연에 대한 최종 조언과 응원 메시지로 따뜻하게 마무리해주세요.\n\n");

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

		prompt.append("## 두 사람의 기본 관계 방정식: 끌림과 균열의 씨앗\n");
		prompt.append("두 사람이 서로에게 느끼는 매력과 기본적인 관계의 강점 분석.\n");
		prompt.append("겉으로 드러나지 않을 수 있는 관계의 취약점 또는 불만 요소 예측 (지지 '충/형', 오행 불균형 등).\n");
		prompt.append("제3자가 비집고 들어올 수 있는 '틈'은 어디에 있는지 분석.\n\n");

		prompt.append("## 제3자의 등장: 어떤 인물이, 왜 끼어드는가?\n");
		prompt.append("%s님 또는 %s님이 끌리기 쉬운 제3자의 사주적 특징(일간, 오행, 십성 등) 예측.\n");
		prompt.append("두 사람 중 누가 먼저 마음이 흔들리거나 관계에 변화를 줄 가능성이 높은지 분석.\n");
		prompt.append("제3자의 등장이 두 사람의 관계에 미치는 초기 영향력 예측.\n\n");

		prompt.append("## 질투와 경쟁: 감정의 소용돌이\n");
		prompt.append("삼각관계 상황에서 %s님과 %s님이 각각 보일 수 있는 질투의 양상과 강도 분석 (겁재, 비견 등 활용).\n");
		prompt.append("누가 관계의 주도권을 쥐려 하거나 혹은 더 집착하는 모습을 보일지 예측.\n");
		prompt.append("경쟁 구도 속에서 각자가 사용할 수 있는 전략이나 행동 패턴 분석.\n\n");

		prompt.append("## 관계의 역학: 누가 선택하고 누가 상처받는가?\n");
		prompt.append("삼각관계 구도에서 누가 심리적으로 우위에 서거나 선택하는 입장이 될 가능성이 높은지 분석.\n");
		prompt.append("반대로 누가 더 큰 상처를 받거나 관계에서 밀려날 가능성이 높은지 예측.\n");
		prompt.append("이 복잡한 관계가 안정될 가능성 vs 파국으로 치달을 가능성 평가.\n\n");

		prompt.append("## 예상 시나리오와 최종 조언\n");
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
		appendPersonInfoToPrompt(prompt, person1Name, person1Saju);
		prompt.append("\n--- 두 번째 사람: ").append(person2Name).append(" ---\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju);

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

		prompt.append("## 서로에게 끌리는 첫 만남의 에너지\n");
		prompt.append("- 두 사람의 일간(日干) 오행 관계와 첫인상 분석 (서로에게 어떤 매력을 느낄까?).\n");
		prompt.append("- 각자의 외적인 분위기('신살', 12운성 등)가 서로에게 어떻게 작용하는지.\n");
		prompt.append("- 관계 초반의 발전 속도 예측 (빠르게 가까워질까? 서서히 알아갈까?).\n\n");

		prompt.append("##  함께할 때의 조화와 보완 ('오행 조화')\n");
		prompt.append(
			"- 각자의 오행 분포를 비교하여, 서로의 부족한 기운을 채워주는 '상생' 관계인지, 혹은 에너지가 부딪히는 '상극' 관계인지 심층 분석.\n");
		prompt.append("- 함께 있을 때 느끼는 감정(안정감/편안함 vs 긴장감/불편함) 예측.\n");
		prompt.append("- 서로의 성장을 돕는 긍정적 측면과, 주의해야 할 부정적 측면 설명.\n\n");

		prompt.append("## 현실적인 관계에서의 역할과 갈등 (십성, '관계의 역동성')\n");
		prompt.append("- 각자의 십성(十星) 분포를 통해 관계에서의 역할 분담 예측 (주도/보조, 표현/수용 등).\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간 합(合)/충(沖)/형(刑) 관계 분석: 어떤 부분에서 조화를 이루고, 어떤 부분에서 '관계의 역동성'(갈등)이 발생하기 쉬운지.\n");
		prompt.append("- 예상되는 주요 갈등 유형과 이를 '성장의 계기'로 삼기 위한 구체적인 조언.\n\n");

		prompt.append("## 관계 발전을 위한 맞춤 조언\n");
		prompt.append("- 서로의 장점을 더욱 살리고 단점을 보완해주기 위한 구체적인 소통 방식이나 행동 지침 2~3가지 제안.\n");
		prompt.append("- 두 사람이 함께 성장하고 행복한 관계를 오래 유지하기 위해 각자 노력해야 할 부분.\n\n");

		prompt.append("## 총평: 관계의 본질과 미래\n");
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

		// 1. 기본 정보
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("%s | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			java.time.LocalDate.now().getYear()));

		// 2. 사주 팔자
		prompt.append("### 사주팔자 ###\n");
		prompt.append(String.format("년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s\n\n",
			saju.getYearSky().getKorean(), saju.getYearGround().getKorean(),
			saju.getMonthSky().getKorean(), saju.getMonthGround().getKorean(),
			saju.getDaySky().getKorean(), saju.getDayGround().getKorean(),
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"));

		// 3. 일간 정보
		prompt.append("### 일간 ###\n");
		prompt.append(String.format("%s%s (%s) - 본질적 성향의 뿌리\n\n",
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle(),
			saju.getDaySky().getTenStar()));

		// 4. 오행, 십성
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

		// 5. 12운성
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

		// 7. 신살
		prompt.append("### 신살 ###\n");
		appendSinsalFull(prompt, saju);
		prompt.append("\n");

		// 8. 대운
		prompt.append("### 대운 ###\n");
		appendDaewoonCompact(prompt, saju, input.getGender());
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
			prompt.append(String.format("%s님 정보 로드 오류\n\n", name));
			return;
		}

		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();
		ManseryeokCalculationResponse.InputInfo input = manseResponse.getInput();

		// ===== 1. 기본 정보 =====
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("%s | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			java.time.LocalDate.now().getYear()));

		// ===== 2. 사주팔자 =====
		prompt.append("### 사주팔자 ###\n");
		prompt.append(String.format("년주: %s%s | 월주: %s%s | 일주: %s%s (일간) | 시주: %s%s\n\n",
			saju.getYearSky() != null ? saju.getYearSky().getKorean() : "?",
			saju.getYearGround() != null ? saju.getYearGround().getKorean() : "?",
			saju.getMonthSky() != null ? saju.getMonthSky().getKorean() : "?",
			saju.getMonthGround() != null ? saju.getMonthGround().getKorean() : "?",
			saju.getDaySky() != null ? saju.getDaySky().getKorean() : "?",
			saju.getDayGround() != null ? saju.getDayGround().getKorean() : "?",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"));

		// ===== 3. 일간 정보 =====
		prompt.append("### 일간 ###\n");
		if (saju.getDaySky() != null) {
			prompt.append(String.format("%s%s (%s) - 본질적 성향의 뿌리\n\n",
				saju.getDaySky().getKorean() != null ? saju.getDaySky().getKorean() : "?",
				saju.getDaySky().getFiveCircle() != null ? saju.getDaySky().getFiveCircle() : "?",
				saju.getDaySky().getTenStar() != null ? saju.getDaySky().getTenStar() : "?"));
		} else {
			prompt.append("일간 정보 없음\n\n");
		}

		// ===== 4. 오행 분포 =====
		prompt.append("### 오행 분포 ###\n");
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();

		if (saju.getYearSky() != null && saju.getMonthSky() != null && saju.getDaySky() != null) {
			calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

			String ilganOhaeng = saju.getDaySky() != null ? saju.getDaySky().getFiveCircle() : "";
			prompt.append(formatOhaengAsTable(ohaengCounts, ilganOhaeng));

			// ===== 5. 십성 분포 =====
			prompt.append("### 십성 분포 ###\n");
			prompt.append(formatSipseongAsTable(sipseongCounts));
		} else {
			prompt.append("오행/십성: 계산 불가 - 필수 정보 누락\n\n");
		}

		// ===== 6. 12운성 =====
		prompt.append("### 12운성 ###\n");
		prompt.append(String.format("년:%s 월:%s 일:%s 시:%s\n\n",
			saju.getYearGround() != null && saju.getYearGround().getUnseong() != null
				? saju.getYearGround().getUnseong() : "-",
			saju.getMonthGround() != null && saju.getMonthGround().getUnseong() != null
				? saju.getMonthGround().getUnseong() : "-",
			saju.getDayGround() != null && saju.getDayGround().getUnseong() != null
				? saju.getDayGround().getUnseong() : "-",
			saju.getTimeGround() != null && saju.getTimeGround().getUnseong() != null
				? saju.getTimeGround().getUnseong() : "-"));

		// ===== 7. 지장간 =====
		prompt.append("### 지장간 ###\n");
		prompt.append("일지(배우자궁): ");
		if (saju.getDayGround() != null) {
			appendJijangganDetailSimple(prompt, saju.getDayGround());
		} else {
			prompt.append("정보 없음");
		}
		prompt.append("\n");

		prompt.append("년지: " + getJijangganSummary(saju.getYearGround()) + " | ");
		prompt.append("월지: " + getJijangganSummary(saju.getMonthGround()) + " | ");
		if (saju.getTimeGround() != null) {
			prompt.append("시지: " + getJijangganSummary(saju.getTimeGround()));
		}
		prompt.append("\n\n");

		// ===== 8. 신살 (전체) =====
		prompt.append("### 신살 ###\n");
		appendSinsalFull(prompt, saju);

		// ===== 9. 대운 (간략) =====
		prompt.append("### 대운 ###\n");
		if (saju.getBigFortuneNumber() != null) {
			prompt.append(String.format("시작:%d세 | 방향:%s\n",
				saju.getBigFortuneNumber(),
				getDaewoonDirection(saju, input.getGender())));
			appendDaewoonSimple(prompt, saju, input.getGender());
		} else {
			prompt.append("대운 정보 없음\n");
		}
		prompt.append("\n");
	}

	private String formatSipseongAsTable(Map<String, Integer> sipseongCounts) {
		List<String> sipseongOrder = Arrays.asList(
			"비견", "겁재", "식신", "상관", "편재",
			"정재", "편관", "정관", "편인", "정인"
		);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sipseongOrder.size(); i++) {
			String sipseong = sipseongOrder.get(i);
			int count = sipseongCounts.getOrDefault(sipseong, 0);
			sb.append(sipseong).append(":").append(count);

			if (i == 4) {
				sb.append("\n");
			} else if (i < sipseongOrder.size() - 1) {
				sb.append(" | ");
			}
		}
		sb.append("\n\n");
		return sb.toString();
	}

	private String formatOhaengAsTable(Map<String, Double> ohaengCounts, String ilganOhaeng) {
		StringBuilder sb = new StringBuilder();
		sb.append("목:").append(String.format("%.1f", ohaengCounts.getOrDefault("목", 0.0)));
		if ("목".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("화:").append(String.format("%.1f", ohaengCounts.getOrDefault("화", 0.0)));
		if ("화".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("토:").append(String.format("%.1f", ohaengCounts.getOrDefault("토", 0.0)));
		if ("토".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("금:").append(String.format("%.1f", ohaengCounts.getOrDefault("금", 0.0)));
		if ("금".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" | ");

		sb.append("수:").append(String.format("%.1f", ohaengCounts.getOrDefault("수", 0.0)));
		if ("수".equals(ilganOhaeng)) {
			sb.append("★");
		}
		sb.append(" (★=일간)\n\n");

		return sb.toString();
	}

	private void appendJijangganDetailSimple(StringBuilder prompt, PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			prompt.append("정보 없음");
			return;
		}

		JijangganInfo ji = pillar.getJijanggan();
		List<String> elements = new ArrayList<>();

		if (ji.getFirst() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getFirst().getKorean() != null ? ji.getFirst().getKorean() : "?",
				ji.getFirst().getFiveCircle() != null ? ji.getFirst().getFiveCircle() : "?",
				ji.getFirst().getTenStar() != null ? ji.getFirst().getTenStar() : "?",
				ji.getFirst().getRate() != null ? ji.getFirst().getRate() : 0));
		}
		if (ji.getSecond() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getSecond().getKorean() != null ? ji.getSecond().getKorean() : "?",
				ji.getSecond().getFiveCircle() != null ? ji.getSecond().getFiveCircle() : "?",
				ji.getSecond().getTenStar() != null ? ji.getSecond().getTenStar() : "?",
				ji.getSecond().getRate() != null ? ji.getSecond().getRate() : 0));
		}
		if (ji.getThird() != null) {
			elements.add(String.format("%s%s(%s,%d%%)",
				ji.getThird().getKorean() != null ? ji.getThird().getKorean() : "?",
				ji.getThird().getFiveCircle() != null ? ji.getThird().getFiveCircle() : "?",
				ji.getThird().getTenStar() != null ? ji.getThird().getTenStar() : "?",
				ji.getThird().getRate() != null ? ji.getThird().getRate() : 0));
		}

		prompt.append(String.join(", ", elements));
	}

	private String getJijangganSummary(PillarElement pillar) {
		if (pillar == null || pillar.getJijanggan() == null) {
			return "?";
		}
		JijangganInfo ji = pillar.getJijanggan();

		List<String> elements = new ArrayList<>();
		if (ji.getFirst() != null && ji.getFirst().getKorean() != null
			&& ji.getFirst().getFiveCircle() != null && ji.getFirst().getRate() != null) {
			elements.add(ji.getFirst().getKorean() + ji.getFirst().getFiveCircle()
				+ "(" + ji.getFirst().getRate() + "%)");
		}
		if (ji.getSecond() != null && ji.getSecond().getKorean() != null
			&& ji.getSecond().getFiveCircle() != null && ji.getSecond().getRate() != null) {
			elements.add(ji.getSecond().getKorean() + ji.getSecond().getFiveCircle()
				+ "(" + ji.getSecond().getRate() + "%)");
		}
		if (ji.getThird() != null && ji.getThird().getKorean() != null
			&& ji.getThird().getFiveCircle() != null && ji.getThird().getRate() != null) {
			elements.add(ji.getThird().getKorean() + ji.getThird().getFiveCircle()
				+ "(" + ji.getThird().getRate() + "%)");
		}

		return elements.isEmpty() ? "?" : String.join(", ", elements);
	}

	private String getDaewoonDirection(SajuInfo saju, String gender) {
		if (saju.getYearSky() == null || saju.getYearSky().getMinusPlus() == null) {
			return "?";
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		return "MALE".equalsIgnoreCase(gender) ?
			(yearSkyMinusPlus.equals("+") ? "순행" : "역행") :
			(yearSkyMinusPlus.equals("+") ? "역행" : "순행");
	}

	private void appendDaewoonSimple(StringBuilder prompt, SajuInfo saju, String gender) {
		if (saju.getMonthSky() == null || saju.getMonthGround() == null
			|| saju.getBigFortuneNumber() == null) {
			prompt.append("대운 흐름 계산 불가\n");
			return;
		}

		String monthGapja = saju.getMonthSky().getKorean() + saju.getMonthGround().getKorean();
		int currentGapjaIndex = GAPJA_CYCLE.indexOf(monthGapja);

		if (currentGapjaIndex == -1) {
			prompt.append("대운 흐름 계산 불가\n");
			return;
		}

		String flowDirection = getDaewoonDirection(saju, gender);
		int startAge = saju.getBigFortuneNumber();

		// 현재 나이 계산
		int currentYear = java.time.LocalDate.now().getYear();
		// 간단히 년주로 추정 (정확한 생년은 별도 처리 필요)
		int currentAge = 30; // 기본값 (실제로는 input에서 계산)
		int currentDaewoonIndex = Math.max(0, (currentAge - startAge) / 10);

		// 현재 + 미래 2개 (총 3개)
		for (int i = currentDaewoonIndex; i < currentDaewoonIndex + 3 && i < 9; i++) {
			int age = startAge + (i * 10);
			if (age > 120) {
				break;
			}

			int nextIndex;
			if (flowDirection.equals("순행")) {
				nextIndex = (currentGapjaIndex + i + 1) % 60;
			} else {
				nextIndex = (currentGapjaIndex - (i + 1) % 60 + 60) % 60;
			}
			String daewoonGapja = GAPJA_CYCLE.get(nextIndex);

			if (i == currentDaewoonIndex) {
				prompt.append(String.format("▶ %d~%d세: %s (현재)\n", age, age + 9, daewoonGapja));
			} else {
				prompt.append(String.format("  %d~%d세: %s\n", age, age + 9, daewoonGapja));
			}
		}
	}

	private String extractContentFromResponseGpt5(String jsonResponse)
		throws JsonProcessingException {

		if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
			throw new IllegalArgumentException("GPT 응답이 비어있습니다.");
		}

		try {
			JsonNode root = objectMapper.readTree(jsonResponse);

			// 에러 체크
			if (root.path("error").isObject()) {
				JsonNode errorNode = root.get("error");
				String errorMessage = errorNode.path("message").asText("알 수 없는 API 오류");
				log.error("GPT API 에러: {}", errorMessage);
				throw new IllegalArgumentException("GPT API 에러: " + errorMessage);
			}

			// output 배열 체크
			JsonNode outputNode = root.path("output");
			if (!outputNode.isArray() || outputNode.isEmpty()) {
				log.error("응답에 유효한 'output' 배열이 없습니다. JSON: {}", jsonResponse);
				throw new IllegalArgumentException("GPT 응답 형식이 올바르지 않습니다.");
			}

			// output 배열에서 message 타입 찾기
			for (JsonNode outputItem : outputNode) {
				if ("message".equals(outputItem.path("type").asText())) {
					JsonNode contentArray = outputItem.path("content");
					if (contentArray.isArray() && !contentArray.isEmpty()) {
						// content 배열에서 output_text 타입 찾기
						for (JsonNode contentItem : contentArray) {
							if ("output_text".equals(contentItem.path("type").asText())) {
								JsonNode textNode = contentItem.path("text");
								if (textNode.isTextual()) {
									String content = textNode.asText();
									log.info("✅ GPT 응답 추출 성공 - 길이: {} 문자", content.length());
									return content; // ← 이게 JSON 문자열
								}
							}
						}
					}
				}
			}

			log.error("GPT 응답에서 'text' 필드를 찾을 수 없습니다. JSON: {}", jsonResponse);
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
			saju.getSinsalInfo().forEach((pillarName, sinsals) -> {
				if (sinsals != null && !sinsals.isEmpty()) {
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

	private void appendSinsalFull(StringBuilder prompt, SajuInfo saju) {
		Map<String, List<String>> sinsalByPillar = new HashMap<>();
		sinsalByPillar.put("년주", new ArrayList<>());
		sinsalByPillar.put("월주", new ArrayList<>());
		sinsalByPillar.put("일주", new ArrayList<>());
		sinsalByPillar.put("시주", new ArrayList<>());

		// 각 기둥별 신살 수집
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().forEach((pillar, sinsals) -> {
				if (sinsals != null && !sinsals.isEmpty()) {
					sinsalByPillar.get(pillar).addAll(sinsals);
				}
			});
		}

		// 특수 신살 추가 (일주)
		if (Boolean.TRUE.equals(saju.getHasGoegang())) {
			sinsalByPillar.get("일주").add("괴강살");
		}
		if (Boolean.TRUE.equals(saju.getHasBaekho())) {
			sinsalByPillar.get("일주").add("백호대살");
		}

		// 공망 추가
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			sinsalByPillar.get("일주").add("공망:" + String.join(",", saju.getGongmang()));
		}

		// 기둥별로 출력 (신살이 있는 기둥만)
		boolean hasSinsal = false;
		for (String pillar : Arrays.asList("년주", "월주", "일주", "시주")) {
			List<String> sinsals = sinsalByPillar.get(pillar);
			if (!sinsals.isEmpty()) {
				prompt.append(pillar).append(": ").append(String.join(", ", sinsals)).append("\n");
				hasSinsal = true;
			}
		}

		if (!hasSinsal) {
			prompt.append("해당 없음\n");
		}
		prompt.append("\n");
	}

	private void appendDaewoonCompact(StringBuilder prompt, SajuInfo saju, String gender) {
		if (saju.getYearSky() == null || saju.getMonthSky() == null
			|| saju.getMonthGround() == null || saju.getBigFortuneNumber() == null) {
			prompt.append("대운 정보 없음\n\n");
			return;
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		if (yearSkyMinusPlus == null) {
			prompt.append("대운 정보 없음\n\n");
			return;
		}

		String flowDirection = "MALE".equalsIgnoreCase(gender) ?
			(yearSkyMinusPlus.equals("+") ? "순행" : "역행") :
			(yearSkyMinusPlus.equals("+") ? "역행" : "순행");

		int startAge = saju.getBigFortuneNumber();
		String monthGapja = saju.getMonthSky().getKorean() + saju.getMonthGround().getKorean();
		int currentGapjaIndex = GAPJA_CYCLE.indexOf(monthGapja);

		if (currentGapjaIndex == -1) {
			prompt.append("대운 정보 없음\n\n");
			return;
		}

		// 현재 나이 계산 (간단히 년도 차이로)
		int currentYear = java.time.LocalDate.now().getYear();
		int birthYear = Integer.parseInt(
			saju.getYearSky().getKorean() + saju.getYearGround().getKorean());
		int currentAge = currentYear - birthYear + 1; // 한국 나이

		// 현재 대운 찾기
		int currentDaewoonIndex = Math.max(0, (currentAge - startAge) / 10);

		prompt.append(String.format("시작:%d세 | 방향:%s\n", startAge, flowDirection));

		// 현재 + 미래 2개만 표시 (총 3개)
		for (int i = currentDaewoonIndex; i < currentDaewoonIndex + 3 && i < 9; i++) {
			int age = startAge + (i * 10);
			if (age > 120) {
				break;
			}

			int nextIndex;
			if (flowDirection.equals("순행")) {
				nextIndex = (currentGapjaIndex + i + 1) % 60;
			} else {
				nextIndex = (currentGapjaIndex - (i + 1) % 60 + 60) % 60;
			}
			String daewoonGapja = GAPJA_CYCLE.get(nextIndex);

			if (i == currentDaewoonIndex) {
				prompt.append(String.format("▶ %d~%d세: %s (현재)\n", age, age + 9, daewoonGapja));
			} else {
				prompt.append(String.format("  %d~%d세: %s\n", age, age + 9, daewoonGapja));
			}
		}
		prompt.append("\n");
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
