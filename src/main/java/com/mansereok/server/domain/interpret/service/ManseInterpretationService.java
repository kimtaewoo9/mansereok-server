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
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ManseInterpretationService {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final GptApiRetryService gptApiRetryService;

	private final UserService userService;
	private final OgImageGenerationService ogImageGenerationService;
	private final DiscordNotificationService discordNotificationService; // 👈 Slack -> Discord
	private final EmailService emailService;
	private final SajuResultService sajuResultService;

	private static final List<String> GAPJA_CYCLE_KOR = new ArrayList<>();

	// 60갑자 순서 정의 (대운 계산용)
	private static final List<String> HEAVENLY_STEMS = Arrays.asList("甲", "乙", "丙", "丁", "戊",
		"己", "庚", "辛", "壬", "癸");
	private static final List<String> EARTHLY_BRANCHES = Arrays.asList("子", "丑", "寅", "卯", "辰",
		"巳", "午", "未", "申", "酉", "戌", "亥");

	private static final List<String> HEAVENLY_STEMS_KOR = Arrays.asList("갑", "을", "병", "정", "무",
		"기", "경", "신", "임", "계");
	private static final List<String> EARTHLY_BRANCHES_KOR = Arrays.asList("자", "축", "인", "묘", "진",
		"사", "오", "미", "신", "유", "술", "해");

	private static final List<String> GAPJA_CYCLE = new ArrayList<>();

	static {
		for (int i = 0; i < 60; i++) {
			GAPJA_CYCLE.add(HEAVENLY_STEMS.get(i % 10) + EARTHLY_BRANCHES.get(i % 12));

			GAPJA_CYCLE_KOR.add(HEAVENLY_STEMS_KOR.get(i % 10) + EARTHLY_BRANCHES_KOR.get(i % 12));
		}
	}

	private static final String GPT5_SYSTEM_INSTRUCTION =
		"--- SYSTEM INSTRUCTION ---\n" +
			"당신은 30년 경력의 전문 사주명리학자입니다. " +
			"자연스럽고 전문적인 어조로 사주 해석을 제공하되, 절대 다음 표현들을 사용하지 마세요:\n" +
			"- 해석이 AI나 시스템에 의한 것임을 암시하는 모든 메타적 표현 절대 금지\n\n" +
			"도입은 짧고 자연스럽게 시작하되, 인위적인 안내 멘트 없이 바로 본론으로 이어가세요.\n" +
			"부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조하세요. " +
			"'해요'체를 기본으로 사용하되, 전문적인 분석이나 정보를 전달할 때는 '~입니다', '~습니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 모두 갖춘 어조를 사용하세요.\n\n"
			+
			"--- USER QUERY ---\n";

	private static final Pattern BUSINESS_PAGE_BREAK_PATTERN = Pattern.compile(
		"(?i)\\[\\s*PAGE_BREAK\\s*\\]");
	private static final Pattern BRACKET_SECTION_TITLE_PATTERN = Pattern.compile(
		"(?m)^\\s*\\[[0-9]+\\.[^\\]]*\\]\\s*\\n?");
	private static final Pattern NUMBERED_SUBSECTION_PATTERN = Pattern.compile(
		"(?m)^\\s*\\d+[-.]\\d+\\s+");
	private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile(
		"(?m)^\\s*\\d+\\s*[-.)]\\s+");
	private static final Pattern HASH_HEADER_PATTERN = Pattern.compile(
		"(?m)^\\s*#+\\s*");
	private static final Pattern ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN = Pattern.compile(
		"(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(?::\\d{2})?");
	private static final Pattern DATETIME_WITH_SPACE_PATTERN = Pattern.compile(
		"(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}:\\d{2})(?::\\d{2})?");
	private static final Pattern DATE_WITH_DAY_PATTERN = Pattern.compile(
		"(\\d{4})-(\\d{2})-(\\d{2})");
	private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile(
		"\\b(\\d{4})-(0[1-9]|1[0-2])\\b");
	private static final Pattern THREE_OR_MORE_NEWLINES_PATTERN = Pattern.compile(
		"\\n{3,}");
	private static final Pattern MONEY_LUCK_LETTERED_SECTION_PATTERN = Pattern.compile(
		"(?m)^\\s*\\[?[A-H]\\s*[.)]\\s*[^\\n]*\\n?");
	private static final Pattern MONEY_LUCK_BRACKET_OPEN_FRAGMENT_PATTERN = Pattern.compile(
		"(?m)^\\s*\\[\\s*([^\\]\\n]{1,120})\\s*$");
	private static final Pattern MONEY_LUCK_BRACKET_CLOSE_FRAGMENT_PATTERN = Pattern.compile(
		"(?m)^\\s*([^\\[\\]\\n]{1,120})\\s*\\]\\s*$");
	private static final Pattern MONEY_LUCK_BRACKET_ONLY_HEADING_PATTERN = Pattern.compile(
		"(?m)^\\s*\\[[^\\]\\n]{1,120}\\]\\s*$");
	private static final Pattern ARABIC_OR_CYRILLIC_PATTERN = Pattern.compile(
		"[\\p{IsArabic}\\p{IsCyrillic}]+");
	private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile(
		"[ \\t]{2,}");
	private static final Pattern KEYWORD_TITLE_LINE_PATTERN = Pattern.compile(
		"^\\s*\\[[^\\]\\n]{1,120}\\]");
	private static final List<String> FREE_PARAGRAPH_TRANSITIONS = List.of(
		"다만", "반면", "또한", "그리고", "한편", "특히", "무엇보다", "이때", "여기서", "정리하면",
		"결론적으로", "요약하면", "반대로");
	private static final int BUSINESS_SUMMARY_MAX_LINES = 5;
	private static final int BUSINESS_SUMMARY_MAX_CHARS = 280;
	private static final int MARCH_MONTHLY_SECTION_MAX_CHARS = 230;
	private static final List<String> MARCH_MONTHLY_SECTION_TITLES = List.of(
		"3월 핵심 키워드",
		"금전운",
		"연애운",
		"학업운",
		"직장/일운",
		"건강운",
		"주의할 점과 조언",
		"3월운 총평"
	);

	public ManseInterpretationService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl,
		GptApiRetryService gptApiRetryService,
		UserService userService,
		CompatibilityResultRepository compatibilityResultRepository,
		OgImageGenerationService ogImageGenerationService,
		DiscordNotificationService discordNotificationService,
		EmailService emailService,
		SajuResultService sajuResultService
	) {
		this.gptApiRetryService = gptApiRetryService;
		this.userService = userService;
		this.ogImageGenerationService = ogImageGenerationService;
		this.discordNotificationService = discordNotificationService;
		this.emailService = emailService;
		this.sajuResultService = sajuResultService;
	}

	@Async("gptTaskExecutor")
	public void interpret(
		String name,
		ManseryeokCalculationResponse response,
		String username,
		Long subcategoryId,
		Long paymentId,
		String sourceTitle
	) {
		log.info("✅ 사주 해석 요청 시작 - name: {}, subcategoryId: {}", name, subcategoryId);

		Long resultId = null; // 롤백용 ID 저장

		try {
			// 1. [DB] 초기 정보 저장 (DB 커넥션 사용 O -> 즉시 반납)
			String ilgan = "정보 없음";
			if (response != null && response.getSaju() != null
				&& response.getSaju().getDaySky() != null) {
				ilgan = response.getSaju().getDaySky().getKorean() + response.getSaju().getDaySky()
					.getFiveCircle();
			}

			Result result = sajuResultService.updateInitialStatus(paymentId, name, response, ilgan);
			resultId = result.getId();
			log.info("[Async] 정보 업데이트 완료: resultId={}", resultId);

			// 2. [Non-DB] 알림 전송
			try {
				User user = userService.findByUsername(username);
				discordNotificationService.sendInterpretationRequestNotification(name,
					user.getEmail(), response.getInput().getSolarDate().toString(), subcategoryId);
			} catch (Exception e) {
				log.error("알림 전송 실패", e);
			}

			// 3. GPT 호출 (DB 커넥션 사용 X)
			String userPrompt = createPromptBySubcategory(subcategoryId, name, response,
				sourceTitle);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			String requestBody = objectMapper.
				writeValueAsString(
					new Gpt5Request(
						"gpt-5.2",
						input,
						16384,
						"high",
						"high")
				);

			log.info("GPT API 호출 시작...");
			String gptResponse = gptApiRetryService.callGptApiWithRetry(requestBody);

			GptSajuResponse gptData = objectMapper.readValue(
				extractContentFromResponseGpt5(gptResponse),
				GptSajuResponse.class
			);

			String normalizedFullAnalysis = normalizeAnalysisBySubcategory(subcategoryId,
				gptData.getFullAnalysis());
			String normalizedSummary = normalizeSummaryBySubcategory(subcategoryId,
				gptData.getSummary());

			// 4. [DB] 결과 저장 (DB 커넥션 사용 O -> 즉시 반납)
			User user = userService.findByUsername(username);
			Result savedResult = sajuResultService.saveFinalResult(
				resultId,
				normalizedFullAnalysis,
				normalizedSummary
			);
			log.info("해석 결과 저장 완료: resultId={}", savedResult.getId());

			// 5. [Non-DB] 후처리
			try {
				ogImageGenerationService.generateAndUploadOgImage(savedResult);
			} catch (Exception e) {
				log.error("OG 실패", e);
			}

			try {
				if (user.getEmail() != null) {
					emailService.sendResultReadyEmail(user.getEmail(), user.getName());
				}
			} catch (Exception e) {
				log.error("이메일 실패", e);
			}

		} catch (Exception e) {
			log.error("해석 중 오류 발생: {}", e.getMessage(), e);
			// 6. [DB] 에러 롤백
			sajuResultService.rollbackStatus(resultId);
		}
	}

	@Async("gptTaskExecutor")
	public void analyzeCompatibilityWithSubcategory(
		String person1Name, ManseryeokCalculationResponse person1Response,
		String person2Name, ManseryeokCalculationResponse person2Response,
		Long subcategoryId, Long paymentId, String username,
		String person1SourceTitle, String person2SourceTitle
	) {
		log.info("✅ 궁합 분석 요청 시작: {} & {}", person1Name, person2Name);

		Long resultId = null;

		try {
			// 1. [DB] 초기 정보 저장
			String p1Ilgan = extractIlgan(person1Response);
			String p2Ilgan = extractIlgan(person2Response);

			CompatibilityResult result = sajuResultService.updateCompatibilityInitialStatus(
				paymentId, person1Name, p1Ilgan, person2Name, p2Ilgan
			);
			resultId = result.getId();

			// 2. GPT 호출 (DB 커넥션 사용 X)
			try {
				discordNotificationService.sendCompatibilityRequestNotification(person1Name,
					person1Response.getInput().getSolarDate().toString(), person2Name,
					person2Response.getInput().getSolarDate().toString());
			} catch (Exception e) {
			}

			String userPrompt = createCompatibilityPromptBySubcategory(
				subcategoryId, person1Name, person1Response, person2Name, person2Response,
				person1SourceTitle, person2SourceTitle
			);

			String systemInstruction = GPT5_SYSTEM_INSTRUCTION; // 기본값

			// 재회운(subcategoryId == 19)인 경우, 시스템 프롬프트 덮어쓰기
			if (subcategoryId == 19L) {
				systemInstruction =
					"당신은 대한민국 최고의 재회 상담가이자 사주 명리학 대가 '혜안'입니다.\n" +
						"내담자는 이 상담을 위해 **매우 비싼 비용**을 지불했습니다. 절대 내용을 요약하거나 짧게 끝내지 마십시오.\n" +
						"모든 분석은 **'논문' 수준의 깊이**와 **'소설' 수준의 서사**를 갖춰야 합니다.\n" +
						"단순한 사실 전달을 넘어, 내담자의 마음을 어루만지는 **감성적인 문체**로, 최대한 길고 자세하게 서술하세요.\n" +
						"한 챕터당 최소 **공백 포함 1,000자 이상** 작성해야 합니다.";
			}

			String requestBody = objectMapper.writeValueAsString(
				new Gpt5Request(
					"gpt-5.2",
					systemInstruction + userPrompt,
					16384,
					"high",
					"high")
			);

			log.info("GPT 궁합 API 호출 시작");
			String gptResponse = gptApiRetryService.callGptApiWithRetry(requestBody);

			GptCompatibilityResponse gptData = objectMapper.readValue(
				extractContentFromResponseGpt5(gptResponse), GptCompatibilityResponse.class);

			// 3. [DB] 결과 저장
			CompatibilityResult savedResult = sajuResultService.saveCompatibilityFinalResult(
				resultId, gptData.getInterpretation(), gptData.getScore(), gptData.getSummary()
			);

			// 4. 후처리
			ogImageGenerationService.generateAndUploadOgImage(savedResult);
			try {
				User user = userService.findByUsername(username);
				if (user != null && user.getEmail() != null) {
					emailService.sendResultReadyEmail(user.getEmail(), user.getName());
				}
			} catch (Exception e) {
			}

		} catch (Exception e) {
			log.error("궁합 분석 오류: {}", e.getMessage(), e);
			// 5. [DB] 롤백
			sajuResultService.rollbackCompatibilityStatus(resultId);
		}
	}

	@Async("gptFreeTaskExecutor")
	public void interpretFree(
		String name, ManseryeokCalculationResponse response,
		String username, Long subcategoryId, Long paymentId
	) {
		log.info("🆓 무료 사주 해석 시작");

		Long resultId = null;

		try {
			// 1. [DB] 초기 정보 저장
			String ilgan = extractIlgan(response);
			Result result = sajuResultService.updateInitialStatus(paymentId, name, response, ilgan);
			resultId = result.getId();

			// 2. GPT 호출
			try {
				User user = userService.findByUsername(username);
				discordNotificationService.sendInterpretationRequestNotification(name,
					user.getEmail(), response.getInput().getSolarDate().toString(), subcategoryId);
			} catch (Exception e) {
			}

			String userPrompt = createFreePromptBySubcategory(subcategoryId, name, response);
			String requestBody = objectMapper.writeValueAsString(
				new Gpt5Request("gpt-5-mini", GPT5_SYSTEM_INSTRUCTION + userPrompt, 8192, "medium",
					"medium"));

			log.info("GPT-5-mini 호출...");
			String gptResponse = gptApiRetryService.callGptApiWithRetry(requestBody);
			GptSajuResponse gptData = objectMapper.readValue(
				extractContentFromResponseGpt5(gptResponse), GptSajuResponse.class);

			String normalizedFullAnalysis = normalizeAnalysisBySubcategory(subcategoryId,
				gptData.getFullAnalysis());
			String normalizedSummary = normalizeSummaryBySubcategory(subcategoryId,
				gptData.getSummary());

			// 3. [DB] 결과 저장
			Result savedResult = sajuResultService.saveFinalResult(resultId,
				normalizedFullAnalysis, normalizedSummary);

			// 4. 후처리
			try {
				ogImageGenerationService.generateAndUploadOgImage(savedResult);
			} catch (Exception e) {
				log.error("OG 실패", e);
			}

		} catch (Exception e) {
			log.error("무료 사주 오류: {}", e.getMessage(), e);
			// 5. [DB] 롤백
			sajuResultService.rollbackStatus(resultId);
		}
	}

	@Async("gptTaskExecutor") // 고퀄리티를 원하면 gptTaskExecutor, 절약하려면 gptFreeTaskExecutor
	public void analyzeCompatibilityFree(
		String person1Name, ManseryeokCalculationResponse person1Response,
		String person2Name, ManseryeokCalculationResponse person2Response,
		Long subcategoryId, Long paymentId, String username
	) {
		log.info("🆓 무료 궁합/재회운 서비스 시작: {} & {}", person1Name, person2Name);

		Long resultId = null;

		try {
			// 1. [DB] 초기 정보 저장 (CompatibilityResult 생성)
			String p1Ilgan = extractIlgan(person1Response);
			String p2Ilgan = extractIlgan(person2Response);

			CompatibilityResult result = sajuResultService.updateCompatibilityInitialStatus(
				paymentId, person1Name, p1Ilgan, person2Name, p2Ilgan
			);
			resultId = result.getId();

			// 2. 알림 (선택사항)
			try {
				discordNotificationService.sendCompatibilityRequestNotification(person1Name,
					person1Response.getInput().getSolarDate().toString(), person2Name,
					person2Response.getInput().getSolarDate().toString());
			} catch (Exception e) {
			}

			// 3. 프롬프트 생성 (재회운 등 카테고리별 로직 자동 적용)
			String userPrompt = createCompatibilityPromptBySubcategory(
				subcategoryId, person1Name, person1Response, person2Name, person2Response,
				null, null
			);

			// 4. GPT 호출
			String requestBody = objectMapper.writeValueAsString(
				new Gpt5Request("gpt-5.2", GPT5_SYSTEM_INSTRUCTION + userPrompt, 16384, "high",
					"high")
			);

			log.info("GPT 궁합(무료) API 호출 중...");
			String gptResponse = gptApiRetryService.callGptApiWithRetry(requestBody);

			GptCompatibilityResponse gptData = objectMapper.readValue(
				extractContentFromResponseGpt5(gptResponse), GptCompatibilityResponse.class);

			// 5. [DB] 결과 저장
			CompatibilityResult savedResult = sajuResultService.saveCompatibilityFinalResult(
				resultId, gptData.getInterpretation(), gptData.getScore(), gptData.getSummary()
			);

			// 6. 후처리 (OG이미지 등)
			ogImageGenerationService.generateAndUploadOgImage(savedResult);

		} catch (Exception e) {
			log.error("무료 궁합 분석 오류: {}", e.getMessage(), e);
			if (resultId != null) {
				sajuResultService.rollbackCompatibilityStatus(resultId);
			}
		}
	}

	private void appendHyeanPersonaHeader(StringBuilder prompt) {
		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 30년 경력의 사주명리 전문가입니다.\n");
		prompt.append("사주 데이터를 바탕으로 명확하고 실용적인 조언을 제공합니다.\n\n");

		prompt.append("### 1. 작성 원칙 ###\n");
		prompt.append("1. **명확성 우선**: 사주 용어를 쓰지 않고 일반인이 이해할 수 있는 말로 설명\n");
		prompt.append("2. **간결함**: 핵심만 전달하고 불필요한 수사 제거\n");
		prompt.append("3. **어조**: 전문가가 정중하게 설명하는 톤 (해요체 기본, 중요 정보는 합니다체)\n\n");

		prompt.append("### 2. 절대 금지 ###\n");
		prompt.append("- 사주 전문 용어를 그대로 노출 (편인, 비견 등을 괄호 안에 쓰지 말 것)\n");
		prompt.append("- 과한 비유나 은유 (자연물 비유는 필수일 때만 1회)\n");
		prompt.append("- 구어체 남발 (~거든요/~잖아요는 전체에서 각 2회 이내)\n");
		prompt.append("- 장황한 서론/결론\n\n");

		prompt.append("### 3. 분량 제한 ###\n");
		prompt.append("- 불필요한 반복이나 예시는 과감히 삭제\n\n");

		prompt.append("### 4. 용어 해석 가이드라인 ###\n");
		prompt.append("**사주 용어는 맥락에 따라 다르게 해석하세요. 기계적으로 1:1 대응하지 마세요.**\n\n");

		prompt.append("**[신강/신약]**\n");
		prompt.append("- 신강(身强): 자기 세력이 강함\n");
		prompt.append("  * 성격: 주관이 뚜렷, 독립적, 고집 셈, 타협 어려움\n");
		prompt.append("  * 직업: 리더십 발휘, 자영업/전문직 유리, 지시받기 싫어함\n");
		prompt.append("  * 단점: 독단적, 주변 의견 무시, 외로움\n");
		prompt.append("  * 조언: \"혼자 다 하려 말고 협력하세요\"\n\n");

		prompt.append("- 신약(身弱): 자기 세력이 약함\n");
		prompt.append("  * 성격: 유연함, 적응 잘함, 눈치 빠름, 우유부단\n");
		prompt.append("  * 직업: 조직 생활 적합, 팀워크 좋음, 서포트 역할\n");
		prompt.append("  * 단점: 주관 없음, 휘둘림, 번아웃\n");
		prompt.append("  * 조언: \"내 의견을 확실히 표현하세요\"\n\n");

		prompt.append("**[충(沖)]**\n");
		prompt.append("- 의미: 정면충돌, 180도 반대 기운\n");
		prompt.append("  * 환경: 이사, 이직, 이별 등 큰 변화\n");
		prompt.append("  * 관계: 끌리지만 부딪힘, 애증의 관계\n");
		prompt.append("  * 심리: 불안정, 조급함, 결단의 시기\n");
		prompt.append("  * 긍정: 돌파구, 새 출발, 정체 탈출\n");
		prompt.append("  * 부정: 사고, 이별, 갈등, 건강 악화\n\n");

		prompt.append("**[합(合)]**\n");
		prompt.append("- 의미: 결합, 융합, 끌어당김\n");
		prompt.append("  * 환경: 안정, 정착, 파트너십 형성\n");
		prompt.append("  * 관계: 자연스러운 인연, 편안함, 케미\n");
		prompt.append("  * 심리: 소속감, 협력 욕구\n");
		prompt.append("  * 긍정: 결혼운, 사업 파트너, 귀인\n");
		prompt.append("  * 부정: 묶임, 속박, 자유 제한, 집착\n\n");

		prompt.append("**[형(刑)]**\n");
		prompt.append("- 의미: 은밀한 갈등, 내적 압박\n");
		prompt.append("  * 심리: 죄책감, 자책, 숨긴 스트레스\n");
		prompt.append("  * 관계: 서운함 누적, 표현 못하는 불만\n");
		prompt.append("  * 건강: 만성 질환, 신경성 통증\n\n");

		prompt.append("**[파(破)]**\n");
		prompt.append("- 의미: 파괴, 붕괴, 예상 못한 사고\n");
		prompt.append("  * 환경: 급작스러운 손실, 계획 틀어짐\n");
		prompt.append("  * 관계: 갑작스러운 이별, 배신감\n\n");

		prompt.append("**[해(害)]**\n");
		prompt.append("- 의미: 방해, 견제, 은근한 피해\n");
		prompt.append("  * 관계: 시샘, 질투, 뒤에서 헐뜯기\n");
		prompt.append("  * 환경: 발목 잡히는 일, 방해꾼 등장\n\n");

		prompt.append("**[적용 예시]**\n");
		prompt.append("❌ 나쁜 예: \"월지와 일지가 충이라 변화가 많습니다\"\n");
		prompt.append(
			"✅ 좋은 예: \"태어날 때부터 환경이 자주 바뀌는 운명이에요. 이사도 많이 다니고, 한곳에 정착하기보다 새로운 곳을 찾아 떠나는 삶이 맞습니다. 안정을 추구하면 오히려 답답해질 수 있어요.\"\n\n");
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
			"2. **자연스러운 전문가 어조**: '해요체'를 기본으로 쓰되, 전문 정보 전달 시 '입니다' 체를 혼용하여 신뢰감과 친근함을 모두 전달합니다.\n");
		prompt.append("3. **깊이 있는 통찰**: 관계를 피상적으로 다루지 않고, 명리학적 근거를 바탕으로 심층 분석합니다.\n\n");
		prompt.append(
			"4. ** 사주 용어를 최대한 자제해주세요. 읽는 사람이 글에 몰입할 수 있도록 이야기의 흐름이 자연스럽게 이어져야합니다. \n\n");

		prompt.append("### 2. 금지 사항 (Strict Prohibitions) ###\n");
		prompt.append("- 데이터 나열, 글에서 AI티가 나면 절대 안됨\n\n");

		prompt.append("### 3. 작성 스타일 (공통) ###\n");
		prompt.append(
			"- **자연스러운 전문가 어조**: '해요체'와 '입니다' 체를 자연스럽게 혼용하여 신뢰감과 친근함을 전달해주세요.\n");
		prompt.append("- **깊이 우선**: 각 항목을 매우 구체적이고 깊이 있게 분석해주세요. 분량 제한은 없습니다.\n");

		prompt.append("\n### 4. [매우 중요] 용어 사용 절대 규칙 ###\n");
		prompt.append(
			"1. **전문 용어 사용 금지**: '궁합', '원진살', '귀문관살', '충', '합' 같은 전문 용어를 웬만하면 직접 쓰지 마세요.\n");
		prompt.append("2. **관계성 언어로 치환**: 사주 용어 대신 **'두 사람의 관계'**를 묘사하는 말로 바꾸세요.\n");
		prompt.append("   - 충(沖) -> 서로 다른 매력, 강렬한 스파크, 조율이 필요한 부분\n");
		prompt.append("   - 합(合) -> 찰떡 호흡, 안정적인 느낌, 서로에게 스며드는\n");
		prompt.append("   - 원진/귀문 -> 애증의 관계, 묘한 긴장감, 서로에게 집착하게 되는\n");
		prompt.append(
			"3. **괄호 사용 금지**: '(충)' 처럼 괄호 쓰지 말고, \"두 분은 서로 정반대의 성향이라 오히려 강렬하게 끌립니다\" 처럼 서술하세요.\n\n");

		prompt.append("\n### ⚠️ [필수 작성 지침] - 이름 표기 규칙 ###\n");
		prompt.append("1. **제공된 캐릭터의 이름은 절대로 임의로 줄이거나 변경하지 마세요.**\n");
		prompt.append("2. 본문에 이름을 언급할 때는 반드시 입력받은 '전체 이름'을 그대로 사용하세요.\n");
	}

	private void appendSajuJsonResponseFormat(StringBuilder prompt, String name) {
		prompt.append("\n\n### 9. [최종 출력 형식] (JSON) ###\n");

		prompt.append("⚠️ **[JSON 출력 필수 규칙]**\n");
		prompt.append("1. 반드시 **순수 JSON만** 출력하세요. Markdown 코드 블록(```json) 절대 금지.\n");
		prompt.append("2. JSON 문자열 내부의 **모든 줄바꿈**은 반드시 `\\n`으로 이스케이프하세요.\n");
		prompt.append("3. JSON 문자열 내부의 **쌍따옴표**는 반드시 `\\\"`로 이스케이프하세요.\n");
		prompt.append("4. 출력 예시:\n");
		prompt.append("{\n");
		prompt.append("  \"fullAnalysis\": \"첫 번째 문단입니다.\\n\\n두 번째 문단입니다.\",\n");
		prompt.append("  \"summary\": \"요약입니다\\n줄바꿈도 \\\\n으로 표시\"\n");
		prompt.append("}\n\n");

		prompt.append("위에서 요청된 모든 분석을 완료한 후, **반드시 markdown 감싸기 없이 순수한 JSON 형식으로만** 응답해주세요.\n");
		prompt.append(
			"**fullAnalysis** 값에는 위에서 요청한 모든 상세 분석 내용을 **목록 기호 없이 물 흐르듯 자연스럽게 이어진 하나의 긴 텍스트**로 담아야 합니다.\n");

		prompt.append("--- [fullAnalysis 작성 규칙] ---\n");
		prompt.append("1. **(매우 중요)** 프롬프트에 `##`로 시작하는 주제(제목)가 있으면, `##` 기호는 **절대 출력하지 마세요.**\n");
		prompt.append("2. 대신, 그 주제(제목) 텍스트를 **대괄호(`[]`)**로 감싸고, 그 뒤에 **줄바꿈(\\n)**을 한 번만 추가해주세요.\n");
		prompt.append("   (예시: `## 1. 핵심 성격` -> [핵심 성격]\\n)\n");
		prompt.append(
			"3. **(매우 중요)** 프롬프트에 `**`로 감싸진 단어(강조)는, `**` 기호 없이 **그냥 텍스트**로만 출력해주세요. (굵게 표시 금지)\n");
		prompt.append(
			"**[JSON 문법 절대 엄수]** JSON 값(value) 안에서 줄바꿈을 할 때는 반드시 이스케이프 문자(`\\n`)를 사용해야 합니다.\n");
		prompt.append("절대로 키보드 엔터키(Line Break)를 사용하여 실제 줄바꿈을 넣지 마세요. 시스템 에러가 발생합니다.\n");
		prompt.append("4. 한 문단이 6~7줄을 넘으면 안됨.\n");
		prompt.append("5. 목록 기호(-, *, 1.) 사용 금지, 자연스러운 문장으로 연결\n");

		prompt.append("--- [summary 말투 규칙 - 매우 중요] ---\n");
		prompt.append("**summary는 '혜안' 페르소나를 완전히 무시하고, 아래 규칙만 100% 따라야 합니다.**\n\n");

		prompt.append("🎯 **필수 규칙 (절대 엄수)**\n");
		prompt.append(
			"1. **페르소나**: 당신은 다정하고 통찰력 있는 조언자입니다. **무조건 '해요체'(~해요, ~하네요)를 사용하여 정중하게** 요약해주세요. 반말은 절대 금지입니다.\n");
		prompt.append("2. **주제 (총평)**: 이 사람 사주에 대한 **'핵심 총평'**을 해줘. 성격, 재능, 매력 같은 거 찝어서.\n");
		prompt.append("3. **줄바꿈**: 한 문장이 끝나면 **반드시 줄바꿈(\\n)** 해주고, 마침표는 찍지 마.\n");
		prompt.append("4. **분량**: 총 250자 이내.\n");

		prompt.append("{\n");
		prompt.append(
			"  \"fullAnalysis\": \"<여기에 상세 분석 전체 내용을 작성. 상세 분석 전체 내용 작성할때 보기 편하게 문단을 잘 나눠야함>\",\n");
		prompt.append(
			"  \"summary\": \"<문장 끝마다 '\\n'으로 줄바꿈된 250자 이내 요약본 작성>\"\n");
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
			"1. **페르소나 (가장 중요)**: 당신은 두 사람의 관계를 응원하는 따뜻한 상담가입니다. **무조건 '해요체'(~해요, ~하네요)를 사용하여 정중하고 다정하게** 요약해주세요. **반말은 절대 금지**입니다.\n");
		prompt.append(
			"2. **주제 (총평)**: 두 사람의 **'궁합 총평'**을 해줘. 둘의 케미에 대한 내용, 서로 조심해야할 부분, 결혼 한다면, 언제가 좋을지.\n");
		prompt.append("3. **줄바꿈**: 한 문장이 끝나면 **반드시 줄바꿈(\\n)** 해주고, 마침표는 찍지 마.\n");
		prompt.append("4. **분량**: 총 250자 이내.\n");

		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 종합 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<상세 궁합 분석 내용>\",\n");
		prompt.append(
			"  \"summary\": \"<문장 끝마다 '\\n'으로 줄바꿈된 250자 이내 요약본 작성>\"\n");
		prompt.append("}\n");
	}

	// ==================== 프롬프트 라우팅 메서드 ====================
	private String createPromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response, String sourceTitle) {

		if (subcategoryId == 9) {
			return createCharacterSajuPrompt(name, response, sourceTitle);
		}

		return switch (subcategoryId.intValue()) {
			case 1 -> createLifeOverallPrompt(name, response);
			case 2 -> createPersonalityAnalysisPrompt(name, response);
			case 3 -> createCareerAptitudePrompt(name, response);
			case 5 -> createIdolAnalysisPrompt(name, response);
			case 13 -> createActorAnalysisPrompt(name, response);
			case 17 -> createLoveLuckPrompt(name, response); // 연애운
			case 18 -> createNewYear2026Prompt(name, response); // 신년 운세
			case 20 -> createMoneyLuckPrompt(name, response);
			case 21 -> createBusinessLuckPrompt(name, response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
		};
	}

	private String createCompatibilityPromptBySubcategory(
		Long subcategoryId,
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response,
		String person1SourceTitle,
		String person2SourceTitle
	) {
		if (subcategoryId == 10) {
			return createCharacterCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response, person2SourceTitle);
		}
		if (subcategoryId == 11) {
			return createCharacterToCharacterCompatibilityPrompt(
				person1Name, person1Response, person1SourceTitle,
				person2Name, person2Response, person2SourceTitle
			);
		}

		return switch (subcategoryId.intValue()) {
			case 4, 6 -> createLoveStoryPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 7 -> createIdolCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 8 -> createTriangleRelationshipPrompt(person1Name, person1Response, person2Name,
				person2Response);
			case 14 -> createLoveStoryPrompt(person1Name, person1Response, person2Name,  // ← 추가
				person2Response);
			case 15 ->
				createActorCompatibilityPrompt(person1Name, person1Response, person2Name,  // ← 추가
					person2Response);
			case 19 -> createReunionPrompt(person1Name, person1Response, person2Name,  //
				person2Response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다: " + subcategoryId);
		};
	}

	private String createFreePromptBySubcategory(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {
		return switch (subcategoryId.intValue()) {
			case 101 -> create2026ChangesPrompt(name, response);
			case 102 -> create2026KeywordPrompt(name, response);
			case 103 -> createFlirtingPrompt(name, response);
			case 104 -> createChemistryMatchPrompt(name, response);
			case 105 -> createTodayFortunePrompt(name, response);
			case 106 -> createMarchMonthlyFortunePrompt(name, response);
			default -> throw new IllegalArgumentException("지원하지 않는 카테고리입니다.");
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

		appendKeywords(prompt, response);

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
			" 일간, 월지, 오행 분포, 십성 구조를 종합하여 %s님의 핵심 기질과 성격 형성 과정을 '비유'를 통해 깊이 있게 분석해주세요. 만세력 기반으로 자세하게 분석하되, 쉽고 재미있게 풀어서 설명해주세요\n");
		prompt.append(
			"지장간에 숨겨진 '내면의 DNA'와 무의식적 동기까지 파헤쳐, 다층적인 성격 구조를 설명해주세요. 만세력 기반으로 자세하게 분석하되, 쉽고 재미있게 풀어서 설명해주세요\n\n");

		prompt.append("## 사주에 숨겨진 매력\n");
		prompt.append(
			"**이 부분이 가장 중요합니다.** %s님이 가진 신살(도화, 홍염, 화개, 역마, 귀인 등)이나 특수 기운을 찾아내어, 이것이 현대 사회에서 어떤 **'강력한 무기'**가 되는지 설명해주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append("예를 들어:\n");
		prompt.append("- **도화/홍염:** 사람을 끌어당기는 치명적인 매력이나 인기, 연예인 같은 끼가 있는지.\n");
		prompt.append("- **역마:** 글로벌하게 활동하거나 여행을 통해 운이 트이는 활동성인지.\n");
		prompt.append("- **화개:** 예술적 재능이나 화려함 뒤의 고독, 혹은 종교/철학적 깊이인지.\n");
		prompt.append("- **귀인:** 결정적인 순간에 나를 도와주는 인복이 있는지.\n");
		prompt.append(
			"**주의:** '도화살이 있다'라고 딱딱하게 말하지 말고, \"가만히 있어도 시선을 사로잡는 묘한 아우라가 있습니다\"와 같이 **풀어서 재미있게 묘사**해주세요.\n\n");

		prompt.append("## 직업과 사회적 성공의 길\n");
		prompt.append(
			"%s님이 일에서 '도파민'을 느끼는 순간은 언제인지, 타고난 재능(식상, 관인 등)을 기반으로 분석해주세요. \n");
		prompt.append(
			" %s님의 핵심 재능(십성, 신살 등을 참고)은 무엇이며, 어떤 분야(구체적인 직업 3개정도만 제시)에서 가장 빛을 발할 수 있는지 명확히 제시해주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요. 단어의 나열은 절대 금지\n");

		prompt.append("## 재물운의 흐름과 경제적 안정\n");
		prompt.append(" %s님의 타고난 재물운, 앞으로 어떻게 해야하는지, 어떻게 노력해야하는지, 투자 성향, 투자 어떻게 해야하는지 등\n");

		prompt.append("## 연애와 결혼의 인연\n");
		prompt.append(
			"%s님의 연애 스타일, 매력 포인트, 이상적인 배우자상('일지' 비유 활용)을 상세히 그려주세요. 만세력 기반으로 설명하되 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append(
			"%s님이 끌리는 스타일, 본인의 이상형, 실제로 이상형을 만나는가 ? 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append("연애/결혼 가능성이 높은 시기와 만남의 방식 예측, 행복한 관계를 오래 유지하기 위한 비결 조언.\n\n");

		prompt.append("## 6. 나의 뿌리와 열매 (부모·형제·자식운)\n");
		prompt.append(
			"가족 관계를 통해 %s님의 인복을 분석합니다. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append("- **부모운:** 부모님의 덕을 많이 볼 수 있는 사주인지, 아니면 자수성가해야 하는 사주인지 (초년운과 연계).\n");
		prompt.append("- **형제/동료운:** 주변 형제나 친구들이 나를 돕는 귀인인지, 내 것을 뺏어가는 경쟁자인지.\n");
		prompt.append(
			"- **자식운:** 말년을 책임질 **자식복**에 대해 설명해주세요. 자식이 효자인지, 똑똑한지, 혹은 자식으로 인한 근심이 있을 수 있는지 솔직하지만 부드럽게 풀어주세요.\n\n");

		prompt.append("## 7. 대운과 세운: 인생의 파도 타기\n");

		prompt.append("## 대운과 세운 - 인생의 큰 파도\n");
		prompt.append(
			"**[현재 대운 집중 분석]** 지금 겪고 있는 현재 대운(10년)은 %s님 인생에서 어떤 '챕터'이며, 이 시기의 주요 과제와 기회는 무엇인지 집중 분석해주세요. 그리고 어떻게 행동해야하는지까지 분석 해주세요. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
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

		appendKeywords(prompt, response);

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

		String solarDate = input.getSolarDate().toString();
		String solarTime = input.getSolarTime().toString();

		String formattedDate = solarDate.substring(0, 4) + "년 "
			+ solarDate.substring(5, 7) + "월 "
			+ solarDate.substring(8, 10) + "일";
		String formattedTime = solarTime.substring(0, 2) + "시 "
			+ solarTime.substring(3, 5) + "분";

		// 1. 페르소나
		appendHyeanPersonaHeader(prompt);

		// 2. 상세 정보
		prompt.append("### 5. 분석 대상자 상세 정보 (Data for Analysis) ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// 3. 절대 기준
		appendKeywords(prompt, response);

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

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 5. 아이돌 최애 분석 프롬프트 (혜안 적용) ====================
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

		appendKeywords(prompt, response);

		// 4. [분석 요청]
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

		prompt.append("## 아이돌의 타고난 기질, 성격, 인성, 그룹 내 역할)\n");
		prompt.append(String.format(
			"(일간, 월지, 십성 분포를 바탕으로 %s님의 근본적인 성격과 인성을 심층 분석해주세요. 만세력 기반으로 설명하되, 알아듣기 쉽도록 풀어서 재미있게 설명해주세요.)\n",
			name));
		prompt.append(
			"(만약 팀이라면, 이 사람의 성격이 팀 내에서 어떻게 작용할지, 어떤 역할(리더형, 조율자형, 마이웨이형 등)을 맡을지도 함께 예측해주세요.)\n\n");
		prompt.append(
			"(아이돌로서 보여지는 것과 달리, 이 사람의 실제 성격은 어떤지, 끼를 타고난 아이돌인가 아니면 노력형인가, 만세력 기반으로 분석 하되 알아듣기 쉽도록 풀어서 재미있게 설명해주세요.)\n\n");

		prompt.append("## 병크 및 리스크 예측\n");
		prompt.append("(사주 원국과 신살, 운의 흐름을 볼 때, 이 아이돌이 아이돌 활동 중 가장 조심해야 할 '병크'나 리스크는 무엇인가요?)\n");
		prompt.append(
			"(예: 구설수, 건강 문제, 이성 문제 등. 흉살이나 충/형을 근거로 설명하되, 알아듣기 쉽게 풀어서 설명해주세요.)\n\n");

		prompt.append("## 아이돌이 아니었다면? (타고난 재능)\n");
		prompt.append(
			"(사주에 나타난 핵심 재능(식상, 인성, 관성 등)을 바탕으로, 아이돌이 아니었다면 어떤 직업에서 성공 했을지, 1~2가지 구체적으로 분석해주세요.)\n\n");

		prompt.append("## 연애관 및 이상형 (가장 마지막)\n");
		prompt.append("(팬들이 궁금해하는 부분입니다. 이 사람의 연애 스타일, 본능적으로 끌리는 이상형(외모, 성격)을 솔직하게 분석해주세요.)\n");
		prompt.append(
			"(이 사람의 어떤 부분이 이성에게 매력으로 다가올지에 대해서 설명 (외적인 것, 내적인 것 만세력 기반으로 분석하되 알아듣기 쉽고 편하게 설명해주세요.))\n");
		prompt.append("(결혼은 언제쯤 할지 정확한 년도 예측, 배우자궁(일지)의 모습은 어떤지도 포함해주세요.)\n\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 13. 배우 분석 프롬프트 ====================
	private String createActorAnalysisPrompt(String name, ManseryeokCalculationResponse response) {
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
		prompt.append("이 분석은 '%s'라는 제3자(배우)에 대한 것입니다.\n");
		prompt.append("쉼표, 마침표, '-', 이런 표현 최대한 줄여주세요. AI가 작성한 글이라는 티가 나면 안됩니다.\n");
		prompt.append(
			"절대로 2인칭(%s님, 당신)을 사용하지 말고, **'그는', '그녀는', '%s님은', '이 사람은'** 등 3인칭 관찰자 시점으로만 서술해야 합니다.\n\n");

		// 3. 분석 대상자 정보 주입
		prompt.append("### 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		appendKeywords(prompt, response);

		// 4. 분석 요청
		prompt.append("\n### [배우 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 배우 '%s'님의 사주를 팬의 관점에서 **아래 요청된 순서대로** 깊이 있게 분석해주세요.\n", name));

		prompt.append(
			"**[가장 중요!]** 말투는 **'~입니다', '~네요', '~로군요', '~이군요' 같은 따뜻하고 명료한 말투**를 사용하세요.\n");
		prompt.append(
			"AI가 쓴 것 같은 뻔한 서론/결론, 억지 비유는 절대 쓰지 마세요. 부드럽고, 심각하지 않게, '발견한 사실'을 설명하는 방식이어야 합니다.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 기운을 지니셨습니다.\"와 같이 자연스럽게 분석을 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 배우의 타고난 기질, 성격, 인성, 작품 선택 능력)\n");
		prompt.append(String.format(
			"(일간, 월지, 십성 분포를 바탕으로 %s님의 근본적인 성격과 인성을 심층 분석해주세요. 만세력 기반으로 자세하게 설명하되, 보는 사람이 알아 듣기 편하게 쉽고 재미있게 풀어서 설명해주세요.)\n",
			name));
		prompt.append(
			"(배우로서 어떤 작품을 선택하는지, 작품에 임하는 태도가 어떤지, 대중들에게 인기 많은 이유가 무엇인지? 어떤 점에 대중들이 매료됐는지 설명)"
				+ "만세력 기반으로 자세하고 구체적으로 설명하되, 사람들이 이해하기 쉽게 재미있게 풀어서 설명 \n\n");
		prompt.append(
			"(촬영장에서 스태프들과의 사이가 어떨지, 상대 배우나 다른 배우들과의 사이가 어떨지(현장 케미) 실제 이 배우의 성격이 어떨지에 대해서 만세력 기반으로 자세하게 설명하되 알아듣기 쉽고 재미있게 풀어서 설명해주세요.)\n\n");

		prompt.append("## 병크 및 리스크 예측\n");
		prompt.append("(사주 원국과 신살, 운의 흐름을 볼 때, 이 배우가 연예 활동 중 가장 조심해야 할 '병크'나 리스크는 무엇인가요?)\n");
		prompt.append(
			"(예: 구설수, 건강 문제, 이성 문제 등. 흉살이나 충/형을 근거로 설명하되, 알아듣기 쉽게 풀어서 재미있게 설명해주세요.)\n\n");

		prompt.append("## 배우가 아니었다면? (타고난 재능)\n");
		prompt.append(
			"(사주에 나타난 핵심 재능(식상, 인성, 관성 등)을 바탕으로, 배우가 아니었다면 어떤 직업에서 성공 했을지, 어떤 직업이 잘 어울리는지, 1~2가지 구체적으로 분석해주세요.)\n\n");

		prompt.append("## 연애관 및 이상형 (가장 마지막)\n");
		prompt.append("(팬들이 궁금해하는 부분입니다. 이 사람의 연애 스타일, 본능적으로 끌리는 이상형(외모, 성격)을 솔직하게 분석해주세요.)\n");
		prompt.append("(결혼은 언제쯤 할지 정확한 년도 예측, 배우자궁(일지)의 모습은 어떤지도 포함해주세요.)\n\n");

		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 17. 연애운 ====================
	private String createLoveLuckPrompt(String name, ManseryeokCalculationResponse response) {
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
		appendHyeanPersonaHeader(prompt);

		// 2. 분석 대상자 정보 주입
		prompt.append("### 5. 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		appendKeywords(prompt, response);

		// 3. 분석 요청
		prompt.append("\n### 6. [연애운 심층 분석] 요청 ###\n");
		prompt.append(String.format(
			"혜안 선생님, 위 데이터를 바탕으로 %s님의 '사랑과 연애'에 대한 모든 것을 **아래 5가지 핵심 주제**로 깊이 있게 풀어주세요.\n", name));
		prompt.append("단순한 위로보다는, 사주 원국에 나타난 기질과 운의 흐름을 냉철하면서도 따뜻하게 분석해주세요.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s님은 %s %s에 태어나신, [일간(%s) 자연물 비유]와 같은 사랑을 하시는군요.\" 로 시작해주세요.\n\n",
			name, formattedDate, formattedTime,
			saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle()));

		prompt.append("## 타고난 연애 세포\n");
		prompt.append(
			"일간과 월지, 그리고 '도화살/홍염살' 등의 신살을 확인하여 %s님이 가진 고유의 매력 포인트가 무엇인지 분석하되, 이해하기 쉽게 풀어서 재미있게 설명해주세요.\n");
		prompt.append(
			"%s님은 연애할 때 어떤 스타일인가요? 만세력 기반으로 분석하되, 쉽고 재미있게 풀어서 설명.\n");
		prompt.append(
			"이성이 %s님을 볼 때 가장 매력적으로 느끼는 부분과, 반대로 질려할 수 있는 단점을 솔직하게 말해주세요. 만세력 기반으로 대상자의 성격, 장점, 단점 등을 자세하게 설명하되 쉽고 재미있게 풀어서 설명해주세요.\n\n");

		prompt.append("## 나의 이상형과 운명적인 상대\n");
		prompt.append(
			"**일지(배우자궁)**에 있는 글자와 십성을 분석하여, %s님이 본능적으로 끌리는 이성은 어떤 스타일인지 설명해주세요.\n");
		prompt.append(
			"실제로 %s님에게 자꾸 꼬이는 이성들의 특징은 어떤가요? (나쁜 남자/여자가 꼬이는지, 능력자가 꼬이는지 등)\n");
		prompt.append(
			"**[운명적인 상대방 예측]** %s님의 사주에 가장 잘 맞는 '진정한 사랑'의 특징을 아래 항목에 맞춰 풀어서 설명해주세요:\n");
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
			"사주 원국에서 연애를 방해하는 요소(예: 무관/무재, 관살혼잡, 고란살, 식상과다 등)가 있다면 솔직하게 지적해주세요.\n");
		prompt.append(
			"연애만 하면 반복되는 문제 패턴이 있나요? (집착, 의심, 금방 식음 등)\n\n");

		prompt.append("## 2026년 연애운, 연애 타이밍\n");
		prompt.append(
			"**2026년(병오년)** 세운을 분석하여, 솔로라면 언제쯤 인연이 들어올지(몇 월?), 커플이라면 관계가 어떻게 변할지 예측해주세요.\n");
		prompt.append("결혼을 한다면 언제일지, 구체적인 년도를 추천해주세요\n\n");

		prompt.append("## 연애 코칭 및 조언\n");
		prompt.append(
			"%s님의 사주에 부족한 오행을 채워줄 수 있는 데이트 장소, 행운의 컬러 추천\n");
		prompt.append("마지막으로 사랑 때문에 고민하는 %s님을 위한 따뜻한 응원의 한마디.\n\n");

		// JSON 포맷 추가
		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// 18. 신년 운세
	private String createNewYear2026Prompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		// ===== [0단계] 페르소나 주입 =====
		appendHyeanPersonaHeader(prompt);

		// ===== [1단계] 분석 대상자 정보 =====
		prompt.append("\n### 5. 분석 대상자 상세 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response, 2026);

		// ===== [2단계] 절대 기준(Fact) 주입 =====
		appendKeywords(prompt, response);

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
		prompt.append("**[중요] 지금은 2025년 말이거나 2026년 초입니다. 년도를 지칭할 때 반드시 '2026년'이라고 명시하세요.**\n\n");

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

		prompt.append("**✅ 체크리스트 (모두 충족했는지 확인)**\n");
		prompt.append("□ 2026년 병오년 에너지와 사용자 사주의 **화학반응**을 설명했는가?\n");
		prompt.append("□ 추상적 표현 대신 **구체적 행동 키워드**를 3개 이상 사용했는가?\n");
		prompt.append("□ '절대 기준'의 용신, 신강/신약 정보를 **반드시** 반영했는가?\n");
		prompt.append("□ 분량이 **최소 8문장 이상**인가? (짧으면 돈값 못함)\n\n");

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

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 재성(정재/편재) 개수를 반영했는가?\n");
		prompt.append("□ '~하면 좋아요' 말고 '~해야 합니다' 수준의 **강한 조언**이 있는가?\n");
		prompt.append("□ 분량 **최소 6문장** 이상인가?\n\n");

		// 직장/사업운
		prompt.append("### [직장/사업운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **커리어 변화 가능성**: 관성(官星) 유무와 대운 흐름을 보고 ");
		prompt.append("승진, 이직, 창업의 **실제 가능성**을 명확히 제시하세요.\n");
		prompt.append("- **업무 스타일 조언**: 사주 강약과 십성 분포를 보고 ");
		prompt.append("'혼자 책임지고 끌고 가는 스타일' vs '협업으로 시너지 내는 스타일' 중 **어느 쪽**인지 ");
		prompt.append("명확히 판단하고 그에 맞는 **전략**을 제시하세요.\n");
		prompt.append("- **타이밍**: '상반기 집중' vs '하반기 결실'처럼 **시기적 전략**을 짚어주세요.\n\n");

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 관성(정관/편관), 식상(식신/상관) 분포를 반영했는가?\n");
		prompt.append("□ '역할이 늘어난다', '기회가 온다' 같은 **구체적 상황**을 묘사했는가?\n");
		prompt.append("□ 분량 **최소 6문장** 이상인가?\n\n");

		// 가정/건강운
		prompt.append("### [가정/건강운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **신체 부위 특정**: 오행 편중이나 충 관계를 보고 ");
		prompt.append("**구체적인 신체 부위**(소화기, 수면, 근육, 피부 등)를 2~3개 짚어주세요.\n");
		prompt.append("- **생활 리듬 조언**: '수면 시간 고정', '카페인 조절' 같은 ");
		prompt.append("**즉시 실천 가능한 행동**을 3가지 이상 제시하세요.\n");
		prompt.append("- **가족 관계**: 육친(부모, 형제, 배우자) 관련 변화가 있을지 예측하고 ");
		prompt.append("**도윤님 샘플처럼** '중심을 잡아주는 역할' 같은 구체적 표현을 쓰세요.\n\n");

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 오행 과다/부족에 따른 **신체 취약점**을 명시했는가?\n");
		prompt.append("□ '스트레스 관리'처럼 추상적 말 대신 **구체적 행동**을 제시했는가?\n");
		prompt.append("□ 분량 **최소 5문장** 이상인가?\n\n");

		// 이성/대인관계
		prompt.append("### [이성/대인관계]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **연애운 흐름**: 식상, 재성, 관성의 조합을 보고 ");
		prompt.append("'새로운 인연' vs '기존 관계 심화' vs '정리의 시기' 중 **어느 쪽**인지 판단하세요.\n");
		prompt.append("- **이상형 힌트**: 용신 오행을 활용해 ");
		prompt.append("'차분하고 책임감 있는 사람', '활발하고 즉흥적인 사람' 같은 **구체적 특징**을 제시하세요.\n");
		prompt.append("- **대인 전략**: 사주 강약을 보고 ");
		prompt.append("'선택과 집중' vs '네트워킹 확장' 중 **어느 전략**이 유리한지 조언하세요.\n\n");

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 도화살, 역마살 등 신살 정보를 반영했는가?\n");
		prompt.append("□ '인연이 온다'는 말만 하지 않고 **어떤 타입**의 인연인지 구체적으로 묘사했는가?\n");
		prompt.append("□ 분량 **최소 5문장** 이상인가?\n\n");

		// 학업/성취운
		prompt.append("### [학업/성취운]\n");
		prompt.append("**📌 작성 지침**\n");
		prompt.append("- **학습 스타일**: 인성(정인/편인) 유무와 강도를 보고 ");
		prompt.append("'단기 집중' vs '장기 루틴'형인지 판단하고 **구체적 공부법**을 제시하세요.\n");
		prompt.append("- **시험/자격증 타이밍**: 월별 세운을 참고해 ");
		prompt.append("'상반기 집중' vs '하반기 결실' 같은 **시기 전략**을 조언하세요.\n");
		prompt.append("- **성과 예측**: 식상과 관성의 조합을 보고 ");
		prompt.append("'결과가 천천히 쌓이는 해' vs '단기 성과가 가능한 해'인지 명확히 하세요.\n\n");

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 인성(정인/편인) 분포를 반영했는가?\n");
		prompt.append("□ '루틴으로 이기는 해' 같은 **핵심 전략 키워드**가 있는가?\n");
		prompt.append("□ 분량 **최소 4문장** 이상인가?\n\n");

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

		prompt.append("**✅ 각 월별 체크리스트**\n");
		prompt.append("□ 해당 월의 천간지지와 사용자 사주의 **충/합/형** 관계를 확인했는가?\n");
		prompt.append("□ '조심하세요'만 말하지 않고 **구체적 이유와 대응책**을 제시했는가?\n");
		prompt.append("□ 각 월마다 **최소 3문장** 이상 서술했는가?\n\n");

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

		prompt.append("**✅ 체크리스트**\n");
		prompt.append("□ 사용자의 **고유한 강점**을 1~2개 명확히 언급했는가?\n");
		prompt.append("□ '조급해하지 말라'는 메시지를 **사주 근거**와 함께 전달했는가?\n");
		prompt.append("□ 분량이 **최소 5문장** 이상인가?\n");
		prompt.append("□ **[필수]** 마지막 문장: \"새해 복 많이 받으시고 항상 행복하세요. 네임드사주가 응원하겠습니다.\"\n\n");

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
		appendSajuJsonResponseFormat(prompt, name);

		return prompt.toString();
	}

	// ==================== 20. 돈벼락(재물운) 분석 프롬프트 (v6 - 자연문단형) ====================
	private String createMoneyLuckPrompt(String name, ManseryeokCalculationResponse response) {
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
		appendPersonDetailInfo(prompt, name, response);
		appendKeywords(prompt, response);
		prompt.append("\n");

		appendMoneyLuckJsonResponseFormat(prompt);

		return prompt.toString();
	}

	// 21. 사업운 분석 프롬프트
	private String createBusinessLuckPrompt(String name, ManseryeokCalculationResponse response) {
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
		prompt.append("읽는 사람이 아 맞아 나 그래 하고 소름이 돋을 정도로 구체적이어야 한다.\n\n");

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
		prompt.append("- ~이라 ~해요 패턴을 연속으로 반복하는 문장 금지\n");
		prompt.append("- 연속 2문장 이상이 같은 어미(해요/입니다)로 끝나는 패턴 금지\n");
		prompt.append("- 쉼표 4개 이상으로 길게 연결한 문장 금지\n");
		prompt.append(
			"- 사주 용어를 풀이 없이 단독 사용 금지 (편인, 겁재, 상관, 정관, 편관, 식신, 정재, 편재, 비견, 정인 모두 해당. 처음 등장 시 반드시 1문장 이상 풀이. 두 번째부터는 생략 가능)\n");
		prompt.append("- 사주 근거 없이 결론만 던지는 문장 금지 (예: 추진력이 강합니다 → 왜? 어디서?)\n");
		prompt.append("- 색깔/방향/숫자 개운법 금지 (청색, 동쪽, 3과 8 등)\n");
		prompt.append("- 본문 중간에 오늘 할 일은, 지금 당장, 바로 적용할 같은 즉시행동 유도 금지 (마지막 문단에서만 허용)\n\n");

		prompt.append("### 권장 서술 패턴 ###\n");
		prompt.append("사주 구조를 밝히고, 그게 이 사람의 성격/습관에서 어떻게 드러나는지 묘사하고, 사업 현장에서 어떤 장면으로 나타나는지 그려준다.\n");
		prompt.append("비유와 구체적 장면 묘사를 적극 활용한다. 예: 통장에 돈이 찍히면 마음이 커지고, 같이 하자는 제안에 쉽게 끌려요.\n");
		prompt.append("문장 길이를 섞어 리듬을 만든다. 짧은 문장, 설명 문장, 묘사 문장을 교차한다.\n");
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
		appendPersonDetailInfo(prompt, name, response);
		appendKeywords(prompt, response);
		prompt.append(
			"※ 위 데이터의 수치값은 내부 판단용이다. 최종 본문(fullAnalysis)에는 점수/개수를 직접 쓰지 말고 강약 경향으로만 표현한다.\n");
		prompt.append("\n");

		prompt.append("시점 표기는 yyyy년 M월 형식만 사용하고 일/시간/분/초/T 문자는 절대 쓰지 않는다.\n\n");

		appendBusinessJsonResponseFormat(prompt);
		return prompt.toString();
	}

	private void appendBusinessJsonResponseFormat(StringBuilder prompt) {
		prompt.append("\n\n### 최종 출력 형식 (JSON) ###\n");
		prompt.append("반드시 순수 JSON 객체만 출력한다. markdown 코드블록 금지.\n");
		prompt.append("JSON 문자열 내부 줄바꿈은 반드시 \\\\n으로 이스케이프한다.\n");
		prompt.append("fullAnalysis에는 번호형 목차, 대괄호 제목, 목록 기호 없이 순수 문장 단락만 작성한다.\n");
		prompt.append("fullAnalysis의 단락 구분은 \\\\n\\\\n만 사용한다.\n");
		prompt.append("문단 내부에서 문장별 줄바꿈은 금지하고, 한 문단은 자연스러운 연속 문장으로 작성한다.\n");
		prompt.append("전환 문장 없이 단락을 끊지 말고 앞 단락의 의미를 다음 단락으로 연결한다.\n");
		prompt.append("다음 표기 금지: [PAGE_BREAK], [1.], 1-1, 1), ##, ###, -, *.\n");
		prompt.append("첫째는, 둘째는, 셋째는 같은 번호성 전개는 금지한다.\n");
		prompt.append("한자 직접 표기, 색/방향/숫자 개운법 추천은 금지한다.\n");
		prompt.append("summary는 4~5줄로 작성하고, 핵심 행동만 짧게 정리한다.\n");
		prompt.append("summary 총 길이는 280자 이내로 제한한다.\n");
		prompt.append("기간 표기는 yyyy년 M월 형식만 허용한다.\n");
		prompt.append("출력 스키마:\n");
		prompt.append("{\n");
		prompt.append("  \"fullAnalysis\": \"...\",\n");
		prompt.append("  \"summary\": \"...\"\n");
		prompt.append("}\n");
	}

	private void appendMoneyLuckJsonResponseFormat(StringBuilder prompt) {
		prompt.append("\n\n### 최종 출력 형식 (JSON) ###\n");
		prompt.append("반드시 순수 JSON 객체만 출력한다. markdown 코드블록 금지.\n");
		prompt.append("JSON 문자열 내부 줄바꿈은 반드시 \\\\n으로 이스케이프한다.\n");
		prompt.append("fullAnalysis에는 번호형 라벨(A., 1., 첫째), 대괄호 제목([ ... ]), 목록 기호(-, *)를 쓰지 않는다.\n");
		prompt.append("fullAnalysis의 문단 구분은 \\\\n\\\\n만 사용한다.\n");
		prompt.append("fullAnalysis 길이는 3800자 이상 4600자 이하를 지킨다.\n");
		prompt.append("summary는 4~5줄로 작성하고 총 길이는 280자 이내로 제한한다.\n");
		prompt.append("출력 스키마:\n");
		prompt.append("{\n");
		prompt.append("  \"fullAnalysis\": \"...\",\n");
		prompt.append("  \"summary\": \"...\"\n");
		prompt.append("}\n");
	}

	// ==================== 4,6,14 러브 스토리 프롬프트 (혜안 적용) ====================
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

		// --- 사람 1 ---
		prompt.append("--- 첫 번째 사람 정보: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);

		// 🔥 [추가 1] Person 1 팩트 주입
		appendKeywords(prompt, person1Response);

		// --- 사람 2 ---
		prompt.append("\n--- 두 번째 사람 정보: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		// 🔥 [추가 2] Person 2 팩트 주입
		appendKeywords(prompt, person2Response);

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

		// 🔥 [추가 1] Person 1 팩트 주입
		appendKeywords(prompt, person1Response);

		prompt.append("\n--- 두 번째 아이돌: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		appendKeywords(prompt, person2Response);

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

	// ==================== 9. 캐릭터 사주 프롬프트 (혜안 적용) ====================
	private String createCharacterSajuPrompt(String name, ManseryeokCalculationResponse response,
		String sourceTitle) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		String dayIlgan = saju.getDaySky().getKorean() + saju.getDaySky().getFiveCircle(); // 예: 갑목

		// 1. 혜안 페르소나
		appendHyeanPersonaHeader(prompt);

		// 2. 캐릭터 설정 주입
		prompt.append("### ⚠️ 캐릭터 분석 모드 ###\n");
		prompt.append(String.format("이 사주는 작품 **'%s'**에 등장하는 캐릭터 **'%s'**의 사주입니다.\n",
			sourceTitle != null ? sourceTitle : "알 수 없는 작품", name));
		prompt.append("캐릭터의 원작 설정(성격, 작중 행적)과 사주 풀이를 연결하여, '이 캐릭터가 왜 이런 운명을 가졌는지' 설명해주세요.\n\n");

		// 3. 사주 정보
		prompt.append("### 5. 캐릭터 사주 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		appendKeywords(prompt, response);

		// 4. 분석 요청
		prompt.append("\n### 6. [캐릭터 사주 심층 분석] 요청 ###\n");
		prompt.append("원작의 내용과 사주 명리학을 결합하여 다음 항목들을 '해요체'로 재미있게 분석해주세요.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(
			String.format("\"작품 '%s'의 '%s'님은 [%s 자연물 비유]와 같은 기운을 타고나셨군요.\"로 시작\n\n", sourceTitle,
				name, dayIlgan));

		prompt.append("## 사주로 본 캐릭터에 대한 분석\n");
		prompt.append(
			"작중에서 보여주는 성격과 실제 사주(일간, 십성)의 싱크로율을 분석해주세요. 만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append(
			"왜 그런 행동을 했는지, 사주적 근거(충, 합, 신살 등)를 들어 설명해주세요. 만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n\n");

		prompt.append("## 작중 시련과 운명의 흐름\n");
		prompt.append(
			"캐릭터가 겪은 주요 사건이나 시련이 사주상 어떤 기운 때문이었는지 해석해주세요. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n\n");

		prompt.append("## 매력 포인트\n");
		prompt.append("만세력을 바탕으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.");
		prompt.append(
			"팬들이 사랑하는 이 캐릭터의 치명적인 매력(도화살, 홍염살, 화개살 등)은 무엇인가요? 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n\n");

		prompt.append("## 최애와의 로맨스\n");
		// 1. 대시 스타일 (적극/소극)
		prompt.append(String.format(
			"1. **사랑에 빠지는 과정**: %s님은 좋아하는 사람이 생기면 불도저처럼 직진하는 스타일인가요, 아니면 멀리서 지켜보며 신중하게 다가가는 스타일인가요? 사주(식상, 재성, 관성 등)를 근거로 분석해주세요.\n",
			name));

		// 2. 질투와 집착
		prompt.append(String.format(
			"2. **질투와 소유욕**: %s님의 질투 레벨은 어느 정도일까요? (겉으로는 쿨하지만 속은 타들어가는 타입, 대놓고 질투하는 타입, 집착광공 재질 등). 만세력의 기운을 바탕으로 상상력을 더해 묘사해주세요.\n",
			name));

		// 3. 연상/연하/동갑 취향
		prompt.append(String.format(
			"3. **잘 어울리는 관계**: 사주 구성상 %s님은 본인을 리드해주는 '연상', 본인이 챙겨줘야 하는 '연하', 친구 같은 '동갑' 중 누구와 가장 합이 좋을까요? 그 이유도 알려주세요.\n",
			name));

		// 4. 연애 시 모습
		prompt.append(String.format(
			"4. **연인이 된다면?**: %s님과 연애를 한다면 어떤 데이트를 하고 어떤 말을 해줄까요? (다정다감한 사랑꾼, 무심한 듯 챙겨주는 츤데레 등). 팬들이 설렐 수 있는 구체적인 상황을 예시로 들어주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n\n",
			name));

		prompt.append("## 현실 세계에 산다면?\n");
		prompt.append(
			"이 캐릭터가 지금 한국에 산다면 어떤 직업(MBTI)과 라이프스타일을 가졌을지 상상해주세요. 만세력 기반으로 설명하되, 쉽고 재미있게 풀어서 설명해주세요.\n");
		prompt.append("현실에서의 연애 스타일과 이상형도 예측해주세요. 만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명\n\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	// 10번 나와 캐릭터의 궁합
	// 10번: 나와 캐릭터의 궁합 (3단계 구조: 나 -> 캐릭터 -> 궁합)
	private String createCharacterCompatibilityPrompt(
		String userName, ManseryeokCalculationResponse userSaju,
		String charName, ManseryeokCalculationResponse charSaju,
		String sourceTitle
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 혜안 궁합 페르소나
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 상황 설정 (드림/이입)
		prompt.append("### ⚠️ '나'와 '최애'의 심층 연애 시뮬레이션 ###\n");
		prompt.append(
			String.format("사용자('%s')가 작품 **'%s'**의 캐릭터 **'%s'**와의 연애 궁합을 의뢰했습니다.\n", userName,
				sourceTitle, charName));
		prompt.append(
			"단순한 분석글이 아니라, **사용자가 주인공이 된 한 편의 로맨스 소설**을 읽는 듯한 **엄청난 몰입감과 풍부한 분량**을 제공하세요.\n");
		prompt.append("**각 챕터마다 최소 5문장 이상** 서술하고, 상황 묘사와 감정선을 아주 디테일하게 풀어써야 합니다.\n"); // 분량 강제
		prompt.append("※ 캐릭터 이름 유지 필수: 입력된 풀네임을 그대로 사용하세요.\n\n");

		// 3. 사주 정보
		prompt.append("\n### 3. 분석 대상 정보 ###\n");
		prompt.append(String.format("--- 캐릭터 (최애): %s (%s) ---\n", charName, sourceTitle));
		appendPersonDetailInfo(prompt, charName, charSaju);

		// 🔥 [추가 1] 캐릭터의 절대 기준(Fact) 주입
		appendKeywords(prompt, charSaju);

		prompt.append("\n--- 사용자 (나): ").append(userName).append(" ---\n");
		appendPersonDetailInfo(prompt, userName, userSaju);

		// 🔥 [추가 2] 나의 절대 기준(Fact) 주입
		appendKeywords(prompt, userSaju);

		// 4. 분석 구조
		prompt.append("\n### 4. [분석 구조] ###\n");
		prompt.append(String.format("1. 주인공 %s님(나)의 연애 DNA 분석\n", userName));
		prompt.append(String.format("2. 최애 %s의 숨겨진 내면과 연애관\n", charName));
		prompt.append(String.format("3. %s X %s의 로맨스 서사 (궁합 시뮬레이션)\n\n", userName, charName));

		// ===== 5. 1단계: 사용자(나) 분석 =====
		prompt.append(String.format("### 5. [1단계: 주인공 '%s'님(나) 집중 탐구] ###\n", userName));
		prompt.append("먼저 사용자의 사주를 분석하여, 이 로맨스 소설의 '주인공'으로서 어떤 매력을 가졌는지 분석해주세요.\n\n");

		prompt.append("타고난 분위기와 매력 포인트\n");
		prompt.append(String.format(
			"- %s님은 태생적으로 어떤 아우라(일간/오행)를 풍기는 사람인가요? [자연물 비유]를 들어 설명해주세요.\n", userName));
		prompt.append("- 이성을 끌어당기는 결정적인 매력(도화, 홍염 등)이나 성격적 장점은 무엇인가요?\n\n");

		prompt.append("연애 스타일\n");
		prompt.append("- 사랑에 빠지면 직진하는 타입인가요, 아니면 신중하게 지켜보는 타입인가요? (십성 근거)\n");
		prompt.append("- 연인에게 바라는 가장 중요한 가치는 무엇인가요? (안정감, 설렘, 대화 등)\n\n");

		prompt.append("내 사주가 말하는 '운명의 상대'\n");
		prompt.append("- 일지(배우자궁)를 볼 때, %s님은 본능적으로 어떤 스타일의 이성에게 끌리나요?\n\n");

		prompt.append("이상형\n");
		prompt.append("- 이 캐릭터가 본능적으로 끌릴 수밖에 없는 상대의 분위기, 성격, 외모를 아주 상세하게 묘사해주세요.\n\n");

		// ===== 6. 2단계: 캐릭터 분석 =====
		prompt.append(String.format("### 6. [2단계: 최애 '%s' 집중 탐구] ###\n", charName));
		prompt.append("캐릭터의 원작 성격과 사주(일간, 십성, 신살)를 연결하여 아주 구체적으로 분석해주세요.\n\n");

		prompt.append("타고난 기질과 은밀한 매력\n");
		prompt.append("- 겉으로 보이는 성격 뒤에 숨겨진 내면의 모습은 무엇인가요? (지장간, 신살 활용하여 분석)\n");
		prompt.append("- 원작에서 보여준 행동들이 사주의 어떤 글자에서 비롯되었는지 구체적으로 연결해서 설명해주세요.\n\n");

		prompt.append("연애 스타일: 사랑에 빠진 모습\n");
		prompt.append("- 평소 모습과 달리, 사랑하는 사람 앞에서는 어떻게 변할까요? (구체적인 행동 묘사 필수)\n");
		prompt.append("- 집착, 질투, 혹은 회피? 사주 십성(관성, 재성 등)을 근거로 디테일하게 묘사해주세요.\n\n");

		prompt.append("절대적인 이상형\n");
		prompt.append("- 이 캐릭터가 본능적으로 끌릴 수밖에 없는 상대의 분위기, 성격, 외모를 아주 상세하게 묘사해주세요.\n\n");

		// ===== 7. 3단계: 궁합 시뮬레이션 (핵심) =====
		prompt.append(String.format("\n### 7. [3단계: %s X %s의 로맨스 서사] ###\n", userName, charName));
		prompt.append("**가장 중요한 파트입니다. 두 사람의 만남을 눈앞에 그려지듯 생생하게 서술하세요.**\n\n");

		prompt.append("--- [분석 시작] ---\n");

		prompt.append("## 운명적 이끌림: 너는 내 취향일까?\n");
		prompt.append(String.format(
			"- **[교차 검증]** 앞서 분석한 **캐릭터의 이상형**에 %s님(사용자)이 얼마나 부합하며, 반대로 **사용자의 이상형**에 캐릭터가 얼마나 부합하는지 설명해주세요.\n",
			userName));
		prompt.append(String.format(
			"- %s(캐릭터)는 %s님(사용자)의 어떤 매력 포인트(도화살, 특정 오행 등)에 시선을 뺏길까요?\n", charName, userName));
		prompt.append("- **[상황 묘사]** 두 사람이 처음 마주치는 순간, 캐릭터가 사용자에게 건넬 첫마디나 속마음을 상상해서 적어주세요.\n\n");

		prompt.append("## 연애시의 온도: 케미\n");
		prompt.append(
			"- **[관계성]** 친구 같은 연인? 아니면 긴장감 넘치는 어른의 연애? 두 사람의 오행과 십성 관계를 통해 분위기를 묘사하세요.\n");
		prompt.append(
			"- **[데이트]** 두 사람이 데이트를 한다면 어디를 가고 무엇을 할까요? 사주 성향에 맞는 구체적인 데이트 코스를 추천하고 장면을 묘사해주세요.\n");
		prompt.append("- **[스킨십/애정표현]** 서로의 애정 표현 방식은 잘 맞을까요? 누가 더 적극적일까요?\n\n");

		prompt.append("## 공략 : 마음을 얻는 방법\n");
		prompt.append(String.format("- %s(캐릭터)의 마음을 확실하게 얻기 위한 '필살기(행동 지침)'를 2~3가지 구체적으로 조언해주세요.\n",
			charName));
		prompt.append("- 반대로, 절대 해서는 안 되는 행동(지뢰)은 무엇인가요?\n\n");

		prompt.append("## 혜안의 총평\n");
		prompt.append("- 이 커플의 서사를 한 줄로 요약한다면?\n");
		prompt.append("- 사용자의 '덕질'이 행복한 결말(성덕)을 맺을 수 있도록 응원의 메시지를 남겨주세요.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, userName, charName);

		return prompt.toString();
	}

	// 11번 캐릭터와 캐릭터 궁합.
	private String createCharacterToCharacterCompatibilityPrompt(
		String char1Name, ManseryeokCalculationResponse char1Saju, String char1Source,
		String char2Name, ManseryeokCalculationResponse char2Saju, String char2Source
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. 혜안 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 상황 설정 (팬픽/크로스오버 관점 강화)
		prompt.append("\n### ⚠️ 캐릭터 관계성(Chemistry) 심층 분석 ###\n");
		prompt.append(
			String.format(
				"- 이 분석은 작품 **'%s'**의 **'%s'**와 작품 **'%s'**의 **'%s'** 간의 가상 궁합(Coupling)입니다.\n",
				char1Source, char1Name, char2Source, char2Name));
		prompt.append("- 단순한 분석글이 아니라, **두 캐릭터의 서사(Narrative)를 완성하는 고퀄리티 관계 분석글**을 작성하세요.\n");
		prompt.append(
			"- 팬들이 이 글을 읽고 '이 주식은 된다(This ship is real)'라고 느낄 수 있도록 **몰입감과 분량을 극대화**해야 합니다.\n");
		prompt.append(
			"- **각 챕터마다 최소 5문장 이상** 서술하고, 상황 묘사(If)를 적극적으로 활용하세요. 말이 자연스럽게 이어지도록 글을 구성하세요.\n");

		// 3. 분석 대상자들 정보 주입
		prompt.append("\n### 3. 분석 대상 캐릭터 정보 ###\n");
		prompt.append(String.format("--- 캐릭터 1: %s (%s) ---\n", char1Name, char1Source));
		appendPersonDetailInfo(prompt, char1Name, char1Saju);

		// 🔥 [추가 1] 캐릭터 1의 팩트 주입
		appendKeywords(prompt, char1Saju);

		prompt.append(String.format("\n--- 캐릭터 2: %s (%s) ---\n", char2Name, char2Source));
		appendPersonDetailInfo(prompt, char2Name, char2Saju);

		// 🔥 [추가 2] 캐릭터 2의 팩트 주입
		appendKeywords(prompt, char2Saju);

		// 4. 분석 구조 설명
		prompt.append("\n### 4. [분석 구조] ###\n");
		prompt.append(String.format("1. %s (%s)의 연애관과 기질\n", char1Name, char1Source));
		prompt.append(String.format("2. %s (%s)의 연애관과 기질\n", char2Name, char2Source));
		prompt.append("3. 두 캐릭터의 '케미스트리'와 '관계 서사' (핵심)\n\n");

		// ===== 5. 1단계: 캐릭터 1 분석 =====
		prompt.append(String.format("### 5. [1단계: '%s' 캐릭터성 분석] ###\n", char1Name));
		prompt.append(
			"캐릭터의 원작 성격과 사주(일간, 십성)를 연결하여 연애 스타일을 분석해주세요.\n\n");

		prompt.append("타고난 기질과 숨겨진 욕망\n");
		prompt.append(
			String.format("- %s의 겉모습과 달리 내면에 숨겨진 욕망이나 결핍은 무엇인가요? (지장간, 신살 활용)\n", char1Name));
		prompt.append("- 원작의 행동 패턴이 사주의 어떤 글자와 일치하는지 구체적으로 연결해주세요.\n\n");

		prompt.append("연애 스타일\n");
		prompt.append("- 연애를 할 때 리드하는 타입인가요, 아니면 챙김 받는 타입인가요? 사주 십성을 근거로 분석해주세요.\n");
		prompt.append("- 집착, 회피, 헌신 등 사랑에 빠졌을 때 나타나는 특징을 묘사해주세요.\n\n");

		// ===== 6. 2단계: 캐릭터 2 분석 =====
		prompt.append(String.format("### 6. [2단계: '%s' 캐릭터성 분석] ###\n", char2Name));
		prompt.append("마찬가지로 두 번째 캐릭터의 사주를 통해 연애 스타일을 분석해주세요.\n\n");

		prompt.append("타고난 기질과 숨겨진 욕망\n");
		prompt.append(String.format("- %s의 겉모습과 달리 내면에 숨겨진 욕망이나 결핍은 무엇인가요?\n", char2Name));
		prompt.append("- 원작의 성격이 사주의 어떤 부분에서 기인했는지 설명해주세요.\n\n");

		prompt.append("연애 스타일: 공(Top)인가 수(Bottom)인가?\n");
		prompt.append("- 관계를 주도하는 성향인가요, 맞춰주는 성향인가요?\n");
		prompt.append("- 이 캐릭터가 사랑을 표현하는 고유한 방식(말/행동/돈/희생 등)은 무엇인가요?\n\n");

		// ===== 7. 3단계: 두 캐릭터의 궁합 (여기가 핵심) =====
		prompt.append(
			String.format("\n### 7. [3단계: %s X %s 관계성] ###\n", char1Name, char2Name));
		prompt.append("**가장 중요한 파트입니다. 두 캐릭터가 엮이는 장면을 눈앞에 보이듯 생생하게 서술하세요.**\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append(String.format(
			"\"%s와 %s의 조합이라니... 마치 [비유]처럼 [어떤 분위기]의 서사가 펼쳐지겠네요.\"로 시작\n\n",
			char1Name, char2Name));

		prompt.append("## 첫 만남과 비주얼 합\n");
		prompt.append(
			"- **[상황 묘사]** 두 캐릭터가 처음 마주친다면 어떤 장면일까요? (긴장감? 호기심? 무관심?) 구체적인 상황을 상상해서 묘사해주세요.\n");
		prompt.append("- 서로의 일간(日干) 기운으로 볼 때, 첫눈에 끌릴까요 아니면 부딪힐까요?\n\n");

		prompt.append("## 관계의 역학\n");
		prompt.append("- 두 사람의 관계성을 한 단어로 정의한다면? (예: 배틀 연애, 상호 구원, 집착과 도망 등)\n");
		prompt.append(
			"- **[주도권 싸움]** 사귄다면 누가 관계의 주도권(기강)을 잡게 될까요? 사주의 '관성'과 '비겁' 세력을 비교해서 분석해주세요.\n");
		prompt.append(
			String.format("- %s의 이상형 조건에 %s가 얼마나 부합하는지, 반대는 어떤지 교차 검증해주세요.\n\n", char1Name,
				char2Name));

		prompt.append("## 갈등과 위기\n");
		prompt.append("- 두 사람 사이에 발생할 수 있는 가장 치명적인 갈등(위기) 상황은 무엇인가요? (오해, 가치관 차이, 집착 등)\n");
		prompt.append("- **[상황 묘사]** 갈등 상황에서 서로에게 어떤 상처 주는 말을 할지, 혹은 어떻게 행동할지 구체적으로 묘사해주세요.\n");
		prompt.append("- 이 갈등을 해결하고 해피엔딩으로 가기 위해 서로에게 필요한 것은 무엇인가요?\n\n");

		prompt.append("## 혜안의 한 줄 평\n");
		prompt.append("- 이 커플의 궁합을 한 문장으로 정의한다면? (예: '세계관 최강자들의 자존심 강한 사랑')\n");
		prompt.append("- 팬들에게 이 조합을 '먹어볼 만한지(츄라이)' 영업하는 멘트로 마무리.\n\n");

		appendCompatibilityJsonResponseFormat(prompt, char1Name, char2Name);

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

	// ==================== 15. 배우 궁합 프롬프트 ====================
	private String createActorCompatibilityPrompt(
		String person1Name,
		ManseryeokCalculationResponse person1Response,
		String person2Name,
		ManseryeokCalculationResponse person2Response
	) {
		StringBuilder prompt = new StringBuilder();

		// 1. '혜안' 궁합 페르소나 주입
		appendHyeanCompatibilityPersonaHeader(prompt);

		// 2. 3인칭 서술 지시 (배우용으로 수정)
		prompt.append("\n### ⚠️ 매우 중요: 3인칭 서술 (배우 팬픽 관점) ###\n");
		prompt.append(
			String.format("- 이 분석은 '%s'와 '%s'라는 제3자(배우)들에 대한 것입니다.\n", person1Name, person2Name));
		prompt.append("- 절대로 2인칭(당신, 당신들)을 사용하지 마세요.\n");
		prompt.append(
			String.format("- [개인 분석]은 '%s님은...', '%s님은...' 처럼 3인칭 단수로 서술해야 합니다.\n", person1Name,
				person2Name));
		prompt.append("- [궁합 분석]은 '두 사람은...', '%s님과 %s님은...' 처럼 3인칭 관찰자 시점으로 서술해야 합니다.\n");
		prompt.append("- 팬들의 상상력을 자극할 수 있는 서사적이고 감성적인 어조를 사용해주세요.\n");

		// 3. 분석 대상자들 정보 주입
		prompt.append("\n### 3. 분석 대상자 상세 정보 ###\n");
		prompt.append("--- 첫 번째 배우: ").append(person1Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person1Name, person1Response);
		appendKeywords(prompt, person1Response);

		prompt.append("\n--- 두 번째 배우: ").append(person2Name).append(" ---\n");
		appendPersonDetailInfo(prompt, person2Name, person2Response);

		appendKeywords(prompt, person2Response);

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

	private String createReunionPrompt(
		String person1Name, ManseryeokCalculationResponse person1,
		String person2Name, ManseryeokCalculationResponse person2) {
		StringBuilder prompt = new StringBuilder();

		String todayDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));

		// ==========================================
		// 0. 시스템 페르소나
		// ==========================================
		prompt.append("==================================================\n");
		prompt.append("🔥 [긴급 경고] 현재 시점: ").append(todayDate).append("\n");
		prompt.append("- 모든 시기 예측은 지금 이후의 미래만 언급하세요\n");
		prompt.append("- 이미 지나간 년/월을 추천하면 즉시 재작성 명령이 내려집니다\n");
		prompt.append("==================================================\n\n");

		prompt.append("### 💔 당신의 정체성: 재회 전문 명리학자 혜안 ###\n\n");

		prompt.append("이 상담료는 14,000원입니다.\n");
		prompt.append("내담자가 읽고 나서 이렇게 느끼게 만들어야 합니다:\n");
		prompt.append("- 와, 이 사람 내 속까지 다 들여다보네\n");
		prompt.append("- 헤어진 이유를 이렇게까지 정확하게 짚어주다니\n");
		prompt.append("- 이 정도 분석이면 14,000원 하나도 안 아깝다\n\n");

		prompt.append("**당신의 3가지 강점**\n");
		prompt.append("1. 냉정한 통찰력 - 거짓 희망을 주지 않습니다. 안 되면 안 된다고 말합니다\n");
		prompt.append("2. 날카로운 심리 분석 - 두 사람의 속마음을 거의 도청하듯 정확하게 짚어냅니다\n");
		prompt.append("3. 구체적인 솔루션 - 추상적 조언이 아니라 실행 가능한 방법을 제시합니다\n\n");

		prompt.append("**절대 금지 사항**\n");
		prompt.append("❌ 추상적인 조언 (소통하세요, 이해하세요 같은 뻔한 말)\n");
		prompt.append("❌ 사주 용어 나열 (일지 충이 있어서~ 이런 거 설명하려다 말고 바로 해석으로)\n");
		prompt.append("❌ 희망고문 (가망 없으면 차라리 새 인연 찾으세요라고 말하기)\n");
		prompt.append("❌ 작은따옴표 과다 사용 (AI 티가 나므로 간접화법 사용)\n");
		prompt.append("❌ 획일적인 구조 (Case 1, Case 2 같은 틀에 박힌 표현 지양)\n");
		prompt.append("❌ 대화 예시를 대본처럼 작성 (A: 안녕 B: 응 형식 금지, 서술형으로)\n\n");

		prompt.append("**글쓰기 스타일**\n");
		prompt.append("- 기본은 해요체, 핵심 조언할 때는 단호하게 합니다체 사용\n");
		prompt.append("- 공감 표현을 자연스럽게 활용하세요 (아마도, 그쵸, 그랬을 거예요)\n");
		prompt.append("- 대화 예시는 직접 인용 대신 간접화법으로 서술하세요\n");
		prompt.append("  예시) ❌ A: 지금 뭐해? B: 바빠\n");
		prompt.append("       ✅ 지금 뭐 하는지 계속 물어봤고, 상대방은 바쁘다고 대답했을 겁니다\n");
		prompt.append("- 중요한 부분은 반복해서 강조하세요\n\n");

		// ==========================================
		// 1. 데이터 주입
		// ==========================================
		prompt.append("### 📋 분석 대상자 정보 ###\n\n");
		prompt.append(String.format("【신청자 (마음 아픈 사람): %s님】\n", person1Name));
		appendPersonCalculationInfo(prompt, person1);
		appendKeywords(prompt, person1);

		prompt.append(String.format("\n【상대방 (그리운 사람): %s님】\n", person2Name));
		appendPersonCalculationInfo(prompt, person2);
		appendKeywords(prompt, person2);

		// ==========================================
		// 2. 분석 구조 가이드
		// ==========================================
		prompt.append("\n### 📝 분석 구조 가이드 ###\n\n");
		prompt.append("총 4개 장으로 구성하되, 각 장의 분량과 표현 방식은\n");
		prompt.append("사주 데이터와 내담자의 상황에 맞춰 유연하게 조정하세요.\n");
		prompt.append("획일적인 템플릿이 아니라 이 두 사람만의 고유한 이야기로 풀어내야 합니다.\n\n");

		prompt.append("**제1장: 헤어진 진짜 이유** (권장 1500자 이상)\n");
		prompt.append("표면적 이유와 사주가 말하는 근본 원인을 분석하세요.\n");
		prompt.append("갈등이 어떻게 시작되고 반복됐는지 구체적으로 서술하되,\n");
		prompt.append("대화를 대본처럼 쓰지 말고 간접화법으로 자연스럽게 풀어내세요.\n");
		prompt.append("사주 데이터를 바탕으로 하되, 체크리스트 형식이 아니라 흐름 있는 서술로 작성하세요.\n\n");

		prompt.append("**제2장: 지금 상대방의 마음** (권장 1200자 이상)\n");
		prompt.append("위로가 아니라 냉정한 현실을 말하세요.\n");
		prompt.append("현재 상대방의 운세, 새 인연 가능성, 지금 연락하면 어떻게 될지\n");
		prompt.append("사주 근거와 함께 솔직하게 분석하세요.\n");
		prompt.append("듣기 싫어도 사실대로 말해야 합니다.\n\n");

		prompt.append("**제3장: 재회 가능성 및 타이밍** (권장 1500자 이상)\n");
		prompt.append("별점(★)으로 가능성을 평가하고 구체적 근거를 제시하세요.\n");
		prompt.append("재회 골든타임 3개를 년/월로 명시하되, 반드시 ").append(todayDate).append(" 이후의 미래여야 합니다.\n");
		prompt.append("타이밍을 놓치면 어떻게 되는지도 명확히 경고하세요.\n");
		prompt.append("데드라인도 명시하세요.\n\n");

		prompt.append("**제4장: 재회 실전 매뉴얼** (권장 1500자 이상)\n");
		prompt.append("재회 전 준비, 첫 연락 방법, 만남 후 행동, 재회 후 지속 전략을\n");
		prompt.append("상대방의 사주 성향에 맞춰 구체적으로 제시하세요.\n");
		prompt.append("첫 연락 예시는 3개 이상 제시하되, Case 1/2/3 형식보다는\n");
		prompt.append("상대방 성향에 따른 자연스러운 흐름으로 풀어내세요.\n");
		prompt.append("상대방 반응별 대처법도 시뮬레이션하세요.\n\n");

		// ==========================================
		// 제1장: 헤어진 진짜 이유
		// ==========================================
		prompt.append("==================================================\n");
		prompt.append("📖 제1장: 헤어진 진짜 이유\n");
		prompt.append("==================================================\n\n");

		prompt.append("이 장은 내담자가 아, 맞아, 진짜 그랬어라고 고개를 끄덕이게 만들어야 합니다.\n");
		prompt.append("단순히 성격 차이라고 하지 말고, 구체적인 갈등 흐름을 자연스럽게 재현하세요.\n\n");

		prompt.append("먼저 표면적으로 보이는 이유를 언급하세요.\n");
		prompt.append(
			String.format("아마도 %s님은 성격 차이, 가치관 차이, 바빠서 연락이 뜸해져서 같은 이유를 떠올릴 겁니다.\n", person1Name));
		prompt.append("하지만 사주를 보면 이건 표면적인 핑계일 뿐이에요.\n\n");

		prompt.append("**사주가 말하는 진짜 원인을 분석하세요** (최소 800자)\n\n");

		prompt.append(String.format("먼저 %s님의 사주를 깊이 분석하세요:\n", person1Name));
		prompt.append("- 일간을 보고 이 사람의 본질적 성향을 파악하세요\n");
		prompt.append("- 오행 편중이 있다면 그것이 연애에서 어떻게 드러나는지 설명하세요\n");
		prompt.append("  (예: 화 과다는 급한 성격, 수 과다는 우울감과 의심 등)\n");
		prompt.append("- 십성 구조를 보고 관계에서의 패턴을 분석하세요\n");
		prompt.append("  (예: 관성 과다는 통제 욕구, 재성 과다는 결과 집착 등)\n");
		prompt.append("- 일지(배우자궁)를 보고 연애할 때 무의식적으로 드러나는 태도를 설명하세요\n\n");

		prompt.append("사주 용어를 나열하지 말고, 그 용어가 실제 연애에서 어떤 행동으로 나타나는지\n");
		prompt.append("구체적으로 서술하세요. 내담자가 공감할 수 있게 써야 합니다.\n\n");

		prompt.append(String.format("다음으로 %s님의 사주를 같은 방식으로 분석하세요.\n", person2Name));
		prompt.append("이 사람의 일간, 오행, 십성, 일지를 보고\n");
		prompt.append(String.format("왜 이 사람은 %s님의 그런 태도를 견딜 수 없었는지 연결하세요.\n\n", person1Name));

		prompt.append("**두 사람의 갈등이 반복된 방식을 서술하세요**\n\n");

		prompt.append("사주를 바탕으로 두 분의 갈등이 어떤 패턴으로 시작됐는지 분석하세요.\n");
		prompt.append("중요: 대화를 직접 인용하지 말고 간접화법으로 서술하세요.\n\n");

		prompt.append("예를 들어:\n");
		prompt.append(String.format("- %s님은 연락이 뜸하거나 답장이 늦어질 때마다 불안해했을 겁니다\n", person1Name));
		prompt.append("- 지금 누구랑 있는지, 왜 연락이 늦는지 계속 궁금해하면서 질문이 많아졌을 거예요\n");
		prompt.append("- 본인은 걱정과 관심의 표현이라고 생각했지만\n");
		prompt.append("- 상대방은 그 질문들이 감시처럼 느껴지면서 점점 숨이 막혔을 겁니다\n\n");

		prompt.append(String.format("%s님 입장도 서술하세요:\n", person2Name));
		prompt.append("- 왜 자꾸 확인하는지, 왜 믿어주지 않는지 답답함이 쌓였을 거예요\n");
		prompt.append("- 정말 바쁘거나 힘든 상황에서도 설명해야 한다는 게 부담이었겠죠\n");
		prompt.append("- 방어적인 태도가 나오면서 대화가 점점 어려워졌을 겁니다\n\n");

		prompt.append("감정이 격해진 후의 패턴도 설명하세요:\n");
		prompt.append("- 한쪽은 즉시 해결하고 싶어 하고, 한쪽은 거리를 두고 싶어 했을 거예요\n");
		prompt.append("- 해결의 타이밍이 계속 엇갈리면서 두 사람 모두 지쳐갔습니다\n");
		prompt.append("- 이런 악순환이 반복되면서 결국 이별에 이르렀어요\n");
		prompt.append("- 사랑이 식어서가 아니라, 사랑하는 방식이 너무 달라서 견디기 힘들어진 거죠\n\n");

		prompt.append("**결정타를 날린 시기 분석** (대운/세운)\n");
		prompt.append(String.format("%s님과 %s님의 대운/세운을 보고\n", person1Name, person2Name));
		prompt.append("이별이 확정됐을 가능성이 높은 시기를 추정하세요.\n");
		prompt.append("충(沖)이나 형(刑) 같은 사주 작용을 근거로 들되,\n");
		prompt.append("전문 용어보다는 쉬운 말로 풀어서 설명하세요.\n\n");

		// ==========================================
		// 제2장: 지금 상대방의 마음
		// ==========================================
		prompt.append("==================================================\n");
		prompt.append("💭 제2장: 지금 상대방의 마음\n");
		prompt.append("==================================================\n\n");

		prompt.append(
			String.format("가장 궁금한 거, 알죠? 지금 %s님이 %s님을 생각하고 있는지.\n\n", person2Name, person1Name));

		prompt.append("이 파트는 위로가 아니라 냉정한 현실을 말해줘야 합니다.\n");
		prompt.append("내담자가 듣기 싫어도, 사실대로 말하세요.\n\n");

		prompt.append("**현재 상대방의 운세를 분석하세요**\n\n");

		prompt.append(String.format("%s님의 현재 대운을 보고:\n", person2Name));
		prompt.append("- 지금 애정운이 강한지 (새로운 사람 만났을 가능성)\n");
		prompt.append("- 지금 직업/재물운이 강한지 (연애 신경 쓸 여유 없음)\n");
		prompt.append("- 지금 고난운/정리운인지 (과거를 돌아볼 수 있는 시기)\n");
		prompt.append("이 중 어느 상태인지 판단하고 근거를 제시하세요.\n\n");

		prompt.append("**솔직한 진단을 내리세요**\n\n");

		prompt.append("사주를 보고 현재 상대방이 어떤 상태인지 명확히 판단하세요:\n");
		prompt.append("- 시나리오 A: 당신 생각을 종종 하지만, 먼저 연락할 용기는 없는 상태\n");
		prompt.append("- 시나리오 B: 이미 새로운 인연에게 관심이 옮겨간 상태\n");
		prompt.append("- 시나리오 C: 일이나 다른 문제로 연애는 뒷전인 상태\n\n");

		prompt.append("어느 시나리오가 맞는지 사주 근거와 함께 명확히 판단하고,\n");
		prompt.append("그 가능성을 퍼센트로도 제시하세요.\n\n");

		prompt.append("**만약 지금 연락하면 어떻게 될지 시뮬레이션하세요**\n\n");

		prompt.append(String.format("만약 %s님이 오늘 당장 연락한다면:\n", person1Name));
		prompt.append("- 상대가 호의적일 경우 어떻게 전개될지\n");
		prompt.append("- 상대가 냉담할 경우 어떻게 반응할지\n");
		prompt.append("현실적으로 예측하고, 사주 근거로 어느 쪽 가능성이 높은지 판단하세요.\n\n");

		prompt.append("**상대방의 새 인연 가능성도 냉정하게 평가하세요**\n\n");

		prompt.append("듣기 싫겠지만, 솔직하게 말해야 합니다.\n");
		prompt.append(String.format("%s님의 현재 운세를 보면,\n", person2Name));
		prompt.append("새로운 이성과의 인연이 들어올 확률이 얼마나 되는지 퍼센트로 제시하세요.\n");
		prompt.append("높으면 솔직히 경고하고, 낮으면 지금이 기회일 수 있다고 말하세요.\n\n");

		// ==========================================
		// 제3장: 재회 가능성 및 타이밍
		// ==========================================
		prompt.append("==================================================\n");
		prompt.append("⏰ 제3장: 재회 가능성 및 골든타임\n");
		prompt.append("==================================================\n\n");

		prompt.append("**재회 가능성을 종합 평가하세요**\n\n");

		prompt.append("사주 궁합, 현재 운세, 성격 조화도를 종합해서 평가하고,\n");
		prompt.append("별점(★)으로 재회 가능성을 표시하세요.\n\n");

		prompt.append(
			String.format("%s님과 %s님의 재회 가능성: ★★★☆☆ (이런 식으로)\n\n", person1Name, person2Name));

		prompt.append("별점 기준:\n");
		prompt.append("★★★★★ - 매우 높음: 천생연분, 시간이 약. 반드시 다시 만나게 되어 있음\n");
		prompt.append("★★★★☆ - 높음: 노력하면 충분히 가능. 타이밍만 잘 맞추면 됨\n");
		prompt.append("★★★☆☆ - 보통: 50대50. 당신의 전략과 상대방 상황에 따라 달라짐\n");
		prompt.append("★★☆☆☆ - 낮음: 쉽지 않음. 근본적인 문제를 해결해야 가능\n");
		prompt.append("★☆☆☆☆ - 매우 낮음: 거의 불가능. 새로운 인연을 찾는 게 나음\n\n");

		prompt.append("**근거를 구체적으로 제시하세요**\n");
		prompt.append("1. 사주 궁합 점수 (100점 만점)\n");
		prompt.append("2. 헤어진 이유의 해결 가능성 (높음/보통/낮음)\n");
		prompt.append("3. 현재 두 사람의 운세 싱크로율 (좋음/보통/나쁨)\n\n");

		prompt.append("**솔직한 한마디를 추가하세요**\n\n");

		prompt.append("재회 가능성이 낮으면:\n");
		prompt.append("솔직히 말씀드리면, 이 인연은 여기서 끝내는 게 나을 수 있습니다.\n");
		prompt.append("억지로 재회해도 똑같은 이유로 또 헤어질 확률이 높아요.\n");
		prompt.append("차라리 이 경험을 교훈 삼아 더 잘 맞는 사람을 만나시는 걸 추천합니다.\n\n");

		prompt.append("재회 가능성이 높으면:\n");
		prompt.append("좋은 소식입니다. 두 분은 충분히 다시 만날 수 있어요.\n");
		prompt.append("하지만 타이밍을 놓치면 기회가 영영 사라질 수 있으니,\n");
		prompt.append("아래 내용을 반드시 숙지하세요.\n\n");

		prompt.append("**재회 골든타임 3개를 제시하세요** ⚠️ 절대 과거 날짜 금지\n\n");

		prompt.append("현재 ").append(todayDate).append(" 이후의 미래 시점만 제시해야 합니다.\n\n");

		prompt.append("두 분 모두의 사주를 분석한 결과, 재회에 유리한 시기는 다음과 같습니다:\n\n");

		prompt.append("**1순위 타이밍** - 202X년 X월\n");
		prompt.append("왜 이 시기가 최적인지 300자 이상 구체적으로 설명하세요:\n");
		prompt.append("- 두 사람 모두 애정운이 동시에 상승하는지\n");
		prompt.append("- 특히 상대방의 감정선이 부드러워지는지\n");
		prompt.append("- 사주상 합(合)이 형성되어 화해 분위기가 조성되는지\n");
		prompt.append("사주 근거를 들어 설명하세요.\n\n");

		prompt.append("**2순위 타이밍** - 202X년 X월\n");
		prompt.append("1순위를 놓쳤다면 이 시기를 노리라고 조언하고,\n");
		prompt.append("이 시기가 좋은 이유를 간략히 설명하세요.\n\n");

		prompt.append("**3순위 타이밍** - 202X년 X월\n");
		prompt.append("마지막 기회라는 점을 강조하고,\n");
		prompt.append("이것마저 놓치면 어떻게 되는지 경고하세요.\n\n");

		prompt.append("**데드라인** - 202X년 X월 이후\n");
		prompt.append("이 시기가 지나면 두 사람의 운이 완전히 엇갈린다는 점을 명확히 하고,\n");
		prompt.append(String.format("특히 %s님에게 새로운 인연이 본격적으로 들어오기 시작해서\n", person2Name));
		prompt.append("재회 가능성이 거의 0퍼센트에 가까워진다는 점을 강조하세요.\n\n");

		prompt.append("**왜 이 시기들인지 사주 근거를 설명하세요**\n");
		prompt.append("- 두 사람의 대운/세운 흐름 비교\n");
		prompt.append("- 오행 조화 시기 (상생 vs 상극)\n");
		prompt.append("- 신살 작용 (도화살, 역마살 등)\n");
		prompt.append("전문 용어보다는 쉬운 말로 풀어서 설명하세요.\n\n");

		// ==========================================
		// 제4장: 재회 실전 매뉴얼
		// ==========================================
		prompt.append("==================================================\n");
		prompt.append("📱 제4장: 재회 실전 매뉴얼\n");
		prompt.append("==================================================\n\n");

		prompt.append("자, 이제 가장 중요한 파트입니다.\n");
		prompt.append("이론은 그만하고, 실제로 어떻게 행동해야 하는지 알려드릴게요.\n\n");

		prompt.append("**재회 전 준비 단계** (최소 300자)\n\n");

		prompt.append("연락하기 전에 반드시 해야 할 일들을 구체적으로 제시하세요:\n\n");

		prompt.append("첫째, 당신 자신부터 바꿔야 합니다.\n");
		prompt.append(String.format("%s님이 가장 먼저 고쳐야 할 점을 사주 약점 기반으로 지적하세요.\n", person1Name));
		prompt.append("예를 들어 의심과 집착, SNS 스토킹, 즉각적인 답장 요구 등\n");
		prompt.append("구체적인 개선 포인트를 제시하고,\n");
		prompt.append("이거 안 고치면 재회해도 100퍼센트 또 헤어진다고 경고하세요.\n\n");

		prompt.append("둘째, SNS 전략을 제시하세요.\n");
		prompt.append("- 잘 지내는 모습을 보여주되 과하게 행복한 척하면 역효과\n");
		prompt.append("- 적당히 성장한 모습을 보여주는 게 포인트\n");
		prompt.append("- 상대방이 관심 가질 만한 콘텐츠 제안\n\n");

		prompt.append("셋째, 공통 지인 활용 전략이 있다면 제시하세요.\n\n");

		prompt.append("**첫 연락 대화 전략** (핵심! 최소 500자)\n\n");

		prompt.append("골든타임이 왔다고 가정하고, 첫 연락을 어떻게 보낼지 제시하세요.\n\n");

		prompt.append(String.format("%s님의 성격 분석을 바탕으로 맞춤 전략을 짜세요.\n\n", person2Name));

		prompt.append("중요: Case 1, Case 2, Case 3 형식으로 나누지 말고,\n");
		prompt.append("상대방의 사주 성향에 따라 자연스럽게 서술하세요.\n\n");

		prompt.append("예를 들어:\n");
		prompt.append("- 상대방이 통제받기 싫어하는 타입이라면 가벼운 접근이 좋다고 설명하고\n");
		prompt.append("- 절대 하면 안 되는 멘트와 추천 멘트를 각각 제시하세요\n");
		prompt.append("- 상대방이 챙김받고 싶어하는 타입이라면 따뜻한 관심 표현이 좋다고 설명하고\n");
		prompt.append("- 구체적인 예시를 들어주세요\n");
		prompt.append("- 상대방이 자존심 강한 타입이라면 먼저 사과하는 게 필수라고 강조하세요\n\n");

		prompt.append("첫 연락 예시를 최소 3개 이상 제시하되,\n");
		prompt.append("옵션 1, 옵션 2 형식보다는 흐름 있는 서술로 자연스럽게 풀어내세요.\n\n");

		prompt.append("**상대방 반응별 대처법을 시뮬레이션하세요**\n\n");

		prompt.append("호의적 반응이 오면:\n");
		prompt.append("- 바로 만남을 제안하지 말고 2-3일 가벼운 대화 이어가기\n");
		prompt.append("- 자연스럽게 밥 약속으로 연결하는 방법\n\n");

		prompt.append("중립 반응이 오면:\n");
		prompt.append("- 아직 마음이 안 풀린 상태\n");
		prompt.append("- 조금 더 기다리면서 가끔 안부만 전하기\n");
		prompt.append("- 1-2주 후에 다시 시도하는 전략\n\n");

		prompt.append("냉담 반응이 오면:\n");
		prompt.append("- 지금은 때가 아니라는 점 인정하기\n");
		prompt.append("- 최소 1개월 이상 기다린 후 2순위 타이밍에 재도전하기\n");
		prompt.append("- 절대 하면 안 되는 행동 (추가 메시지 폭탄, 전화, 장문의 사과문 등)\n\n");

		prompt.append("**만남 성사 후 행동 지침** (최소 400자)\n\n");

		prompt.append("드디어 만나게 됐을 때 어떻게 행동해야 하는지 구체적으로 제시하세요.\n\n");

		prompt.append(String.format("- %s님 사주상 가장 싫어하는 행동을 구체적으로 지적하세요\n\n", person2Name));

		prompt.append("플러스 포인트 행동:\n");
		prompt.append("- 옛날에 좋아했던 음식 기억해서 주문하기\n");
		prompt.append("- 예전 추억 중 좋았던 순간만 언급하기\n");
		prompt.append("- 변한 모습을 자연스럽게 보여주기\n\n");

		prompt.append("**재회 후 지속 전략** (재발 방지)\n\n");

		prompt.append("방심하면 똑같은 이유로 3개월 안에 또 헤어집니다.\n\n");

		prompt.append("근본 원인 해결 방법:\n");
		prompt.append("제1장에서 분석한 헤어진 이유를 요약하고,\n");
		prompt.append("이걸 해결하지 않으면 100퍼센트 재발한다고 경고하세요.\n\n");

		prompt.append("구체적 실천 방안:\n");
		prompt.append(String.format("1. %s님이 해야 할 일을 구체적으로 제시하세요\n", person1Name));
		prompt.append("   예: 의심하는 습관 줄이기, 답장 늦어도 3시간은 참기, SNS 안 뒤지기\n\n");

		prompt.append(String.format("2. %s님에게 부탁해야 할 일을 제시하세요\n", person2Name));
		prompt.append("   예: 연락 조금만 더 자주 해주기\n");
		prompt.append("   설득 멘트도 함께 제공하세요\n\n");

		prompt.append("위기 대처 매뉴얼:\n");
		prompt.append("재회 후 다시 싸울 때 (반드시 올 겁니다):\n");
		prompt.append("1단계: 일단 물리적으로 떨어지기\n");
		prompt.append("2단계: 최소 2시간 쿨타임\n");
		prompt.append("3단계: 먼저 미안해로 시작\n");
		prompt.append("4단계: 감정이 아니라 사실만 이야기하기\n\n");

		// ==========================================
		// JSON 포맷
		// ==========================================
		appendCompatibilityJsonResponseFormat(prompt, person1Name, person2Name);

		// ==========================================
		// 최종 체크리스트
		// ==========================================
		prompt.append("\n==================================================\n");
		prompt.append("⚡ [최종 체크리스트 - 제출 전 필수 확인] ⚡\n");
		prompt.append("==================================================\n\n");

		prompt.append("제출하기 전에 반드시 확인하세요:\n\n");

		prompt.append("✅ 전체 분량이 5000자 이상인가요?\n");
		prompt.append("✅ 제1장(헤어진 이유)이 1500자 이상인가요?\n");
		prompt.append("✅ 갈등 장면을 대본 형식이 아니라 간접화법으로 자연스럽게 서술했나요?\n");
		prompt.append("✅ 작은따옴표를 과다하게 사용하지 않았나요?\n");
		prompt.append("✅ Case 1/2/3 같은 획일적인 구조가 아니라 자연스러운 흐름인가요?\n");
		prompt.append("✅ 재회 가능성을 별점(★)으로 명확히 평가했나요?\n");
		prompt.append("✅ 타이밍 3개를 구체적 년/월로 제시했나요? (과거 날짜 없는지 확인!)\n");
		prompt.append("✅ 첫 연락 예시를 최소 3개 이상 제시했나요?\n");
		prompt.append("✅ 상대방 반응별 대처법을 시뮬레이션했나요?\n");
		prompt.append("✅ 만남 후 금지 행동과 플러스 행동을 구분해서 제시했나요?\n");
		prompt.append("✅ 재회 후 재발 방지 구체적 실천 방안을 제시했나요?\n");
		prompt.append("✅ 사주 데이터에 기반한 고유한 분석인가요? (템플릿 같지 않은지)\n");
		prompt.append("✅ 내담자가 와 이거 돈값 한다고 느낄 만한 디테일인가요?\n\n");

		prompt.append("이 모든 항목을 만족해야 제출할 수 있습니다.\n");
		prompt.append("==================================================\n");

		return prompt.toString();
	}

	/**
	 * ManseryeokCalculationResponse 정보를 프롬프트 포맷으로 변환
	 */
	private void appendPersonCalculationInfo(StringBuilder prompt,
		ManseryeokCalculationResponse response) {
		ManseryeokCalculationResponse.InputInfo input = response.getInput();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		prompt.append(String.format("- 생년월일: %s %s (양력/음력 구분: %s)\n",
			input.getSolarDate(), input.getSolarTime(), input.getIsLunar() ? "음력" : "양력"));
		prompt.append(String.format("- 성별: %s\n", input.getGender()));

		// 사주팔자 (천간/지지/십성/오행)
		prompt.append("- 사주팔자:\n");
		appendPillarLine(prompt, "년주", saju.getYearSky(), saju.getYearGround());
		appendPillarLine(prompt, "월주", saju.getMonthSky(), saju.getMonthGround());
		appendPillarLine(prompt, "일주", saju.getDaySky(), saju.getDayGround());
		appendPillarLine(prompt, "시주", saju.getTimeSky(), saju.getTimeGround());

		// 관계 정보 (합, 충, 원진 등) - 재회운에서 중요
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			prompt.append("- 지지 관계(합/충/형): ").append(String.join(", ", saju.getGroundRelations()))
				.append("\n");
		}
		if (saju.getSkyRelations() != null && !saju.getSkyRelations().isEmpty()) {
			prompt.append("- 천간 관계(합/충): ").append(String.join(", ", saju.getSkyRelations()))
				.append("\n");
		}

		// 신살 정보
		if (saju.getSinsalInfo() != null) {
			prompt.append("- 주요 신살: ");
			saju.getSinsalInfo().values().forEach(list ->
				prompt.append(String.join(", ", list)).append(" ")
			);
			prompt.append("\n");
		}
	}

	private void appendPillarLine(StringBuilder prompt, String label,
		ManseryeokCalculationResponse.PillarElement sky,
		ManseryeokCalculationResponse.PillarElement ground) {

		if (sky == null || ground == null) {
			prompt.append(String.format("  %s: (정보 없음)\n", label));
			return;
		}

		prompt.append(String.format("  %s: %s%s (천간:%s/오행:%s, 지지:%s/오행:%s)\n",
			label,
			sky.getChinese() != null ? sky.getChinese() : "?",
			ground.getChinese() != null ? ground.getChinese() : "?",
			sky.getTenStar() != null ? sky.getTenStar() : "?",
			sky.getFiveCircle() != null ? sky.getFiveCircle() : "?",
			ground.getTenStar() != null ? ground.getTenStar() : "?",
			ground.getFiveCircle() != null ? ground.getFiveCircle() : "?"
		));
	}

	// 101. 2026년 상반기 변화(환경, 인간관계, 연애, 학업, 건강)
	private String create2026ChangesPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 군더더기 없이 **미래(2026년)**의 핵심 변화만 콕 집어 예측하는 '족집게 예언가'입니다.\n");
		prompt.append("서론, 본론, 배경설명, 인생 총평 같은 **문학적인 글쓰기를 절대 하지 마세요.**\n");
		prompt.append("오직 사용자가 물어본 '2026년 상반기의 변화' 5가지만 명확하게 전달하세요.\n\n");

		prompt.append("### 5. 분석 대상자 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response, 2026);

		prompt.append("\n### [2026년(병오년) 상반기 변화 분석] 요청 ###\n");
		prompt.append("혜안 선생님, 2026년 병오년(丙午年)의 기운이 " + name
			+ "님의 사주와 만났을 때 일어날 상반기 변화를 5가지 측면에서 구체적으로 예측해주세요.\n\n");

		// 🔥 [수정] 강력한 제약 조건 추가 (잡소리 제거 & 연도 고정)
		prompt.append("### ⚠️ [필수 작성 지침] (어기면 안됨) ###\n");
		prompt.append(
			"2. **[연도 고정]** 지금은 2025년이 아닙니다. 분석 시점은 무조건 **'2026년 상반기'**입니다. '올해'라고 이야기를 하지 말고 **'2026년', '병오년'**에 일어날 일만 서술하세요.\n");
		prompt.append(
			"3. **[목차 강제]** 결과물은 오직 아래 제시된 **5가지 목차**로만 구성되어야 합니다. 서론이나 결론도 길게 쓰지 마세요.\n\n");
		prompt.append(
			"4. **[대운 고정값 준수]** 프롬프트의 `[대운 고정값]`과 다른 대운명(예: 계축 등)을 임의로 쓰면 안 됩니다. 대운은 절대 재계산 금지입니다.\n\n");

		prompt.append("--- [분석 시작] ---\n");
		prompt.append("\"2026년 병오년, 붉은 말의 해가 밝아오네요. " + name + "님에게는...\" 으로 자연스럽게 시작.\n\n");
		prompt.append("--- [작성할 목차] ---\n");

		prompt.append("## 1. 환경의 변화\n(이사, 이직, 부서 이동 등 물리적/사회적 환경의 변화 예측)\n\n");
		prompt.append("## 2. 인간관계의 변화\n(새로운 인연, 멀어질 인연, 귀인의 등장 여부)\n\n");
		prompt.append("## 3. 연애와 애정운\n(솔로라면 만남운, 커플이라면 관계의 변화, 감정의 기복)\n\n");
		prompt.append("## 4. 학업 및 성취운\n(공부, 자격증, 승진, 프로젝트 성과 등)\n\n");
		prompt.append("## 5. 건강 및 컨디션\n(주의해야 할 신체 부위나 멘탈 관리 조언)\n\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	// 102. 2026년 상반기 나의 운명 키워드
	private String create2026KeywordPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주 구조를 현실 언어로 풀어주는 명리 상담가입니다.\n");
		prompt.append("문장은 자연스럽고 읽기 쉬워야 하며, 보고서처럼 딱딱한 문체를 피하세요.\n\n");

		appendPersonDetailInfo(prompt, name, response, 2026);

		prompt.append("\n### [2026년 상반기 운명 키워드 분석 요청] ###\n");
		prompt.append(
			"2026년 상반기 " + name + "님에게 가장 중요한 운명 키워드를 1개만 제시하고, 왜 그 키워드가 중요한지 풀어서 설명해주세요.\n\n");

		prompt.append("### ⚠️ [필수 작성 지침] ###\n");
		prompt.append("1. 분석 시점은 반드시 2026년 상반기와 병오년으로 고정합니다.\n");
		prompt.append("2. 첫 줄은 [2026년 상반기 운명 키워드: 키워드명] 형식으로만 작성합니다.\n");
		prompt.append("3. 첫 줄 이후 본문은 정확히 4개 문단으로 작성하고, 문단 사이는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로 구분합니다.\n");
		prompt.append("4. 각 문단은 4~5문장으로 구성하고, 문단마다 한 가지 주제만 다룹니다.\n");
		prompt.append("5. 각 문단은 너무 짧지 않게 150~220자 안팎으로 작성해 카드 한 페이지가 6~7줄 정도 읽히도록 맞춥니다.\n");
		prompt.append(
			"6. 번호형 나열(1-1, 첫째, 둘째), 목록 기호(-, *, 1.), 마크다운 제목(##, ###), 대괄호 소제목 사용을 금지합니다.\n");
		prompt.append(
			"7. 인위적 안내 문구를 금지합니다. 예: \"직접 대면 상담하듯 핵심만 전해드립니다\", \"핵심만 전해드리겠습니다\", \"AI가 분석한 결과\".\n");
		prompt.append("8. 날짜 표기는 2026년 3월처럼 년-월까지만 사용하고 시/분/초 표기는 금지합니다.\n");
		prompt.append("9. 문체 흐름은 다음 순서를 따릅니다.\n");
		prompt.append("   - 1문단: 키워드의 의미와 현재 흐름\n");
		prompt.append("   - 2문단: 사주 근거와 왜 이 키워드가 핵심인지\n");
		prompt.append("   - 3문단: 상반기 실행 포인트\n");
		prompt.append("   - 4문단: 주의할 선택과 마무리 조언\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	// 103번 나의 플러팅 기술
	private String createFlirtingPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		// 1. 역할 정의 (세련된 연애 프로파일러)
		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 세련되고 감각적인 '연애 프로파일러'입니다.\n");
		prompt.append(
			"사주 명식을 통해 그 사람 고유의 **'분위기(Vibe)'와 '치명적인 매력'**을 분석하고, 이를 극대화할 수 있는 실전 연애 팁을 제안합니다.\n");
		prompt.append("말투는 **정중하지만 위트 있는 '해요체'**를 사용하세요. (예: \"~한 매력이 있네요.\")\n");
		prompt.append("**반말이나 지나치게 가벼운 말투는 사용하지 마세요.**\n\n");

		// 2. 데이터 주입 (색깔/숫자 정보가 든 appendKeywords는 제외)
		prompt.append("### 1. 분석 대상자 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 2. [명령] 매력 분석 및 플러팅 가이드 ###\n");
		prompt.append(
			name + "님의 사주(글자들의 기운)를 보고, 이 사람이 가진 **치명적인 매력**과 **이성을 사로잡는 구체적인 스킬**을 알려주세요.\n\n");

		// 3. 제약 조건
		prompt.append("### ⚠️ [작성 톤앤매너 - 절대 엄수] ###\n");
		prompt.append(
			"1. **[사주 용어 허용]**: '홍염살', '도화살', '역마', '상관' 등 사주 용어를 적절히 섞어서 설명해도 좋습니다. 단, 너무 어렵게 풀지 말고 **\"홍염살이 있어 가만히 있어도 시선을 끄네요\"** 처럼 매력과 연결해 자연스럽게 서술하세요.\n");
		prompt.append(
			"2. **[개운법 절대 금지]**: **색깔(파란색, 빨간색 등), 숫자(3, 7 등), 방향(동쪽, 남쪽), 행운의 아이템** 추천은 **절대 금지**입니다. 오직 **태도, 표정, 대화법, 분위기 연출**로 승부하는 팁만 주세요.\n");

		prompt.append("--- [작성할 내용] ---\n");

		prompt.append("## 1. 당신의 매력 포인트\n");
		prompt.append("- (지침: **분량을 길고 풍부하게(최소 6~7문장 이상)** 작성하세요.)\n");
		prompt.append("- 사주에 나타난 도화, 홍염, 살(殺) 등의 기운을 언급하며, 이 사람만의 고유한 분위기를 칭찬해주세요.\n");
		prompt.append("- 예: \"임수 일간 특유의 깊은 분위기에 홍염살이 더해져, 신비로운 매력을 풍기시네요.\"\n");
		prompt.append("- 본인이 미처 몰랐던 매력까지 끄집어내어 **자존감을 높여주는 '기분 좋은 칭찬'** 위주로 작성하세요.\n\n");

		prompt.append("## 2. 나만의 플러팅 비법은 ?\n");
		prompt.append("- 사주로 봤을때, 어떻게 행동해야 매력이 극대화되는지 설명하세요.\n");
		prompt.append(
			"- 예: \"말을 많이 하기보다 지그시 눈을 맞추는 게 효과적입니다.\", \"무심한 듯 챙겨주는 츤데레 전략이 잘 먹힙니다.\"\n\n");

		prompt.append("## 3. 이것만은 주의하세요\n");
		prompt.append("- 이 사람의 매력을 반감시킬 수 있는 사주적 단점(고집, 급한 성격 등)을 짧고 굵게 조언하세요.\n\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	// 104. 사떡 궁합
	private String createChemistryMatchPrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		String userGender = response != null && response.getInput() != null
			? response.getInput().getGender()
			: null;
		String targetGenderRule;
		if ("MALE".equalsIgnoreCase(userGender)) {
			targetGenderRule = "추천 대상은 반드시 여성으로만 선정하세요.";
		} else if ("FEMALE".equalsIgnoreCase(userGender)) {
			targetGenderRule = "추천 대상은 반드시 남성으로만 선정하세요.";
		} else {
			targetGenderRule = "추천 대상은 반드시 이성(반대 성별)으로만 선정하세요.";
		}

		// 1. 역할 정의
		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주명리학에 정통한 '최애 매칭 큐레이터'입니다.\n");
		prompt.append(
			"사용자의 사주를 분석하여, '찰떡궁합(Soulmate)' 대상을 추천합니다. 말투는 **팬 커뮤니티처럼 '재미있고 주접 떠는' 분위기**를 살려주세요.\n\n");

		// 2. 데이터 주입
		prompt.append("### 1. 분석 대상자 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		prompt.append("\n### 2. [명령] 사떡궁합 매칭 리포트 작성 ###\n");
		prompt.append(name + "님의 사주 구성을 보고, 가장 잘 맞는 인물을 아래 구성으로 추천해주세요.\n");
		prompt.append("- 아이돌 1명, 배우 1명, 캐릭터 1명 (총 3명)\n");
		prompt.append("- 선정 과정 설명보다 인물과 궁합 이유를 바로 제시\n");

		// 3. 제약 조건
		prompt.append("### ⚠️ [필수 작성 지침] (절대 엄수) ###\n");
		prompt.append("1. **[성별 규칙]**: " + targetGenderRule + "\n");
		prompt.append(
			"2. **[대상 구성 고정]**: 아이돌 1명, 배우 1명, 캐릭터 1명을 반드시 모두 채우세요.\n");
		prompt.append(
			"3. **[언어 절대 고정]**: 모든 이름과 작품명은 무조건 한국어로만 표기하세요. 영어 병기 금지.\n");
		prompt.append(
			"4. **[후보별 필수 정보]**: 각 후보마다 맞는 이유 2개, 주의점 1개를 반드시 포함하세요.\n");
		prompt.append(
			"5. **[이름 표기 규칙]**: 단일 이름만 쓰지 말고 반드시 소속/작품을 붙여 표기하세요. 예: 블랙핑크의 지수, 배우 박보영, 원피스의 나미.\n");
		prompt.append(
			"6. **[캐릭터 범위 고정]**: 캐릭터 1명은 반드시 애니메이션 캐릭터만 허용합니다.\n");
		prompt.append(
			"7. **[캐릭터 표기 규칙]**: 캐릭터는 반드시 작품명+캐릭터명으로 표기하세요. 예: 원피스의 나미, 귀멸의 칼날의 탄지로.\n");
		prompt.append(
			"8. **[도입 필수]**: 본문 시작은 반드시 4문장정도로 작성하세요. "
				+ name
				+ "님의 사주 핵심 성향을 간단히 설명하고, 이런 성향이 어떤 사람과 잘 맞는지 자연스럽게 연결하세요.\n");
		prompt.append(
			"9. **[추천 시작 문장 고정]**: 도입 다음, 추천 파트의 첫 문장은 반드시 아래 형식으로 시작하세요. \""
				+ name
				+ "님과 가장 잘 어울리는 아이돌은 [아이돌 이름]님, 배우는 [배우 이름]님, 캐릭터는 [작품명]의 [캐릭터명]입니다.\"\n");
		prompt.append(
			"10. **[문단 분리]**: 추천 시작 문장 다음부터 인물 한 명 설명이 끝날 때마다 줄바꿈 두 번(\\n\\n)으로 다음 문단으로 넘기세요.\n");
		prompt.append("11. **[문단 길이]**: 한 인물 설명은 3~5문장으로 작성하세요.\n");
		prompt.append("12. **[AI 라벨 금지]**: [아이돌 추천], [배우 추천], [캐릭터 추천] 같은 대괄호 라벨 금지.\n");
		prompt.append(
			"13. **[미신형 팁 금지]**: 색깔, 방향, 숫자 같은 개운법은 금지합니다.\n");
		prompt.append(
			"14. **[선정 과정 표현 금지]**: '남성 라인', '여성 라인', '카테고리', '골랐습니다', '선정했습니다' 같은 표현 금지.\n");
		prompt.append("15. **[문체]**: 딱딱한 보고서체보다 읽기 쉬운 설명체를 사용하세요.\n\n");

		prompt.append("--- [작성할 내용 및 구조] ---\n");
		prompt.append("도입 문단: 사주 핵심 성향 + 잘 맞는 상대 타입 설명\n");
		prompt.append("추천 시작 문장: 아이돌/배우/캐릭터 1명 이름을 한 문장에 제시\n");
		prompt.append("아이돌 1명 추천 문단\n");
		prompt.append("배우 1명 추천 문단\n");
		prompt.append("캐릭터 1명 추천 문단\n");
		prompt.append("※ 각 후보는 독립 문단으로 작성하고, 한 문단에 여러 후보를 섞지 마세요.\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	// 105. 오늘의 운세
	private String createTodayFortunePrompt(String name, ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		// ===== 1. 오늘 날짜 정보 (KST 기준) =====
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		String formattedDate = today.format(
			java.time.format.DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"));
		String dayOfWeek = today.getDayOfWeek()
			.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN);
		String todayInfo = String.format("%s %s", formattedDate, dayOfWeek);

		// ===== 2. 오늘의 일진(日辰) 계산 =====
		String todayDayPillar = calculateTodayDayPillar(today);

		// ===== 3. 역할 정의 (스토리텔러로 강화) =====
		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 하루의 흐름을 읽어주는 따뜻한 '인생 날씨 예보관' 혜안(慧眼)입니다.\n");
		prompt.append("단순한 운세 분석을 넘어, 사용자가 오늘 하루를 기분 좋게 시작할 수 있도록 **몰입감 있는 에세이 스타일**로 글을 작성하세요.\n");
		prompt.append(
			"말투는 다정하고 명쾌한 '해요체'를 사용하며, **한자나 어려운 사주 용어는 가급적 사용하지 않습니다. 꼭 필요한 경우 사용 가능**\n\n");

		// ===== 4. 사용자 정보 주입 =====
		prompt.append("### 1. 분석 대상자 정보 ###\n");
		appendPersonDetailInfo(prompt, name, response);

		// ===== 5. 오늘 날짜 및 일진 정보 =====
		prompt.append("\n### 2. 오늘의 천시(天時) 정보 ###\n");
		prompt.append(String.format("**날짜**: %s\n", todayInfo));
		prompt.append(String.format("**오늘의 일진(Input)**: %s\n", todayDayPillar));
		prompt.append(
			"※ 주의: 위 '오늘의 일진'에 포함된 한자(甲, 寅 등)는 분석에만 참고하고, **결과물에는 절대로 한자를 적지 마세요.**\n\n");

		// ===== 6. 분석 요청 =====
		prompt.append("### 3. [오늘의 운세 스토리텔링] 요청 ###\n");
		prompt.append(String.format("오늘(%s)의 기운이 %s님의 하루에 미칠 영향을, 마치 옆에서 조언해주듯 자연스럽게 풀어내주세요.\n\n",
			todayInfo, name));

		// ===== 7. 필수 작성 지침 (강력한 제약 조건) =====
		prompt.append("### ⚠️ [필수 작성 지침 - 절대 엄수] ###\n");

		prompt.append("**[1] 한자(漢字) 및 전문 용어 및 미신적 개운법 절대 금지**\n");
		prompt.append("- **결과물에 한자(甲, 乙, 寅, 卯, 沖, 合 등)가 단 한 글자라도 포함되면 안 됩니다.**\n");
		prompt.append("- '충(沖)하여' → '변화의 바람이 불어와서'\n\n");
		prompt.append(
			"- **색깔/방향/숫자 추천 금지**: '행운의 색은 파랑', '동쪽으로 가라', '숫자 7' 같은 **유치한 미신적 조언을 절대 하지 마세요.**\n");
		prompt.append("- 대신 **'마음가짐', '대화 태도', '업무 방식'** 등 실질적인 행동 팁을 주세요.\n\n");

		prompt.append("**[2] 술술 읽히는 '스토리텔링' 문체**\n");
		prompt.append("- '~하겠네요.', '~할 수도 있어요.', '~한 날이에요.' 등 부드러운 구어체를 섞어 쓰세요.\n");
		prompt.append("- 문장이 뚝뚝 끊기지 않고 물 흐르듯 이어지게 작성하세요. (접속사 활용)\n\n");

		prompt.append("**[3] 분량 및 가독성**\n");
		prompt.append("- 총운: **300자** (충분한 길이로 서사 부여)\n");
		prompt.append("- 각 분야별 운세: **200자~250자**\n");
		prompt.append("- **목록 기호(-, *, 1.) 사용 금지**: 줄글로 자연스럽게 이어쓰세요.\n");
		prompt.append("- 문단은 6~7줄 넘지 않게 적절히 끊어주세요.\n\n");

		// ===== 8. 작성 목차 (기존 구조 유지하되 가이드 강화) =====
		prompt.append("--- [작성할 내용] ---\n\n");

		prompt.append("## 1. 오늘의 총운 (점수: {50~95 사이의 숫자}/100)\n");
		prompt.append("- **[작성 가이드]**: 오늘 하루의 전반적인 '분위기'와 '날씨'를 묘사하듯 시작하세요.\n");
		prompt.append("- 오늘 사용자에게 가장 필요한 마음가짐이나 태도를 따뜻하게 조언해주세요.\n");
		prompt.append("- 기분 좋은 예감이나 주의할 점을 자연스럽게 녹여내세요.\n");
		prompt.append("- **점수는 오늘의 사주 흐름을 분석하여 50~95 사이의 구체적인 숫자로 반드시 채워넣으세요. 알파벳 O나 빈칸 금지.**\n\n");

		prompt.append("## 2. 재물운/금전운 (150~200자)\n");
		prompt.append("- **[작성 가이드]**: 오늘의 금전운에 쉽고 재밌게 풀어서 작성\n");

		prompt.append("## 3. 애정운 (150~200자)\n");
		prompt.append("- **[작성 가이드]**: 오늘의 애정운에 쉽고 재밌게 풀어서 작성\n");

		prompt.append("## 4. 성취운 (150~200자)\n");
		prompt.append("- **[작성 가이드]**: 오늘의 성취운에 쉽고 재밌게 풀어서 작성\n");

		// ===== 9. JSON 포맷 (기존 유지) =====
		prompt.append("\n\n### 9. [최종 출력 형식] (JSON) ###\n");
		prompt.append("위에서 요청된 모든 분석을 완료한 후, **반드시 markdown 감싸기 없이 순수한 JSON 형식으로만** 응답해주세요.\n");
		prompt.append(
			"**fullAnalysis** 값에는 위에서 요청한 모든 상세 분석 내용을 **목록 기호 없이 물 흐르듯 자연스럽게 이어진 하나의 긴 텍스트**로 담아야 합니다.\n\n");

		prompt.append("--- [fullAnalysis 작성 규칙] ---\n");
		prompt.append("1. **(매우 중요)** 프롬프트에 `##`로 시작하는 주제(제목)가 있으면, `##` 기호는 **절대 출력하지 마세요.**\n");
		prompt.append("2. 대신, 그 주제(제목) 텍스트를 **대괄호(`[]`)**로 감싸고, 그 뒤에 **줄바꿈(\\n)**을 한 번만 추가해주세요.\n");
		prompt.append("   (예시: `## 1. 오늘의 총운` -> [오늘의 총운 (75/100)]\\n)\n");
		prompt.append(
			"3. **(매우 중요)** `**` 강조 기호 사용 금지. 그냥 텍스트로만 출력.\n");
		prompt.append("4. **(가장 중요) 한자(甲, 寅 등) 절대 포함 금지.**\n");
		prompt.append(
			"5. **(카드 UI용)** 가독성을 위해, 각 분야(총운, 금전운, 애정운 등)가 끝날 때마다 **줄바꿈을 두 번(\\n\\n)** 하여 섹션을 명확히 구분해주세요.\n\n");

		prompt.append("--- [summary 말투 규칙 - 매우 중요] ---\n");
		prompt.append("**summary는 '혜안' 페르소나를 완전히 무시하고, 아래 규칙만 100% 따라야 합니다.**\n\n");

		prompt.append("🎯 **필수 규칙 (절대 엄수)**\n");
		prompt.append(
			"1. **페르소나**: 당신은 다정하고 통찰력 있는 조언자입니다. **무조건 '해요체'(~해요, ~하네요)를 사용하여 정중하게** 요약해주세요. 반말은 절대 금지입니다.\n");
		prompt.append(
			"2. **주제 (오늘 운세 총평)**: 오늘 하루 전반적인 흐름을 한 문장으로 요약하고, 가장 주의할 점이나 활용할 기회를 짚어주세요.\n");
		prompt.append("3. **줄바꿈**: 한 문장이 끝나면 **반드시 줄바꿈(\\n)** 해주고, 마침표는 찍지 마.\n");
		prompt.append("4. **분량**: 총 250자 이내.\n\n");

		prompt.append("{\n");
		prompt.append("  \"fullAnalysis\": \"<여기에 상세 분석 전체 내용을 작성. 각 섹션을 \\n\\n으로 구분>\",\n");
		prompt.append("  \"summary\": \"<문장 끝마다 '\\n'으로 줄바꿈된 250자 이내 요약본 작성>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	// 106. 3월 월간운세
	private String createMarchMonthlyFortunePrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 0. 시스템 역할 정의 ###\n");
		prompt.append("당신은 사주를 현실 언어로 풀어주는 명리 상담가입니다.\n");
		prompt.append("설명은 자연스럽고 사람다운 문장으로 작성하고, 보고서체/AI 안내문처럼 딱딱한 표현은 금지합니다.\n");
		prompt.append("좋은 흐름만 미화하지 말고, 실제로 주의할 리스크·불편·손실 가능성도 균형 있게 함께 다뤄주세요.\n\n");

		appendPersonDetailInfo(prompt, name, response, 2026);

		prompt.append("\n### [2026년 3월 월간운세 분석 요청] ###\n");
		prompt.append(
			"2026년 3월(신금·묘목의 흐름) 한 달 동안 " + name
				+ "님에게 나타날 운의 흐름을 생활 관점으로 구체적으로 설명해주세요.\n\n");

		prompt.append("### ⚠️ [필수 작성 지침] ###\n");
		prompt.append("1. 분석 범위는 반드시 2026년 3월 한 달로 고정합니다.\n");
		prompt.append("2. 본문은 아래 8개 섹션을 순서대로 모두 포함합니다.\n");
		prompt.append("3. 섹션과 섹션 사이는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로 구분합니다.\n");
		prompt.append("4. 각 섹션은 4~6문장 내외로 작성하고, 한 문단이 지나치게 길어지지 않게 구성합니다.\n");
		prompt.append(
			"5. 번호형 나열(1., 1-1, 첫째/둘째), 마크다운 제목(##, ###), 대괄호 라벨([요약], [핵심]) 사용을 금지합니다.\n");
		prompt.append(
			"6. 인위적인 AI 안내 문구를 금지합니다. 예: '직접 대면 상담하듯 핵심만 전해드립니다', 'AI가 분석한 결과'.\n");
		prompt.append("7. 실천 조언은 현실 행동 중심으로 제시합니다. 색깔, 방향, 숫자 개운법은 금지합니다.\n");
		prompt.append(
			"8. 사주 용어는 필요한 만큼만 쓰고, 바로 쉬운 말로 풀어 설명합니다. 한자(甲, 寅, 沖 등)는 출력하지 않습니다.\n");
		prompt.append("9. 날짜 표기는 '2026년 3월'처럼 년/월까지만 사용하고 시/분/초 표기는 금지합니다.\n");
		prompt.append(
			"10. 각 섹션에는 유리한 흐름과 주의할 리스크를 함께 포함하고, 마지막은 현실 대응 조언으로 마무리하세요.\n");
		prompt.append(
			"11. 모든 판단은 입력 데이터(원국, 대운, 월운, 합/충/형/파/해, 오행/십성) 근거 안에서만 작성하세요. 데이터에 없는 사건은 만들어내지 마세요.\n");
		prompt.append(
			"12. 무조건 좋다/나쁘다 같은 과장이나 단정은 금지하고, 가능성·조건 중심으로 서술하세요.\n\n");

		prompt.append("--- [작성할 섹션 고정 순서] ---\n");
		prompt.append("3월 핵심 키워드\n");
		prompt.append("금전운\n");
		prompt.append("연애운\n");
		prompt.append("학업운\n");
		prompt.append("직장/일운\n");
		prompt.append("건강운\n");
		prompt.append("주의할 점과 조언\n");
		prompt.append("3월운 총평\n");
		prompt.append(
			"※ 마지막 두 섹션에서는 '이번 달은 어떤 달인지'를 짚고, 무리하지 않으면서 실천 가능한 행동 방향을 자연스럽게 제시한 뒤 3월운 총평으로 깔끔하게 마무리하세요.\n\n");

		appendSajuJsonResponseFormat(prompt, name);
		return prompt.toString();
	}

	private String calculateTodayDayPillar(java.time.LocalDate today) {
		// 기준일: 1900-01-01 = 甲戌日 (60갑자 중 10번째)
		java.time.LocalDate baseDate = java.time.LocalDate.of(1900, 1, 1);
		int baseDayPillarIndex = 10; // 갑술(甲戌)의 인덱스

		// 경과일수 계산
		long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(baseDate, today);

		// 60갑자 순환 계산
		int todayIndex = (int) ((baseDayPillarIndex + daysBetween) % 60);
		if (todayIndex < 0) {
			todayIndex += 60; // 음수 방지
		}

		// 60갑자에서 해당 인덱스의 간지 가져오기
		String chineseGapja = GAPJA_CYCLE.get(todayIndex);

		// 한글 변환
		int stemIndex = todayIndex % 10;
		int branchIndex = todayIndex % 12;
		String koreanStem = HEAVENLY_STEMS_KOR.get(stemIndex);
		String koreanBranch = EARTHLY_BRANCHES_KOR.get(branchIndex);

		return String.format("%s%s(%s)", koreanStem, koreanBranch, chineseGapja);
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

		// 🔥 [추가 1] Person 1의 절대 기준 주입
		appendKeywords(prompt, person1Saju);

		prompt.append("\n--- 두 번째 사람: ").append(person2Name).append(" ---\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju);

		// 🔥 [추가 2] Person 2의 절대 기준 주입
		appendKeywords(prompt, person2Saju);

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
		prompt.append("만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.");
		prompt.append("- 두 사람의 일간(日干) 오행 관계와 첫인상 분석 (서로에게 어떤 매력을 느낄까?).\n");
		prompt.append("- 각자의 외적인 분위기('신살', 12운성 등)가 서로에게 어떻게 작용하는지.\n");
		prompt.append("- 관계 초반의 발전 속도 예측 (빠르게 가까워질까? 서서히 알아갈까?).\n\n");

		prompt.append("##  함께할 때의 조화와 보완 ('오행 조화')\n");
		prompt.append("만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.");
		prompt.append(
			"- 각자의 오행 분포를 비교하여, 서로의 부족한 기운을 채워주는 '상생' 관계인지, 혹은 에너지가 부딪히는 '상극' 관계인지 심층 분석.\n");
		prompt.append("- 함께 있을 때 느끼는 감정(안정감/편안함 vs 긴장감/불편함) 예측.\n");
		prompt.append("- 서로의 성장을 돕는 긍정적 측면과, 주의해야 할 부정적 측면 설명.\n\n");

		prompt.append("## 현실적인 관계에서의 역할과 갈등 (십성, '관계의 역동성')\n");
		prompt.append("만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.");
		prompt.append("- 각자의 십성(十星) 분포를 통해 관계에서의 역할 분담 예측 (주도/보조, 표현/수용 등).\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간 합(合)/충(沖)/형(刑) 관계 분석: 어떤 부분에서 조화를 이루고, 어떤 부분에서 '관계의 역동성'(갈등)이 발생하기 쉬운지.\n");
		prompt.append("- 예상되는 주요 갈등 유형과 이를 '성장의 계기'로 삼기 위한 구체적인 조언.\n\n");

		prompt.append("## 관계 발전을 위한 맞춤 조언\n");
		prompt.append("만세력 기반으로 자세하게 설명하되, 쉽고 재미있게 풀어서 설명해주세요.");
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
		appendPersonDetailInfo(prompt, name, response, null);
	}

	private void appendPersonDetailInfo(StringBuilder prompt, String name,
		ManseryeokCalculationResponse response, Integer referenceYear) {
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();
		int targetYear =
			referenceYear != null ? referenceYear : java.time.LocalDate.now().getYear();

		prompt.append("### ⚠️ [매우 중요] 일간 확인 ###\n");
		prompt.append(String.format("**%s님의 일간(日干)은 %s%s입니다.**\n",
			name,
			saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle()));
		prompt.append("절대로 다른 천간(戊, 丁, 丙 등)과 혼동하지 마세요.\n");
		prompt.append("모든 분석은 반드시 이 일간을 기준으로 작성해야 합니다.\n\n");

		// 1. 기본 정보
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("%s | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			targetYear));

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
		if (saju.getBigFortuneNumber() != null) {
			prompt.append(String.format("시작:%d세 | 방향:%s\n",
				saju.getBigFortuneNumber(),
				getDaewoonDirection(saju, input.getGender())));

			// [수정] birthYear 전달!
			int birthYear = input.getSolarDate().getYear();
			appendDaewoonSimple(prompt, saju, input.getGender(), birthYear, targetYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append(String.format("시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)\n",
				saju.getBigFortuneNumberMin(),
				saju.getBigFortuneNumberMax(),
				getDaewoonDirection(saju, input.getGender())));
			prompt.append("※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.\n");
		} else {
			prompt.append("대운 정보 없음\n");
		}
		if (saju.getUncertaintyNotes() != null && !saju.getUncertaintyNotes().isEmpty()) {
			saju.getUncertaintyNotes().forEach(note -> prompt.append("- " + note + "\n"));
		}
		prompt.append("※ 대운은 위 계산 결과를 절대 재계산/수정하지 말고 그대로 분석에 사용하세요.\n");
		prompt.append("\n");

		if (saju.getMonthlyFortunes() != null && !saju.getMonthlyFortunes().isEmpty()) {
			prompt.append("### 월운 (향후 12개월) ###\n");
			saju.getMonthlyFortunes().forEach(monthly -> {
				String monthSky =
					monthly.getMonthSky() != null ? monthly.getMonthSky().getKorean() : "?";
				String monthGround =
					monthly.getMonthGround() != null ? monthly.getMonthGround().getKorean() : "?";
				String monthSkyTenStar =
					monthly.getMonthSky() != null ? monthly.getMonthSky().getTenStar() : "?";
				String monthGroundTenStar = monthly.getMonthGround() != null
					? monthly.getMonthGround().getTenStar() : "?";
				String season = monthly.getSeason() != null ? monthly.getSeason() : "-";
				String periodStart = formatMonthPeriod(monthly.getPeriodStart());
				String periodEnd = monthly.getPeriodEnd() != null
					? formatMonthPeriod(monthly.getPeriodEnd())
					: "다음 절입 직전";

				prompt.append(String.format(
					"- %d년 %d월(%s): %s%s (천간십성:%s, 지지십성:%s) | 적용구간:%s ~ %s\n",
					monthly.getYear(),
					monthly.getMonth(),
					season,
					monthSky,
					monthGround,
					monthSkyTenStar,
					monthGroundTenStar,
					periodStart,
					periodEnd));
			});
			prompt.append("\n");
		}

		// 9. 관계성 분석 (업그레이드 버전)
		prompt.append("### 지지 관계성 (합/충/원진) ###\n");
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			saju.getGroundRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("- 특이사항 없음\n");
		}

		// 10. 천간 관계 (업그레이드 버전)
		prompt.append("### 천간 관계 (정신적 조화) ###\n");
		if (saju.getSkyRelations() != null && !saju.getSkyRelations().isEmpty()) {
			saju.getSkyRelations().forEach(rel -> prompt.append("- " + rel + "\n"));
		} else {
			prompt.append("- 특이사항 없음\n");
		}

		// 11. 삼합
		if (saju.getSamhap() != null && !saju.getSamhap().isEmpty()) {
			prompt.append("### 특수 국(局) ###\n");
			prompt.append("- " + String.join(", ", saju.getSamhap()) + "\n");
		}
		prompt.append("\n");

		// 12. 사주 강약 및 용신 (핵심 업그레이드)
		prompt.append("### 사주 강약 및 용신 (핵심) ###\n");
		if (saju.getYongsinInfo() != null) {
			prompt.append(String.format("- 강약 판단: %s (내 세력 %.1f vs 남의 세력 %.1f)\n",
				saju.getYongsinInfo().getStrength(),
				saju.getYongsinInfo().getMyScore(),
				(saju.getYongsinInfo().getTotalScore() - saju.getYongsinInfo().getMyScore())
			));
			prompt.append(String.format("- 적용 규칙: %s (%s)\n",
				saju.getYongsinInfo().getAppliedRuleName(),
				saju.getYongsinInfo().getAppliedRuleCode()));
			prompt.append(String.format("- 추천 용신: %s (%s)\n",
				saju.getYongsinInfo().getYongsin(),
				saju.getYongsinInfo().getDescription()));
			prompt.append("※ 이 용신 정보를 바탕으로 사용자에게 행운의 조언을 해주세요.\n");
		}
		prompt.append("\n");
	}

	private String formatMonthPeriod(LocalDateTime value) {
		if (value == null) {
			return "-";
		}
		return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
	}

	private String normalizeAnalysisBySubcategory(Long subcategoryId, String fullAnalysis) {
		if (fullAnalysis == null) {
			return null;
		}
		if (subcategoryId != null && subcategoryId == 20L) {
			return normalizeMoneyLuckText(fullAnalysis);
		}
		if (subcategoryId != null && subcategoryId == 21L) {
			return normalizeBusinessText(fullAnalysis);
		}
		if (isFreeFortuneSubcategory(subcategoryId)) {
			return normalizeFreeFortuneText(subcategoryId, fullAnalysis);
		}
		return fullAnalysis;
	}

	private String normalizeSummaryBySubcategory(Long subcategoryId, String summary) {
		if (summary == null) {
			return null;
		}
		if (subcategoryId != null && subcategoryId == 21L) {
			return limitBusinessSummaryLength(normalizeBusinessSummary(summary));
		}
		if (isFreeFortuneSubcategory(subcategoryId)) {
			return normalizeFreeFortuneSummary(subcategoryId, summary);
		}
		return summary;
	}

	private boolean isFreeFortuneSubcategory(Long subcategoryId) {
		if (subcategoryId == null) {
			return false;
		}
		return subcategoryId == 101L
			|| subcategoryId == 102L
			|| subcategoryId == 103L
			|| subcategoryId == 104L
			|| subcategoryId == 105L
			|| subcategoryId == 106L;
	}

	private String normalizeFreeFortuneText(Long subcategoryId, String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");
		normalized = ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN.matcher(normalized)
			.replaceAll("$1 $2");
		normalized = DATETIME_WITH_SPACE_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = DATE_WITH_DAY_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = convertYearMonthToKorean(normalized);
		normalized = mergeSingleLineBreaksWithinParagraph(normalized);

		if (subcategoryId == 101L) {
			normalized = ensureContextAwareParagraphBreaks(
				normalized,
				List.of("환경의 변화", "인간관계의 변화", "연애와 애정운", "학업 및 성취운", "건강 및 컨디션"),
				150,
				250
			);
		} else if (subcategoryId == 102L) {
			normalized = removeKeywordMetaPhrases(normalized);
			normalized = ensureKeywordParagraphBreaks(normalized);
		} else if (subcategoryId == 103L) {
			normalized = ensureContextAwareParagraphBreaks(
				normalized,
				List.of("당신의 매력 포인트", "나만의 플러팅 비법", "이것만은 주의하세요"),
				140,
				230
			);
		} else if (subcategoryId == 104L) {
			normalized = normalized.replaceAll(
				"\\[(아이돌\\s*추천|배우\\s*추천|캐릭터\\s*추천)\\]\\s*",
				"");
			normalized = ensureChemistryParagraphBreaks(normalized);
		} else if (subcategoryId == 105L) {
			normalized = ensureContextAwareParagraphBreaks(
				normalized,
				List.of("오늘의 총운", "재물운", "금전운", "애정운", "성취운"),
				130,
				220
			);
		} else if (subcategoryId == 106L) {
			normalized = normalizeMarchMonthlyText(normalized);
		}

		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeFreeFortuneSummary(Long subcategoryId, String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		if (subcategoryId == 102L) {
			normalized = removeKeywordMetaPhrases(normalized);
		}
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeMoneyLuckText(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");

		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");
		normalized = MONEY_LUCK_LETTERED_SECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");

		// 깨진 대괄호 제목 조각 정리 ([주의할 점과 / 조언] 같은 케이스)
		normalized = MONEY_LUCK_BRACKET_OPEN_FRAGMENT_PATTERN.matcher(normalized).replaceAll("$1");
		normalized = MONEY_LUCK_BRACKET_CLOSE_FRAGMENT_PATTERN.matcher(normalized).replaceAll("$1");
		normalized = MONEY_LUCK_BRACKET_ONLY_HEADING_PATTERN.matcher(normalized).replaceAll("");

		// 대표 깨짐 토큰 보정
		normalized = normalized.replace("جذب力", "흡인력");
		normalized = normalized.replace("جذب 력", "흡인력");
		normalized = normalized.replace(" جذب", " 흡인력");
		normalized = normalized.replace("جذب", "흡인력");

		normalized = ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN.matcher(normalized)
			.replaceAll("$1 $2");
		normalized = DATETIME_WITH_SPACE_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = DATE_WITH_DAY_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = convertYearMonthToKorean(normalized);

		// 비정상 유니코드(아랍/키릴) 제거
		normalized = ARABIC_OR_CYRILLIC_PATTERN.matcher(normalized).replaceAll("");

		normalized = mergeSingleLineBreaksWithinParagraph(normalized);
		normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ");
		normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeBusinessText(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");

		// UI 페이지 구분은 반드시 빈 줄 1개(\n\n)로 통일
		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");

		// 요구하지 않은 라벨/목차 제거
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");

		// 기간 표기 통일: 2026-02-04T04:38:00 / 2026-02-04 04:38 / 2026-02-04 -> 2026-02
		normalized = ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN.matcher(normalized)
			.replaceAll("$1 $2");
		normalized = DATETIME_WITH_SPACE_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = DATE_WITH_DAY_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = convertYearMonthToKorean(normalized);
		normalized = expandBusinessJargonForReadability(normalized);
		normalized = removeBusinessForbiddenAdviceLines(normalized);
		normalized = normalized.replaceAll("(?m)^.*(오행 점수|내 세력|남의 세력).*$\\n?", "");
		normalized = normalized.replaceAll("(?m)([목화토금수])\\s*\\d+\\.\\d+", "$1 기운");
		normalized = normalized.replaceAll("\\b\\d+\\.\\d+\\b", "");
		normalized = normalized.replaceAll("\\p{IsHan}+", "");

		// 문장 단위 줄바꿈을 문단 줄글로 정리
		normalized = mergeSingleLineBreaksWithinParagraph(normalized);

		// 과도한 공백 정리
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeBusinessSummary(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");
		normalized = ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN.matcher(normalized)
			.replaceAll("$1 $2");
		normalized = DATETIME_WITH_SPACE_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = DATE_WITH_DAY_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = convertYearMonthToKorean(normalized);
		normalized = expandBusinessJargonForReadability(normalized);
		normalized = removeBusinessForbiddenAdviceLines(normalized);
		normalized = normalized.replaceAll("(?m)^.*(오행 점수|내 세력|남의 세력).*$\\n?", "");
		normalized = normalized.replaceAll("(?m)([목화토금수])\\s*\\d+\\.\\d+", "$1 기운");
		normalized = normalized.replaceAll("\\b\\d+\\.\\d+\\b", "");
		normalized = normalized.replaceAll("\\p{IsHan}+", "");
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeKeywordText(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");
		normalized = removeKeywordMetaPhrases(normalized);
		normalized = ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS_PATTERN.matcher(normalized)
			.replaceAll("$1 $2");
		normalized = DATETIME_WITH_SPACE_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = DATE_WITH_DAY_PATTERN.matcher(normalized).replaceAll("$1-$2");
		normalized = convertYearMonthToKorean(normalized);
		normalized = mergeSingleLineBreaksWithinParagraph(normalized);
		normalized = ensureKeywordParagraphBreaks(normalized);
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeKeywordSummary(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		normalized = removeKeywordMetaPhrases(normalized);
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String normalizeMarchMonthlyText(String text) {
		String normalized = text == null ? "" : text;

		// 1) 섹션 제목 변형(대괄호, 줄바꿈 분리, 콜론 표기)을 표준 제목으로 통일
		normalized = normalized.replaceAll("(?is)\\[\\s*3월\\s*핵심\\s*키워드\\s*\\]", "3월 핵심 키워드");
		normalized = normalized.replaceAll("(?is)\\[\\s*금전\\s*운\\s*\\]", "금전운");
		normalized = normalized.replaceAll("(?is)\\[\\s*연애\\s*운\\s*\\]", "연애운");
		normalized = normalized.replaceAll("(?is)\\[\\s*학업\\s*운\\s*\\]", "학업운");
		normalized = normalized.replaceAll("(?is)\\[\\s*학업\\s*/\\s*일\\s*운\\s*\\]", "학업운");
		normalized = normalized.replaceAll("(?is)\\[\\s*직장\\s*운\\s*\\]", "직장/일운");
		normalized = normalized.replaceAll("(?is)\\[\\s*직장\\s*/\\s*일\\s*운\\s*\\]", "직장/일운");
		normalized = normalized.replaceAll("(?is)\\[\\s*건강\\s*운\\s*\\]", "건강운");
		normalized = normalized.replaceAll("(?is)\\[\\s*주의할\\s*점과\\s*조언\\s*\\]", "주의할 점과 조언");
		normalized = normalized.replaceAll("(?is)\\[\\s*3월운\\s*총평\\s*\\]", "3월운 총평");

		normalized = normalized.replaceAll("(?m)^\\s*3월\\s*핵심\\s*키워드\\s*[:：-]?\\s*",
			"\n\n3월 핵심 키워드\n");
		normalized = normalized.replaceAll("(?m)^\\s*금전\\s*운\\s*[:：-]?\\s*", "\n\n금전운\n");
		normalized = normalized.replaceAll("(?m)^\\s*연애\\s*운\\s*[:：-]?\\s*", "\n\n연애운\n");
		normalized = normalized.replaceAll("(?m)^\\s*학업\\s*운\\s*[:：-]?\\s*", "\n\n학업운\n");
		normalized = normalized.replaceAll("(?m)^\\s*학업\\s*/\\s*일\\s*운\\s*[:：-]?\\s*", "\n\n학업운\n");
		normalized = normalized.replaceAll("(?m)^\\s*직장\\s*운\\s*[:：-]?\\s*", "\n\n직장/일운\n");
		normalized = normalized.replaceAll("(?m)^\\s*직장\\s*/\\s*일\\s*운\\s*[:：-]?\\s*",
			"\n\n직장/일운\n");
		normalized = normalized.replaceAll("(?m)^\\s*건강\\s*운\\s*[:：-]?\\s*", "\n\n건강운\n");
		normalized = normalized.replaceAll("(?m)^\\s*주의할\\s*점과\\s*조언\\s*[:：-]?\\s*",
			"\n\n주의할 점과 조언\n");
		normalized = normalized.replaceAll("(?m)^\\s*3월운\\s*총평\\s*[:：-]?\\s*", "\n\n3월운 총평\n");
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n").trim();

		// 2) 섹션 단위로 재조립해 제목이 분리되는 문제 방지 + 섹션당 길이 상한 적용
		Map<String, StringBuilder> sectionBodies = new LinkedHashMap<>();
		for (String title : MARCH_MONTHLY_SECTION_TITLES) {
			sectionBodies.put(title, new StringBuilder());
		}

		String currentTitle = null;
		List<String> blocks = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(block -> !block.isEmpty())
			.toList();

		for (String block : blocks) {
			if (MARCH_MONTHLY_SECTION_TITLES.contains(block)) {
				currentTitle = block;
				continue;
			}

			boolean consumed = false;
			for (String title : MARCH_MONTHLY_SECTION_TITLES) {
				if (block.startsWith(title + "\n")) {
					currentTitle = title;
					String body = block.substring(title.length()).trim();
					appendSectionBody(sectionBodies.get(title), body);
					consumed = true;
					break;
				}
			}
			if (consumed) {
				continue;
			}

			if (currentTitle != null) {
				appendSectionBody(sectionBodies.get(currentTitle), block);
			}
		}

		List<String> rebuilt = new ArrayList<>();
		for (String title : MARCH_MONTHLY_SECTION_TITLES) {
			String body = sectionBodies.get(title).toString().trim();
			if (body.isEmpty()) {
				continue;
			}
			body = trimToSentenceLength(body, MARCH_MONTHLY_SECTION_MAX_CHARS);
			rebuilt.add(title + "\n" + body);
		}

		return String.join("\n\n", rebuilt).trim();
	}

	private void appendSectionBody(StringBuilder builder, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(" ");
		}
		builder.append(text.replaceAll("\\s+", " ").trim());
	}

	private String trimToSentenceLength(String text, int maxChars) {
		if (text == null) {
			return "";
		}
		String normalized = text.trim();
		if (normalized.length() <= maxChars) {
			return normalized;
		}

		int hardCut = Math.min(maxChars, normalized.length());
		int cut = -1;
		String[] markers = {"다.", "요.", "니다.", ".", "!", "?"};
		for (String marker : markers) {
			int idx = normalized.lastIndexOf(marker, hardCut);
			if (idx > cut) {
				cut = idx + marker.length();
			}
		}

		if (cut < (int) (maxChars * 0.55)) {
			cut = hardCut;
		}
		return normalized.substring(0, cut).trim();
	}

	private String normalizeChemistryText(String text) {
		String normalized = text
			.replace("\r\n", "\n")
			.replace("\r", "\n");
		normalized = BUSINESS_PAGE_BREAK_PATTERN.matcher(normalized).replaceAll("\n\n");
		normalized = BRACKET_SECTION_TITLE_PATTERN.matcher(normalized).replaceAll("");
		normalized = normalized.replaceAll(
			"(?m)^\\s*\\[(아이돌\\s*추천|배우\\s*추천|캐릭터\\s*추천)\\]\\s*\\n?",
			"");
		normalized = NUMBERED_SUBSECTION_PATTERN.matcher(normalized).replaceAll("");
		normalized = NUMBERED_LIST_PATTERN.matcher(normalized).replaceAll("");
		normalized = HASH_HEADER_PATTERN.matcher(normalized).replaceAll("");
		normalized = mergeSingleLineBreaksWithinParagraph(normalized);
		normalized = ensureChemistryParagraphBreaks(normalized);
		normalized = THREE_OR_MORE_NEWLINES_PATTERN.matcher(normalized).replaceAll("\n\n");
		return normalized.trim();
	}

	private String limitBusinessSummaryLength(String summary) {
		String normalized = summary == null ? "" : summary
			.replace("\r\n", "\n")
			.replace("\r", "\n")
			.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		List<String> lines = Arrays.stream(normalized.split("\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.limit(BUSINESS_SUMMARY_MAX_LINES)
			.toList();

		String limited = String.join("\n", lines);
		if (limited.length() > BUSINESS_SUMMARY_MAX_CHARS) {
			limited = limited.substring(0, BUSINESS_SUMMARY_MAX_CHARS).trim();
		}
		return limited;
	}

	private String expandBusinessJargonForReadability(String text) {
		String normalized = text;
		normalized = normalized.replace("수국", "수기운 결속 구조");
		normalized = normalized.replace("천간충", "천간 충돌(생각과 실행이 맞부딪히는 구조)");
		normalized = normalized.replace("양인살", "양인살(추진력이 강하지만 과속 시 마찰이 생기기 쉬운 신살)");
		normalized = normalized.replace("공망", "공망(기대와 현실이 어긋나기 쉬운 구간)");
		normalized = normalized.replace("역마살", "역마살(이동과 변화가 많아지는 기운)");
		return normalized;
	}

	private String removeBusinessForbiddenAdviceLines(String text) {
		return text.replaceAll(
			"(?m)^.*(행운의 색|개운색|개운법|청색|녹색|동쪽|서쪽|남쪽|북쪽|3과\\s*8|숫자\\s*3|숫자\\s*8).*$\\n?",
			"");
	}

	private String mergeSingleLineBreaksWithinParagraph(String text) {
		String[] paragraphBlocks = text.split("\\n\\s*\\n");
		List<String> mergedBlocks = new ArrayList<>();

		for (String block : paragraphBlocks) {
			String trimmed = block.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String merged = trimmed
				.replaceAll("\\n+", " ")
				.replaceAll("[ \\t]{2,}", " ");
			mergedBlocks.add(merged);
		}

		return String.join("\n\n", mergedBlocks);
	}

	private String removeKeywordMetaPhrases(String text) {
		String normalized = text;
		normalized = normalized.replaceAll(
			"직접\\s*대면\\s*상담하듯\\s*핵심만\\s*전해드(?:립니|릴게)다\\.?",
			"");
		normalized = normalized.replaceAll("핵심만\\s*전해드(?:립니|릴게)다\\.?", "");
		normalized = normalized.replaceAll("AI가\\s*분석한\\s*결과", "");
		return normalized;
	}

	private String ensureKeywordParagraphBreaks(String text) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		String titleLine = "";
		Matcher titleMatcher = KEYWORD_TITLE_LINE_PATTERN.matcher(normalized);
		if (titleMatcher.find() && titleMatcher.start() == 0) {
			titleLine = titleMatcher.group().trim();
			normalized = normalized.substring(titleMatcher.end()).trim();
		}

		List<String> existingParagraphs = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();

		if (existingParagraphs.size() >= 3) {
			List<String> mergedParagraphs = new ArrayList<>(existingParagraphs);
			if (!titleLine.isEmpty()) {
				mergedParagraphs.set(0, titleLine + "\n" + mergedParagraphs.get(0));
			}
			return String.join("\n\n", mergedParagraphs);
		}

		String body = ensureContextAwareParagraphBreaks(
			normalized,
			List.of("다만", "특히", "반면", "무엇보다", "결론적으로"),
			150,
			240
		);
		List<String> rebuiltParagraphs = Arrays.stream(body.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		if (rebuiltParagraphs.size() < 2 && normalized.length() > 120) {
			rebuiltParagraphs = new ArrayList<>(
				splitParagraphByContext(normalized, List.of(), 90, 150));
		}
		if (rebuiltParagraphs.isEmpty()) {
			return titleLine.isEmpty() ? normalized : titleLine + "\n" + normalized;
		}

		if (!titleLine.isEmpty() && !rebuiltParagraphs.isEmpty()) {
			List<String> titledParagraphs = new ArrayList<>(rebuiltParagraphs);
			titledParagraphs.set(0, titleLine + "\n" + titledParagraphs.get(0));
			return String.join("\n\n", titledParagraphs);
		}
		return String.join("\n\n", rebuiltParagraphs);
	}

	private String ensureChemistryParagraphBreaks(String text) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		List<String> existingParagraphs = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();
		if (existingParagraphs.size() >= 3) {
			return String.join("\n\n", existingParagraphs);
		}

		String markerSplit = normalized;
		markerSplit = markerSplit.replaceAll(
			"\\s*(아이돌\\s*1명\\s*추천\\s*문단|배우\\s*1명\\s*추천\\s*문단|캐릭터\\s*1명\\s*추천\\s*문단|아이돌\\s*추천\\s*3명|배우\\s*추천\\s*3명|캐릭터\\s*추천\\s*3명|종합 원픽\\s*TOP3)",
			"\n\n$1");
		markerSplit = markerSplit.replaceAll("\\s*(🥇|🥈|🥉)\\s*", "\n\n$1 ");
		markerSplit = markerSplit.replaceAll("(?<!\\d)([123])위\\s*[:：]", "\n\n$1위:");
		markerSplit = markerSplit.replaceAll(
			"\\s*(아이돌\\s*[1-3]위|배우\\s*[1-3]위|캐릭터\\s*[1-3]위|아이돌\\s*추천\\s*[1-3]|배우\\s*추천\\s*[1-3]|캐릭터\\s*추천\\s*[1-3]|아이돌\\s*1명|배우\\s*1명|캐릭터\\s*1명)\\s*[:：]?",
			"\n\n$1 ");
		markerSplit = markerSplit.replaceAll(
			"(?<=[.!?])\\s*(?=[가-힣A-Za-z0-9]{2,20}(은|는)\\s)",
			"\n\n");
		markerSplit = THREE_OR_MORE_NEWLINES_PATTERN.matcher(markerSplit).replaceAll("\n\n");

		List<String> markerParagraphs = Arrays.stream(markerSplit.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();
		if (markerParagraphs.size() >= 3) {
			return String.join("\n\n", markerParagraphs);
		}

		return ensureContextAwareParagraphBreaks(
			markerSplit,
			List.of("또한", "다만", "특히", "반면", "그리고"),
			140,
			240
		);
	}

	private String ensureContextAwareParagraphBreaks(String text, List<String> topicMarkers,
		int minChars, int maxChars) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return normalized;
		}

		String withMarkerHints = normalized;
		for (String marker : topicMarkers) {
			withMarkerHints = withMarkerHints.replaceAll(
				"(?<!\\n\\n)\\s+(?=" + Pattern.quote(marker) + ")",
				"\n\n");
		}
		withMarkerHints = THREE_OR_MORE_NEWLINES_PATTERN.matcher(withMarkerHints)
			.replaceAll("\n\n");

		List<String> paragraphs = Arrays.stream(withMarkerHints.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();

		List<String> rebuilt = new ArrayList<>();
		for (String paragraph : paragraphs) {
			rebuilt.addAll(splitParagraphByContext(paragraph, topicMarkers, minChars, maxChars));
		}
		return String.join("\n\n", rebuilt);
	}

	private List<String> splitParagraphByContext(String paragraph, List<String> topicMarkers,
		int minChars, int maxChars) {
		List<String> sentences = Arrays.stream(paragraph.split("(?<=[.!?])\\s+"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.toList();
		if (sentences.isEmpty()) {
			return List.of(paragraph);
		}

		if (sentences.size() == 1 && paragraph.length() <= maxChars) {
			return List.of(paragraph);
		}

		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < sentences.size(); i++) {
			String sentence = sentences.get(i);
			if (current.length() > 0) {
				current.append(" ");
			}
			current.append(sentence);

			String next = (i + 1) < sentences.size() ? sentences.get(i + 1).trim() : "";
			boolean contextShift = startsWithAny(next, topicMarkers)
				|| startsWithAny(next, FREE_PARAGRAPH_TRANSITIONS);
			boolean overSoftLimit = current.length() >= maxChars;
			boolean canSplit = current.length() >= minChars;

			if ((contextShift && canSplit) || overSoftLimit) {
				chunks.add(current.toString().trim());
				current.setLength(0);
			}
		}

		if (current.length() > 0) {
			chunks.add(current.toString().trim());
		}
		return chunks;
	}

	private boolean startsWithAny(String text, List<String> prefixes) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String trimmed = text.trim();
		for (String prefix : prefixes) {
			if (trimmed.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private String convertYearMonthToKorean(String text) {
		Matcher matcher = YEAR_MONTH_PATTERN.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			String year = matcher.group(1);
			int month = Integer.parseInt(matcher.group(2));
			String replaced = year + "년 " + month + "월";
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replaced));
		}
		matcher.appendTail(sb);
		return sb.toString();
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
		int referenceYear = java.time.LocalDate.now().getYear();

		// ===== 1. 기본 정보 =====
		prompt.append("### 기본 정보 ###\n");
		prompt.append(String.format("%s | %s | %s %s | 현재 %d년\n\n",
			name,
			"MALE".equalsIgnoreCase(input.getGender()) ? "남성" : "여성",
			input.getSolarDate(),
			input.getSolarTime(),
			referenceYear));

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

			// [수정] birthYear 전달!
			int birthYear = input.getSolarDate().getYear();
			appendDaewoonSimple(prompt, saju, input.getGender(), birthYear, referenceYear);

		} else if (saju.getBigFortuneNumberMin() != null && saju.getBigFortuneNumberMax() != null) {
			prompt.append(String.format("시작:%d~%d세 | 방향:%s (출생시간 미입력 추정)\n",
				saju.getBigFortuneNumberMin(),
				saju.getBigFortuneNumberMax(),
				getDaewoonDirection(saju, input.getGender())));
			prompt.append("※ 정확한 출생시간 입력 시 대운 시작 나이를 확정할 수 있습니다.\n");
		} else {
			prompt.append("대운 정보 없음\n");
		}
		if (saju.getUncertaintyNotes() != null && !saju.getUncertaintyNotes().isEmpty()) {
			saju.getUncertaintyNotes().forEach(note -> prompt.append("- " + note + "\n"));
		}
		prompt.append("※ 대운은 위 계산 결과를 절대 재계산/수정하지 말고 그대로 분석에 사용하세요.\n");
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
			("양".equals(yearSkyMinusPlus) ? "순행" : "역행") :
			("양".equals(yearSkyMinusPlus) ? "역행" : "순행");
	}

	/**
	 * 대운 계산 (선형 탐색을 통한 100% 정확한 인덱스 매칭)
	 */
	private void appendDaewoonSimple(StringBuilder prompt, SajuInfo saju, String gender,
		int birthYear, int referenceYear) {
		// 1. 필수 데이터 검증
		if (saju.getYearSky() == null || saju.getMonthSky() == null
			|| saju.getMonthGround() == null || saju.getBigFortuneNumber() == null) {
			prompt.append("대운 정보 없음 (필수 데이터 누락)\n");
			return;
		}

		String yearSkyMinusPlus = saju.getYearSky().getMinusPlus();
		if (yearSkyMinusPlus == null) {
			prompt.append("대운 정보 없음 (음양 정보 누락)\n");
			return;
		}

		// 2. 대운 방향 결정
		boolean isForward = "MALE".equalsIgnoreCase(gender)
			? "양".equals(yearSkyMinusPlus)
			: "음".equals(yearSkyMinusPlus);

		String flowDirection = isForward ? "순행" : "역행";
		int startAge = saju.getBigFortuneNumber();

		// 3. 월주 인덱스 추출
		String skyChar = extractFirstChar(saju.getMonthSky().getChinese());
		String groundChar = extractFirstChar(saju.getMonthGround().getChinese());

		int skyIndex = HEAVENLY_STEMS.indexOf(skyChar);
		int groundIndex = EARTHLY_BRANCHES.indexOf(groundChar);

		// Fallback: 한글로 재시도
		if (skyIndex == -1 || groundIndex == -1) {
			skyChar = extractFirstChar(saju.getMonthSky().getKorean());
			groundChar = extractFirstChar(saju.getMonthGround().getKorean());
			skyIndex = HEAVENLY_STEMS_KOR.indexOf(skyChar);
			groundIndex = EARTHLY_BRANCHES_KOR.indexOf(groundChar);
		}

		if (skyIndex == -1 || groundIndex == -1) {
			log.error("대운 계산 실패: 천간={}, 지지={}", skyChar, groundChar);
			prompt.append("대운 정보 없음 (월주 매칭 실패)\n");
			return;
		}

		// 4. ✅ [핵심 수정] 선형 탐색으로 정확한 60갑자 인덱스 찾기
		int monthGapjaIndex;
		try {
			monthGapjaIndex = findGapjaIndex(skyIndex, groundIndex);
		} catch (IllegalArgumentException e) {
			log.error("존재할 수 없는 간지 조합: 천간인덱스={}, 지지인덱스={}", skyIndex, groundIndex);
			prompt.append("대운 정보 오류 (잘못된 간지 조합)\n");
			return;
		}

		// 5. 기준 연도의 대운 위치
		int currentDaewoonIndex;
		if (saju.getBigFortuneStartYear() != null) {
			currentDaewoonIndex = Math.max(0, (referenceYear - saju.getBigFortuneStartYear()) / 10);
		} else {
			int currentAge = referenceYear - birthYear + 1; // 세는 나이 (fallback)
			currentDaewoonIndex = Math.max(0, (currentAge - startAge) / 10);
		}

		prompt.append(String.format("대운 시작: %d세 | 흐름: %s\n", startAge, flowDirection));
		String currentDaewoonKor = null;
		String currentDaewoonChi = null;
		int currentStartAge = -1;
		int currentStartYear = -1;

		// 6. 대운 출력 루프
		for (int i = currentDaewoonIndex; i < currentDaewoonIndex + 3 && i < 9; i++) {
			int age = startAge + (i * 10);
			if (age > 120) {
				break;
			}

			// 월주 다음부터 1대운 시작 (i + 1)
			int nextIndex;
			if (isForward) {
				nextIndex = (monthGapjaIndex + (i + 1)) % 60;
			} else {
				// 자바 음수 나머지 연산 안전 처리
				nextIndex = ((monthGapjaIndex - (i + 1)) % 60 + 60) % 60;
			}

			String daewoonKor = GAPJA_CYCLE_KOR.get(nextIndex);
			String daewoonChi = GAPJA_CYCLE.get(nextIndex);
			String daewoonStr = String.format("%s(%s)", daewoonKor, daewoonChi);
			int daewoonStartYear =
				saju.getBigFortuneStartYear() != null
					? saju.getBigFortuneStartYear() + (i * 10)
					: birthYear + age;

			if (i == currentDaewoonIndex) {
				prompt.append(String.format("▶ %d~%d세: %s (현재)\n", age, age + 9, daewoonStr));
				currentDaewoonKor = daewoonKor;
				currentDaewoonChi = daewoonChi;
				currentStartAge = age;
				currentStartYear = daewoonStartYear;
			} else {
				prompt.append(String.format("  %d~%d세: %s\n", age, age + 9, daewoonStr));
			}
		}

		if (currentDaewoonChi != null) {
			prompt.append(String.format(
				"[대운 고정값] 기준연도=%d, 현재대운=%s(%s), 구간=%d~%d세, 시작연도=%d\n",
				referenceYear, currentDaewoonKor, currentDaewoonChi,
				currentStartAge, currentStartAge + 9, currentStartYear
			));
		}
	}

	/**
	 * ✅ [완벽한 방법] 0~59를 순회하며 천간/지지가 일치하는 인덱스를 찾음 수학 공식 오류 가능성을 원천 차단함.
	 */
	private int findGapjaIndex(int skyIndex, int groundIndex) {
		for (int i = 0; i < 60; i++) {
			// i번째 간지의 천간 인덱스는 i % 10
			// i번째 간지의 지지 인덱스는 i % 12
			if ((i % 10) == skyIndex && (i % 12) == groundIndex) {
				return i;
			}
		}
		// 60번을 다 돌았는데도 없으면, 사주적으로 불가능한 조합(예: 갑축)이 들어온 것임
		throw new IllegalArgumentException("유효하지 않은 간지 조합입니다.");
	}

	/**
	 * 문자열 첫 글자 추출 (안전)
	 */
	private String extractFirstChar(String str) {
		if (str == null || str.isEmpty()) {
			return "";
		}
		return str.substring(0, 1);
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

	/**
	 * AI 환각 방지 및 고품질 해석을 위한 절대 기준(Fact) 주입
	 */
	private void appendKeywords(StringBuilder prompt, ManseryeokCalculationResponse response) {
		if (response == null || response.getSaju() == null) {
			return;
		}

		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();

		// 1. 오행/십성 데이터 계산 (판단을 위해 필요)
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("\n### 🔥 [절대 기준] AI 해석 가이드라인 (이 내용을 무조건 따를 것) ###\n");
		prompt.append("너의 임의적인 판단보다 아래의 '계산된 팩트'가 우선합니다. 이 정보를 해석의 뼈대로 삼으세요.\n");

		// ==========================================
		// 1. [핵심] 사주 강약 및 용신 (YongsinResult)
		// ==========================================
		if (saju.getYongsinInfo() != null) {
			prompt.append(String.format("- 사주 강약 판정: %s (내 세력 %.1f vs 남의 세력 %.1f)\n",
				saju.getYongsinInfo().getStrength(),
				saju.getYongsinInfo().getMyScore(),
				(saju.getYongsinInfo().getTotalScore() - saju.getYongsinInfo().getMyScore())
			));
			prompt.append(String.format("- 용신 판단 규칙: %s (%s)\n",
				saju.getYongsinInfo().getAppliedRuleName(),
				saju.getYongsinInfo().getAppliedRuleCode()));

			// AI에게 '신강/신약'에 따른 처세술 힌트 제공
			if (saju.getYongsinInfo().getMyScore() >= saju.getYongsinInfo().getTotalScore() / 2) {
				prompt.append("  -> (지침) 주관이 뚜렷하고 고집이 셉니다. '독단적인 행동'을 주의하라고 조언하세요.\n");
			} else {
				prompt.append("  -> (지침) 주변 환경에 잘 휩쓸립니다. '자기 주관'을 가지라고 조언하세요.\n");
			}

			prompt.append(
				String.format("- 행운의 용신(Key): %s (%s) -> 이 오행을 활용한 개운법(색상, 숫자)을 추천하세요.\n",
					saju.getYongsinInfo().getYongsin(),
					saju.getYongsinInfo().getDescription()));
		}

		// ==========================================
		// 2. [성격] 오행 과다/고립 (Ohaeng)
		// ==========================================
		// 화(Fire) 과다
		if (ohaengCounts.getOrDefault("화", 0.0) >= 4.0) {
			prompt.append("- [성격 키워드] 화(Fire) 과다: 성격이 매우 급하고 다혈질, 화려함을 추구함. 감정 조절이 핵심 과제.\n");
		}
		// 수(Water) 과다
		if (ohaengCounts.getOrDefault("수", 0.0) >= 4.0) {
			prompt.append("- [성격 키워드] 수(Water) 과다: 생각이 너무 많아 우울감 주의, 비밀이 많고 융통성이 좋음.\n");
		}
		// (필요 시 목, 금, 토 추가)

		// ==========================================
		// 3. [직업/재능] 십성 (Sipseong)
		// ==========================================
		int siksang = sipseongCounts.getOrDefault("식신", 0) + sipseongCounts.getOrDefault("상관", 0);
		int gwanseong = sipseongCounts.getOrDefault("정관", 0) + sipseongCounts.getOrDefault("편관", 0);
		int jaeseong = sipseongCounts.getOrDefault("정재", 0) + sipseongCounts.getOrDefault("편재", 0);

		if (siksang == 0) {
			prompt.append("- [단점] 무식상(No Expression): 표현력이 부족하고 행동보다 생각이 앞섬. -> '일단 저질러라'고 조언.\n");
		} else if (siksang >= 3) {
			prompt.append("- [장점] 식상 과다: 언변이 뛰어나고 끼가 넘침. 예체능, 마케팅, 영업 직무 추천.\n");
		}

		if (gwanseong == 0) {
			prompt.append("- [특징] 무관성(No Control): 자유로운 영혼. 조직 생활보다는 프리랜서나 전문직이 적합함.\n");
		}

		if (jaeseong >= 3) {
			prompt.append("- [특징] 재성 혼잡: 결과와 돈 욕심이 많으나 마무리가 약할 수 있음. '선택과 집중'을 조언.\n");
		}

		// ==========================================
		// 4. [매력/살] 신살 정보 (SinsalInfo) - DTO 활용!
		// ==========================================
		if (saju.getSinsalInfo() != null) {
			// 모든 기둥의 신살을 뒤져서 '도화'나 '역마'가 있는지 체크
			boolean hasDohwa = false;
			boolean hasYeokma = false;
			boolean hasHwagae = false;

			for (List<String> sinsals : saju.getSinsalInfo().values()) {
				if (sinsals == null) {
					continue;
				}
				for (String s : sinsals) {
					if (s.contains("도화")) {
						hasDohwa = true;
					}
					if (s.contains("역마") || s.contains("지살")) {
						hasYeokma = true;
					}
					if (s.contains("화개")) {
						hasHwagae = true;
					}
				}
			}

			if (hasDohwa) {
				prompt.append("- [매력] 도화살 보유: 사람을 끄는 묘한 매력과 인기가 있음. 연예인적 기질.\n");
			}
			if (hasYeokma) {
				prompt.append("- [활동] 역마살 보유: 한곳에 머물기보다 이동하고 여행하며 운이 트임. 해외 관련 일 추천.\n");
			}
			if (hasHwagae) {
				prompt.append("- [잠재력] 화개살 보유: 종교, 철학, 예술적 재능이 뛰어나고 고독을 즐김.\n");
			}
		}

		// ==========================================
		// 5. [관계/사건] 합충 정보 (GroundRelations) - DTO 활용!
		// ==========================================
		if (saju.getGroundRelations() != null && !saju.getGroundRelations().isEmpty()) {
			prompt.append("- [지지 관계 특이사항] 아래 요소들을 해석에 녹여내세요:\n");
			for (String relation : saju.getGroundRelations()) {
				if (relation.contains("충")) {
					prompt.append(
						String.format("  * %s: 삶의 변동성이 크거나, 해당 시기(년/월/일/시)에 변화가 많음 (투쟁, 이동).\n",
							relation));
				} else if (relation.contains("합")) {
					prompt.append(
						String.format("  * %s: 유정하고 다정다감함, 혹은 묶여서 답답할 수 있음.\n", relation));
				} else if (relation.contains("원진") || relation.contains("귀문")) {
					prompt.append(String.format("  * %s: 예민하고 직관력이 뛰어남, 신경성 질환 주의.\n", relation));
				}
			}
		}

		// ==========================================
		// 6. [부족함] 공망 (Gongmang)
		// ==========================================
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format(
				"- [공망(비어있음)] %s: 해당 글자에 해당하는 육친(부모,형제 등)이나 십성의 덕이 부족하거나 인연이 짧을 수 있음.\n",
				String.join(", ", saju.getGongmang())));
		}

		prompt.append("\n");
	}
}
