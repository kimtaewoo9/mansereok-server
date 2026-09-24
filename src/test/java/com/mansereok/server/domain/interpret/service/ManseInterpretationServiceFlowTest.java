package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.client.OpenAiProperties;
import com.mansereok.server.domain.interpret.client.OpenAiResponsesClient;
import com.mansereok.server.domain.interpret.client.TestOpenAiProperties;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.postprocess.AnalysisNormalizer;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptFactory;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.prompt.SajuPromptFactory;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import jakarta.persistence.EntityNotFoundException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;

/**
 * 네 갈래 해석 흐름이 공통 파이프라인을 타고 같은 순서로 도는지, 그리고 곁가지(알림·OG·이메일)
 * 실패가 본 흐름을 흔들지 않는지 확인한다. GPT 클라이언트는 mock 이라 실제 호출은 없다.
 *
 * <p>기본 STRICT_STUBS 를 쓴다. 경로마다 안 쓰이는 공통 스텁만 {@code lenient()} 로 풀고,
 * 프롬프트 팩터리 스텁은 각 호출 헬퍼에서 엄격하게 건다. 그래야 예컨대 무료 단일이
 * {@code createFree} 대신 {@code create} 를 부르기 시작하면 안 쓰인 스텁으로 바로 드러난다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManseInterpretationService 해석 흐름")
class ManseInterpretationServiceFlowTest {

	private static final String USERNAME = "user-1";
	private static final Long PAYMENT_ID = 100L;
	private static final Long RESULT_ID = 7L;
	private static final Long SUBCATEGORY_ID = 1L;
	private static final String EMAIL = "tester@example.com";

	private static final Long REUNION_SUBCATEGORY_ID = 19L;
	private static final String PAID_SINGLE_PROMPT = "유료 단일 프롬프트";
	private static final String FREE_SINGLE_PROMPT = "무료 단일 프롬프트";
	private static final String COMPATIBILITY_PROMPT = "궁합 프롬프트";
	private static final String BASE_INSTRUCTION_MARK = "30년 경력의 전문 사주명리학자";
	private static final String REUNION_INSTRUCTION_MARK = "재회 상담가";

	private static final String SAJU_JSON =
		"{\"fullAnalysis\":\"본문입니다\",\"summary\":\"요약입니다\"}";
	private static final String COMPATIBILITY_JSON =
		"{\"score\":88,\"interpretation\":\"궁합 본문\",\"summary\":\"궁합 요약\"}";

	@Mock
	private OpenAiResponsesClient openAiResponsesClient;
	@Mock
	private UserService userService;
	@Mock
	private OgImageGenerationService ogImageGenerationService;
	@Mock
	private DiscordNotificationService discordNotificationService;
	@Mock
	private EmailService emailService;
	@Mock
	private SajuResultService sajuResultService;
	@Mock
	private SajuPromptFactory sajuPromptFactory;
	@Mock
	private CompatibilityPromptFactory compatibilityPromptFactory;
	@Mock
	private AnalysisNormalizer analysisNormalizer;
	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	private final OpenAiProperties openAiProperties = TestOpenAiProperties.defaults();
	private final ManseryeokCalculationResponse person1 = PromptFixtures.person1();
	private final ManseryeokCalculationResponse person2 = PromptFixtures.person2();

	private Result result;
	private CompatibilityResult compatibilityResult;
	private User user;
	private ManseInterpretationService service;

	@BeforeEach
	void setUp() {
		result = mock(Result.class);
		compatibilityResult = mock(CompatibilityResult.class);
		user = mock(User.class);

		// 경로마다 쓰이는 것이 달라서(단일 vs 궁합, 유료 vs 무료) 공통 스텁만 lenient 로 둔다.
		lenient().when(result.getId()).thenReturn(RESULT_ID);
		lenient().when(compatibilityResult.getId()).thenReturn(RESULT_ID);
		lenient().when(user.getEmail()).thenReturn(EMAIL);
		lenient().when(user.getName()).thenReturn("테스터");

		lenient().when(userService.findByUsername(USERNAME)).thenReturn(user);

		lenient().when(sajuResultService
				.updateInitialStatus(anyLong(), anyString(), any(), anyString()))
			.thenReturn(result);
		lenient().when(sajuResultService.saveFinalResult(anyLong(), any(), any()))
			.thenReturn(result);
		lenient().when(sajuResultService.updateCompatibilityInitialStatus(anyLong(), anyString(),
			anyString(), anyString(), anyString())).thenReturn(compatibilityResult);
		lenient().when(sajuResultService
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any()))
			.thenReturn(compatibilityResult);

		lenient().when(analysisNormalizer.normalizeAnalysis(anyLong(), any()))
			.thenReturn("정규화된 본문");
		lenient().when(analysisNormalizer.normalizeSummary(anyLong(), any()))
			.thenReturn("정규화된 요약");

		service = new ManseInterpretationService(
			new ObjectMapper(),
			openAiResponsesClient,
			openAiProperties,
			userService,
			compatibilityResultRepository,
			ogImageGenerationService,
			discordNotificationService,
			emailService,
			sajuResultService,
			sajuPromptFactory,
			compatibilityPromptFactory,
			analysisNormalizer,
			new InterpretationPipeline()
		);
	}

	private void givenSajuResponse() {
		given(openAiResponsesClient.createResponse(any())).willReturn(SAJU_JSON);
	}

	private void givenCompatibilityResponse() {
		given(openAiResponsesClient.createResponse(any())).willReturn(COMPATIBILITY_JSON);
	}

	private void callInterpret() {
		given(sajuPromptFactory.create(anyLong(), any())).willReturn(PAID_SINGLE_PROMPT);
		service.interpret("홍길동", person1, USERNAME, SUBCATEGORY_ID, PAYMENT_ID, null);
	}

	private void callInterpretFree() {
		given(sajuPromptFactory.createFree(anyLong(), any())).willReturn(FREE_SINGLE_PROMPT);
		service.interpretFree("홍길동", person1, USERNAME, SUBCATEGORY_ID, PAYMENT_ID);
	}

	private void callCompatibility() {
		callCompatibility(SUBCATEGORY_ID);
	}

	private void callCompatibility(Long subcategoryId) {
		given(compatibilityPromptFactory.create(anyLong(), any())).willReturn(COMPATIBILITY_PROMPT);
		service.analyzeCompatibilityWithSubcategory("홍길동", person1, "김영희", person2,
			subcategoryId, PAYMENT_ID, USERNAME, null, null);
	}

	private void callCompatibilityFree() {
		callCompatibilityFree(SUBCATEGORY_ID);
	}

	private void callCompatibilityFree(Long subcategoryId) {
		given(compatibilityPromptFactory.create(anyLong(), any())).willReturn(COMPATIBILITY_PROMPT);
		service.analyzeCompatibilityFree("홍길동", person1, "김영희", person2,
			subcategoryId, PAYMENT_ID, USERNAME);
	}

	private Gpt5Request captureRequest() {
		ArgumentCaptor<Gpt5Request> captor = ArgumentCaptor.forClass(Gpt5Request.class);
		verify(openAiResponsesClient).createResponse(captor.capture());
		return captor.getValue();
	}

	private String asyncExecutorOf(String methodName) {
		for (Method method : ManseInterpretationService.class.getDeclaredMethods()) {
			if (method.getName().equals(methodName)) {
				Async async = method.getAnnotation(Async.class);
				return async == null ? null : async.value();
			}
		}
		throw new IllegalStateException("메서드를 찾을 수 없습니다: " + methodName);
	}

	@Nested
	@DisplayName("정상 흐름")
	class HappyPath {

		@Test
		@DisplayName("유료 단일 해석은 초기 상태 갱신 → 저장 → OG 이미지 → 이메일 순서로 진행한다")
		void paidSingleFlowRunsInOrder() {
			givenSajuResponse();

			callInterpret();

			InOrder inOrder = inOrder(sajuResultService, openAiResponsesClient,
				ogImageGenerationService, emailService);
			inOrder.verify(sajuResultService)
				.updateInitialStatus(eq(PAYMENT_ID), eq("홍길동"), any(), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(result);
			inOrder.verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(sajuResultService, never()).rollbackStatus(any());
			assertThat(captureRequest().getInput()).isEqualTo(PAID_SINGLE_PROMPT);
		}

		@Test
		@DisplayName("유료 궁합 분석은 초기 상태 갱신 → 저장 → OG 이미지 → 이메일 순서로 진행한다")
		void paidCompatibilityFlowRunsInOrder() {
			givenCompatibilityResponse();

			callCompatibility();

			InOrder inOrder = inOrder(sajuResultService, openAiResponsesClient,
				ogImageGenerationService, emailService);
			inOrder.verify(sajuResultService).updateCompatibilityInitialStatus(eq(PAYMENT_ID),
				eq("홍길동"), anyString(), eq("김영희"), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveCompatibilityFinalResult(RESULT_ID, "궁합 본문", 88, "궁합 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(compatibilityResult);
			inOrder.verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(sajuResultService, never()).rollbackCompatibilityStatus(any());
			assertThat(captureRequest().getInput()).isEqualTo(COMPATIBILITY_PROMPT);
		}

		@Test
		@DisplayName("무료 단일 해석은 초기 상태 갱신 → 저장 → OG 이미지 순서로 진행하고 이메일은 보내지 않는다")
		void freeSingleFlowRunsInOrder() {
			givenSajuResponse();

			callInterpretFree();

			InOrder inOrder = inOrder(sajuResultService, openAiResponsesClient,
				ogImageGenerationService);
			inOrder.verify(sajuResultService)
				.updateInitialStatus(eq(PAYMENT_ID), eq("홍길동"), any(), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(sajuResultService, never()).rollbackStatus(any());
			assertThat(captureRequest().getInput()).isEqualTo(FREE_SINGLE_PROMPT);
		}

		@Test
		@DisplayName("무료 궁합 분석은 초기 상태 갱신 → 저장 → OG 이미지 순서로 진행하고 이메일은 보내지 않는다")
		void freeCompatibilityFlowRunsInOrder() {
			givenCompatibilityResponse();

			callCompatibilityFree();

			InOrder inOrder = inOrder(sajuResultService, openAiResponsesClient,
				ogImageGenerationService);
			inOrder.verify(sajuResultService).updateCompatibilityInitialStatus(eq(PAYMENT_ID),
				eq("홍길동"), anyString(), eq("김영희"), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveCompatibilityFinalResult(RESULT_ID, "궁합 본문", 88, "궁합 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(compatibilityResult);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(sajuResultService, never()).rollbackCompatibilityStatus(any());
			assertThat(captureRequest().getInput()).isEqualTo(COMPATIBILITY_PROMPT);
		}

		@Test
		@DisplayName("유료 단일 해석은 알림과 이메일이 같은 사용자를 보므로 조회를 한 번만 한다")
		void paidSingleFlowLooksUpUserOnce() {
			givenSajuResponse();

			callInterpret();

			verify(userService, times(1)).findByUsername(USERNAME);
		}

		/**
		 * 사용자 조회는 알림과 이메일에서만 쓰는 곁가지다. 예전에는 저장 앞 바깥 try 안에서 불러서
		 * 조회가 실패하면 이미 값을 치른 GPT 결과를 버리고 INPUT_REQUIRED 로 되돌렸다.
		 * 이제는 결과를 저장하고 이메일만 건너뛴다. 이 차이를 여기서 고정한다.
		 */
		@Test
		@DisplayName("사용자 조회가 실패해도 GPT 결과는 저장하고 이메일만 건너뛴다")
		void userLookupFailureStillSavesResult() {
			givenSajuResponse();
			given(userService.findByUsername(USERNAME))
				.willThrow(new EntityNotFoundException("사용자 없음"));

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(sajuResultService, never()).rollbackStatus(any());
		}
	}

	@Nested
	@DisplayName("프롬프트와 시스템 지시")
	class PromptAndInstruction {

		@Test
		@DisplayName("유료 단일은 create 프롬프트와 기본 시스템 지시를 보낸다")
		void paidSingleUsesCreateAndBaseInstruction() {
			givenSajuResponse();

			callInterpret();

			Gpt5Request request = captureRequest();
			assertThat(request.getInput()).isEqualTo(PAID_SINGLE_PROMPT);
			assertThat(request.getInstructions()).contains(BASE_INSTRUCTION_MARK);
			assertThat(request.getInstructions()).doesNotContain(REUNION_INSTRUCTION_MARK);
		}

		@Test
		@DisplayName("무료 단일은 createFree 프롬프트와 기본 시스템 지시를 보낸다")
		void freeSingleUsesCreateFreeAndBaseInstruction() {
			givenSajuResponse();

			callInterpretFree();

			Gpt5Request request = captureRequest();
			assertThat(request.getInput()).isEqualTo(FREE_SINGLE_PROMPT);
			assertThat(request.getInstructions()).contains(BASE_INSTRUCTION_MARK);
			verify(sajuPromptFactory, never()).create(anyLong(), any());
		}

		@Test
		@DisplayName("유료 궁합 재회운(19)은 전용 시스템 지시를 쓴다")
		void paidReunionUsesOwnInstruction() {
			givenCompatibilityResponse();

			callCompatibility(REUNION_SUBCATEGORY_ID);

			assertThat(captureRequest().getInstructions()).contains(REUNION_INSTRUCTION_MARK);
		}

		@Test
		@DisplayName("유료 궁합 일반(19 아님)은 기본 시스템 지시를 쓴다")
		void paidCompatibilityUsesBaseInstruction() {
			givenCompatibilityResponse();

			callCompatibility();

			Gpt5Request request = captureRequest();
			assertThat(request.getInstructions()).contains(BASE_INSTRUCTION_MARK);
			assertThat(request.getInstructions()).doesNotContain(REUNION_INSTRUCTION_MARK);
		}

		@Test
		@DisplayName("무료 궁합은 재회운(19)이어도 기본 시스템 지시를 쓴다")
		void freeCompatibilityNeverUsesReunionInstruction() {
			givenCompatibilityResponse();

			callCompatibilityFree(REUNION_SUBCATEGORY_ID);

			Gpt5Request request = captureRequest();
			assertThat(request.getInput()).isEqualTo(COMPATIBILITY_PROMPT);
			assertThat(request.getInstructions()).contains(BASE_INSTRUCTION_MARK);
			assertThat(request.getInstructions()).doesNotContain(REUNION_INSTRUCTION_MARK);
		}
	}

	@Nested
	@DisplayName("Structured Outputs 스키마")
	class OutputSchema {

		@SuppressWarnings("unchecked")
		private static Map<String, Object> properties(Gpt5Request request) {
			Map<String, Object> format = request.getText().getFormat();
			assertThat(format).containsEntry("type", "json_schema");
			assertThat(format).containsEntry("strict", true);
			Map<String, Object> schema = (Map<String, Object>) format.get("schema");
			assertThat(schema).containsEntry("additionalProperties", false);
			return (Map<String, Object>) schema.get("properties");
		}

		@SuppressWarnings("unchecked")
		private static Map<String, Object> field(Gpt5Request request, String name) {
			return (Map<String, Object>) properties(request).get(name);
		}

		@Test
		@DisplayName("사주 스키마는 두 필드의 서식 규칙을 description 으로 알려 준다")
		void sajuSchemaDescribesBothFields() {
			givenSajuResponse();

			callInterpret();

			Gpt5Request request = captureRequest();
			assertThat(properties(request)).containsOnlyKeys("fullAnalysis", "summary");
			assertThat((String) field(request, "fullAnalysis").get("description"))
				.contains("줄바꿈 두 번")
				.contains("목록 기호")
				.contains("대괄호");
			assertThat((String) field(request, "summary").get("description"))
				.contains("250자 이내")
				.contains("마침표");
			// 문자열 길이는 strict 모드 지원 여부가 불확실해 description 으로만 표현한다.
			assertThat(field(request, "fullAnalysis")).doesNotContainKey("maxLength");
			assertThat(field(request, "summary")).doesNotContainKey("maxLength");
		}

		@Test
		@DisplayName("궁합 스키마는 score 를 0~100 정수로 가둔다")
		void compatibilitySchemaBoundsScore() {
			givenCompatibilityResponse();

			callCompatibility();

			Gpt5Request request = captureRequest();
			assertThat(properties(request))
				.containsOnlyKeys("score", "interpretation", "summary");
			assertThat(field(request, "score"))
				.containsEntry("type", "integer")
				.containsEntry("minimum", 0)
				.containsEntry("maximum", 100);
			assertThat((String) field(request, "interpretation").get("description"))
				.contains("줄바꿈 두 번");
		}
	}

	@Nested
	@DisplayName("GPT 호출 실패")
	class GptFailure {

		@Test
		@DisplayName("단일 해석에서 GPT 가 실패하면 롤백하고 결과는 저장하지 않는다")
		void singleFlowRollsBackOnGptFailure() {
			willThrow(new RuntimeException("GPT 터짐"))
				.given(openAiResponsesClient).createResponse(any());

			callInterpret();

			verify(sajuResultService).rollbackStatus(RESULT_ID);
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any());
			verify(ogImageGenerationService, never()).generateAndUploadOgImage(any(Result.class));
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
		}

		@Test
		@DisplayName("궁합 분석에서 GPT 가 실패하면 롤백하고 결과는 저장하지 않는다")
		void compatibilityFlowRollsBackOnGptFailure() {
			willThrow(new RuntimeException("GPT 터짐"))
				.given(openAiResponsesClient).createResponse(any());

			callCompatibility();

			verify(sajuResultService).rollbackCompatibilityStatus(RESULT_ID);
			verify(sajuResultService, never())
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any());
		}

		@Test
		@DisplayName("무료 단일 해석에서 토큰 상한에 걸리면 롤백한다")
		void freeSingleFlowRollsBackOnIncompleteResponse() {
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());

			callInterpretFree();

			verify(sajuResultService).rollbackStatus(RESULT_ID);
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any());
		}

		@Test
		@DisplayName("무료 궁합 분석에서 토큰 상한에 걸리면 롤백한다")
		void freeCompatibilityFlowRollsBackOnIncompleteResponse() {
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());

			callCompatibilityFree();

			verify(sajuResultService).rollbackCompatibilityStatus(RESULT_ID);
			verify(sajuResultService, never())
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any());
		}
	}

	@Nested
	@DisplayName("곁가지 실패")
	class SideStepFailure {

		@Test
		@DisplayName("알림 전송이 실패해도 해석은 끝까지 진행한다")
		void notificationFailureDoesNotStopFlow() {
			givenSajuResponse();
			willThrow(new RuntimeException("디스코드 장애"))
				.given(discordNotificationService)
				.sendInterpretationRequestNotification(anyString(), any(), anyString(), anyLong());

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(sajuResultService, never()).rollbackStatus(any());
		}

		@Test
		@DisplayName("무료 단일 해석에서 알림이 실패해도(예전 빈 catch 자리) 해석은 끝까지 진행한다")
		void freeSingleNotificationFailureDoesNotStopFlow() {
			givenSajuResponse();
			willThrow(new RuntimeException("디스코드 장애"))
				.given(discordNotificationService)
				.sendInterpretationRequestNotification(anyString(), any(), anyString(), anyLong());

			callInterpretFree();

			verify(sajuResultService).saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(sajuResultService, never()).rollbackStatus(any());
		}

		@Test
		@DisplayName("OG 이미지 생성이 실패해도 저장된 결과를 되돌리지 않고 이메일은 계속 보낸다")
		void ogImageFailureDoesNotRollback() {
			givenSajuResponse();
			willThrow(new RuntimeException("S3 장애"))
				.given(ogImageGenerationService).generateAndUploadOgImage(any(Result.class));

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(sajuResultService, never()).rollbackStatus(any());
		}

		@Test
		@DisplayName("궁합 분석에서 OG 이미지가 실패해도 저장된 결과를 되돌리지 않는다")
		void compatibilityOgImageFailureDoesNotRollback() {
			givenCompatibilityResponse();
			willThrow(new RuntimeException("S3 장애"))
				.given(ogImageGenerationService)
				.generateAndUploadOgImage(any(CompatibilityResult.class));

			callCompatibility();

			verify(sajuResultService)
				.saveCompatibilityFinalResult(RESULT_ID, "궁합 본문", 88, "궁합 요약");
			verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(sajuResultService, never()).rollbackCompatibilityStatus(any());
		}

		@Test
		@DisplayName("이메일 발송이 실패해도 저장된 결과를 되돌리지 않는다")
		void emailFailureDoesNotRollback() {
			givenSajuResponse();
			willThrow(new RuntimeException("SMTP 장애"))
				.given(emailService).sendResultReadyEmail(anyString(), anyString());

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(sajuResultService, never()).rollbackStatus(any());
		}
	}

	@Nested
	@DisplayName("비용과 동시성")
	class CostAndConcurrency {

		@Test
		@DisplayName("무료 궁합은 light 티어 모델과 토큰 상한을 쓴다")
		void freeCompatibilityUsesLightTier() {
			givenCompatibilityResponse();

			callCompatibilityFree();

			Gpt5Request request = captureRequest();
			assertThat(request.getModel()).isEqualTo(openAiProperties.light().model());
			assertThat(request.getMaxOutputTokens())
				.isEqualTo(openAiProperties.light().maxOutputTokens());
			assertThat(request.getReasoning().getEffort())
				.isEqualTo(openAiProperties.light().reasoningEffort());
			assertThat(request.getModel()).isNotEqualTo(openAiProperties.primary().model());
		}

		@Test
		@DisplayName("무료 궁합은 무료 전용 스레드 풀에서 돈다")
		void freeCompatibilityUsesFreeExecutor() {
			assertThat(asyncExecutorOf("analyzeCompatibilityFree"))
				.isEqualTo("gptFreeTaskExecutor");
		}

		@Test
		@DisplayName("유료 경로는 유료 스레드 풀과 primary 티어를 그대로 쓴다")
		void paidFlowsKeepPaidExecutorAndPrimaryTier() {
			givenSajuResponse();

			callInterpret();

			assertThat(asyncExecutorOf("interpret")).isEqualTo("gptTaskExecutor");
			assertThat(asyncExecutorOf("analyzeCompatibilityWithSubcategory"))
				.isEqualTo("gptTaskExecutor");
			assertThat(captureRequest().getModel())
				.isEqualTo(openAiProperties.primary().model());
		}

		@Test
		@DisplayName("무료 단일 해석은 무료 스레드 풀과 light 티어를 그대로 쓴다")
		void freeSingleKeepsFreeExecutorAndLightTier() {
			givenSajuResponse();

			callInterpretFree();

			assertThat(asyncExecutorOf("interpretFree")).isEqualTo("gptFreeTaskExecutor");
			assertThat(captureRequest().getModel()).isEqualTo(openAiProperties.light().model());
		}
	}
}
