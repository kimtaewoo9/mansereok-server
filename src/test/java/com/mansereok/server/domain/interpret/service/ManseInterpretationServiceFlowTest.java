package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.client.OpenAiProperties;
import com.mansereok.server.domain.interpret.client.OpenAiProperties.ModelTier;
import com.mansereok.server.domain.interpret.client.OpenAiResponsesClient;
import com.mansereok.server.domain.interpret.client.TestOpenAiProperties;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.exception.InterpretationRunOutdatedException;
import com.mansereok.server.domain.interpret.postprocess.AnalysisNormalizer;
import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptContext;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptFactory;
import com.mansereok.server.domain.interpret.prompt.PromptContext;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.prompt.SajuPromptFactory;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import com.mansereok.server.support.fixture.ResultFixture;
import jakarta.persistence.EntityNotFoundException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
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
	/** 컨트롤러가 해석을 시작한 시각. 결과를 쓰는 두 단계와 결제 ID 로 되돌리는 단계에 그대로 넘어가야 한다. */
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);
	// 경로마다 그 경로가 맡는 종류의 상품을 쓴다.
	private static final InterpretationProduct SAJU_PRODUCT = InterpretationProduct.LIFE_OVERALL;
	private static final InterpretationProduct FREE_FORTUNE_PRODUCT = InterpretationProduct.CHANGES_2026;
	private static final InterpretationProduct COMPATIBILITY_PRODUCT = InterpretationProduct.LOVE_STORY_4;
	private static final String EMAIL = "tester@example.com";
	private static final String PAID_SINGLE_PROMPT = "유료 단일 프롬프트";
	private static final String FREE_SINGLE_PROMPT = "무료 단일 프롬프트";
	private static final String COMPATIBILITY_PROMPT = "궁합 프롬프트";
	private static final String BASE_INSTRUCTION_MARK = "30년 경력의 전문 사주명리학자";
	private static final String REUNION_INSTRUCTION_MARK = "재회 상담가";

	/** GPT 가 돌려준 사주 본문과 요약 원문. 정규화기에는 이 원문이 그대로 들어가야 한다. */
	private static final String GPT_ANALYSIS = "본문입니다";
	private static final String GPT_SUMMARY = "요약입니다";
	private static final String SAJU_JSON =
		"{\"fullAnalysis\":\"" + GPT_ANALYSIS + "\",\"summary\":\"" + GPT_SUMMARY + "\"}";
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
	private ResultService resultService;
	@Mock
	private SajuPromptFactory sajuPromptFactory;
	@Mock
	private CompatibilityPromptFactory compatibilityPromptFactory;
	@Mock
	private AnalysisNormalizer analysisNormalizer;

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
				.updateInitialStatus(anyLong(), any(), anyString(), any(), anyString()))
			.thenReturn(result);
		lenient().when(sajuResultService.saveFinalResult(anyLong(), any(), any(), any()))
			.thenReturn(result);
		lenient().when(sajuResultService.updateCompatibilityInitialStatus(anyLong(), any(), anyString(),
			anyString(), anyString(), anyString())).thenReturn(compatibilityResult);
		lenient().when(sajuResultService
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any(), any()))
			.thenReturn(compatibilityResult);

		// 정규화기에는 상품 번호와 GPT 원문이 그대로 들어가야 한다. 본문과 요약이 뒤바뀌거나 상품 번호 자리에 결제 ID 가 들어가면 스텁이
		// 맞지 않아 null 이 저장되고, 저장 인자를 확인하는 테스트가 실패한다. 유료·무료 단일 경로가 서로 다른 상품을 쓰고 궁합 경로는
		// 정규화하지 않아서 lenient 로 둔다.
		lenient().when(analysisNormalizer.normalizeAnalysis(eq(SAJU_PRODUCT.id()), eq(GPT_ANALYSIS)))
			.thenReturn("정규화된 본문");
		lenient().when(analysisNormalizer.normalizeSummary(eq(SAJU_PRODUCT.id()), eq(GPT_SUMMARY)))
			.thenReturn("정규화된 요약");
		lenient().when(analysisNormalizer.normalizeAnalysis(eq(FREE_FORTUNE_PRODUCT.id()), eq(GPT_ANALYSIS)))
			.thenReturn("정규화된 본문");
		lenient().when(analysisNormalizer.normalizeSummary(eq(FREE_FORTUNE_PRODUCT.id()), eq(GPT_SUMMARY)))
			.thenReturn("정규화된 요약");

		service = newService(sajuResultService, resultService, analysisNormalizer);
	}

	private ManseInterpretationService newService(SajuResultService sajuResults, ResultService results,
		AnalysisNormalizer normalizer) {
		return new ManseInterpretationService(
			new ObjectMapper(),
			openAiResponsesClient,
			openAiProperties,
			userService,
			ogImageGenerationService,
			discordNotificationService,
			emailService,
			sajuResults,
			results,
			sajuPromptFactory,
			compatibilityPromptFactory,
			normalizer,
			new InterpretationPipeline()
		);
	}

	private void givenSajuResponse() {
		given(openAiResponsesClient.createResponse(any())).willReturn(SAJU_JSON);
	}

	private void givenCompatibilityResponse() {
		given(openAiResponsesClient.createResponse(any())).willReturn(COMPATIBILITY_JSON);
	}

	/**
	 * 프롬프트 팩터리 스텁은 상품 번호와 사람 묶음을 정확한 값으로 건다. 명령의 상품이나 사람이 팩터리에 제대로 넘어가지 않으면 strict
	 * stubs 가 실패시킨다.
	 */
	private void callInterpret() {
		given(sajuPromptFactory.create(SAJU_PRODUCT.id(), person())).willReturn(PAID_SINGLE_PROMPT);
		service.interpret(sajuCommand(SAJU_PRODUCT));
	}

	private void callInterpretFree() {
		given(sajuPromptFactory.createFree(FREE_FORTUNE_PRODUCT.id(), person())).willReturn(FREE_SINGLE_PROMPT);
		service.interpretFree(sajuCommand(FREE_FORTUNE_PRODUCT));
	}

	private void callCompatibility() {
		callCompatibility(COMPATIBILITY_PRODUCT);
	}

	private void callCompatibility(InterpretationProduct product) {
		given(compatibilityPromptFactory.create(product.id(), persons())).willReturn(COMPATIBILITY_PROMPT);
		service.analyzeCompatibilityWithSubcategory(compatibilityCommand(product));
	}

	private void callCompatibilityFree() {
		callCompatibilityFree(COMPATIBILITY_PRODUCT);
	}

	private void callCompatibilityFree(InterpretationProduct product) {
		given(compatibilityPromptFactory.create(product.id(), persons())).willReturn(COMPATIBILITY_PROMPT);
		service.analyzeCompatibilityFree(compatibilityCommand(product));
	}

	private SajuInterpretationCommand sajuCommand(InterpretationProduct product) {
		return new SajuInterpretationCommand(PAYMENT_ID, STARTED_AT, product, USERNAME, person());
	}

	private CompatibilityInterpretationCommand compatibilityCommand(InterpretationProduct product) {
		return new CompatibilityInterpretationCommand(PAYMENT_ID, STARTED_AT, product, USERNAME, persons());
	}

	private PromptContext person() {
		return PromptContext.of("홍길동", person1);
	}

	private CompatibilityPromptContext persons() {
		return CompatibilityPromptContext.of("홍길동", person1, "김영희", person2);
	}

	private Gpt5Request captureRequest() {
		ArgumentCaptor<Gpt5Request> captor = ArgumentCaptor.forClass(Gpt5Request.class);
		verify(openAiResponsesClient).createResponse(captor.capture());
		return captor.getValue();
	}

	/** 요청의 모델·출력 토큰 상한·추론 강도·출력 길이 네 값이 모두 tier 의 값인지 본다. */
	private static void assertUsesTier(Gpt5Request request, ModelTier tier) {
		assertThat(request)
			.extracting(Gpt5Request::getModel, Gpt5Request::getMaxOutputTokens,
				sent -> sent.getReasoning().getEffort(), sent -> sent.getText().getVerbosity())
			.as("모델, 출력 토큰 상한, 추론 강도, 출력 길이")
			.containsExactly(tier.model(), tier.maxOutputTokens(), tier.reasoningEffort(), tier.verbosity());
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
				.updateInitialStatus(eq(PAYMENT_ID), eq(STARTED_AT), eq("홍길동"), any(), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(result);
			inOrder.verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
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
				eq(STARTED_AT), eq("홍길동"), anyString(), eq("김영희"), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "궁합 본문", 88, "궁합 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(compatibilityResult);
			inOrder.verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(any(), any());
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
				.updateInitialStatus(eq(PAYMENT_ID), eq(STARTED_AT), eq("홍길동"), any(), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
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
				eq(STARTED_AT), eq("홍길동"), anyString(), eq("김영희"), anyString());
			inOrder.verify(openAiResponsesClient).createResponse(any());
			inOrder.verify(sajuResultService)
				.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "궁합 본문", 88, "궁합 요약");
			inOrder.verify(ogImageGenerationService).generateAndUploadOgImage(compatibilityResult);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(any(), any());
			assertThat(captureRequest().getInput()).isEqualTo(COMPATIBILITY_PROMPT);
		}

		@Test
		@DisplayName("유료 단일 해석은 결과 준비 이메일 단계에서만 사용자를 한 번 조회한다")
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

			verify(sajuResultService).saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
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
			verify(sajuPromptFactory, never()).create(any(), any());
		}

		@Test
		@DisplayName("유료 궁합 재회운(19)은 전용 시스템 지시를 쓴다")
		void paidReunionUsesOwnInstruction() {
			givenCompatibilityResponse();

			callCompatibility(InterpretationProduct.REUNION);

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

			callCompatibilityFree(InterpretationProduct.REUNION);

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
				.contains("마크다운 강조");
			assertThat((String) field(request, "summary").get("description"))
				.contains("해요체");
			// 문자열 길이는 strict 모드 지원 여부가 불확실해 description 으로만 표현한다.
			assertThat(field(request, "fullAnalysis")).doesNotContainKey("maxLength");
			assertThat(field(request, "summary")).doesNotContainKey("maxLength");
		}

		/**
		 * 이 스키마는 유료 사주 12개와 무료 6개 상품이 함께 쓴다. 상품마다 제목 표기와 summary
		 * 길이가 달라(20·21·22·23 은 대괄호 제목 금지, summary 280자) 서식 수치를 description 에
		 * 적으면 프롬프트의 최종 출력 형식 지시와 충돌한다. 그래서 상품 중립을 강제한다.
		 */
		@Test
		@DisplayName("사주 스키마 description 은 상품마다 다른 서식을 못 박지 않는다")
		void sajuSchemaDescriptionStaysProductNeutral() {
			givenSajuResponse();

			callInterpret();

			Gpt5Request request = captureRequest();
			assertThat((String) field(request, "fullAnalysis").get("description"))
				.as("제목 표기는 프롬프트에 맡긴다")
				.contains("최종 출력 형식 지시를 따른다")
				.doesNotContain("대괄호");
			assertThat((String) field(request, "summary").get("description"))
				.as("summary 길이와 마침표 규칙은 프롬프트에 맡긴다")
				.contains("최종 출력 형식 지시를 따른다")
				.doesNotContain("250자")
				.doesNotContain("280자");
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

			verify(resultService).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any(), any());
			verify(ogImageGenerationService, never()).generateAndUploadOgImage(any(Result.class));
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
		}

		@Test
		@DisplayName("궁합 분석에서 GPT 가 실패하면 롤백하고 결과는 저장하지 않는다")
		void compatibilityFlowRollsBackOnGptFailure() {
			willThrow(new RuntimeException("GPT 터짐"))
				.given(openAiResponsesClient).createResponse(any());

			callCompatibility();

			verify(resultService).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(sajuResultService, never())
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("무료 단일 해석에서 토큰 상한에 걸리면 롤백한다")
		void freeSingleFlowRollsBackOnIncompleteResponse() {
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());

			callInterpretFree();

			verify(resultService).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any(), any());
		}

		@Test
		@DisplayName("무료 궁합 분석에서 토큰 상한에 걸리면 롤백한다")
		void freeCompatibilityFlowRollsBackOnIncompleteResponse() {
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());

			callCompatibilityFree();

			verify(resultService).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(sajuResultService, never())
				.saveCompatibilityFinalResult(anyLong(), any(), any(), any(), any());
		}
	}

	/**
	 * GPT 앞뒤의 DB 단계가 실패하는 경우다. 입력 정보 채우기가 실패하면 결과 ID 를 받지 못하지만, 컨트롤러가 이미 결과를 해석 중으로
	 * 바꿔 두었으므로 결제 ID 로 되돌려야 한다. 예전에는 결과 ID 가 null 이라 되돌리기가 아무것도 하지 않았다.
	 */
	@Nested
	@DisplayName("GPT 앞뒤의 결과 단계가 실패하면")
	class ResultStepFailure {

		@Test
		@DisplayName("단일 해석의 입력 정보 채우기가 DB 오류로 실패하면 GPT 를 부르지 않고 결제 ID 로 해석 중 상태를 되돌린다")
		void singleRollsBackByPaymentIdWhenFillFails() {
			// given
			given(sajuResultService.updateInitialStatus(eq(PAYMENT_ID), eq(STARTED_AT), eq("홍길동"), any(),
				anyString())).willThrow(new CannotAcquireLockException("잠금 대기 초과"));

			// when
			service.interpret(sajuCommand(SAJU_PRODUCT));

			// then
			verify(resultService, times(1)).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(openAiResponsesClient, never()).createResponse(any());
		}

		@Test
		@DisplayName("궁합 해석의 입력 정보 채우기가 DB 오류로 실패하면 GPT 를 부르지 않고 결제 ID 로 해석 중 상태를 되돌린다")
		void compatibilityRollsBackByPaymentIdWhenFillFails() {
			// given
			given(sajuResultService.updateCompatibilityInitialStatus(eq(PAYMENT_ID), eq(STARTED_AT), eq("홍길동"),
				anyString(), eq("김영희"), anyString())).willThrow(new CannotAcquireLockException("잠금 대기 초과"));

			// when
			service.analyzeCompatibilityWithSubcategory(compatibilityCommand(COMPATIBILITY_PRODUCT));

			// then
			verify(resultService, times(1)).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(openAiResponsesClient, never()).createResponse(any());
		}

		/**
		 * 정규화는 GPT 호출이 아니라 결과 저장 단계에서 한다. 그래서 정규화 예외는 결과를 저장하지 못한 실패로 되돌려진다.
		 */
		@Test
		@DisplayName("GPT 본문 정규화가 실패하면 결과를 저장하지 않고 결제 ID 로 해석 중 상태를 되돌린다")
		void rollsBackByPaymentIdWhenNormalizationFails() {
			// given
			givenSajuResponse();
			given(analysisNormalizer.normalizeAnalysis(SAJU_PRODUCT.id(), GPT_ANALYSIS))
				.willThrow(new IllegalArgumentException("정규화 실패"));

			// when
			callInterpret();

			// then
			verify(resultService, times(1)).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any(), any());
			verify(ogImageGenerationService, never()).generateAndUploadOgImage(any(Result.class));
		}
	}

	/**
	 * 오래 멈춘 결과 되돌리기가 먼저 돌아, 결과가 정보 입력 대기로 돌아갔거나 사용자가 같은 결제로 해석을 다시 시작한 경우다.
	 * 결과를 쓰는 서비스가 InterpretationRunOutdatedException 을 던지면, 결과는 이제 이 실행의 것이 아니므로 되돌리지도 않는다.
	 * 되돌리기를 부르면 다시 시작한 해석의 해석 중 상태를 지울 수 있다.
	 */
	@Nested
	@DisplayName("해석을 시작한 뒤 결과가 되돌려졌거나 다시 시작돼 결과 쓰기가 거부되면")
	class RunOutdated {

		@Test
		@DisplayName("입력 정보 채우기가 거부된 단일 해석은 GPT 를 부르지 않고 결과를 저장하지도 되돌리지도 않는다")
		void singleStopsBeforeGptWhenFillIsRejected() {
			// given: 대기열에서 기다리는 사이 되돌려진 결과
			given(sajuResultService.updateInitialStatus(eq(PAYMENT_ID), eq(STARTED_AT), eq("홍길동"), any(),
				anyString())).willThrow(outdated());

			// when
			service.interpret(sajuCommand(SAJU_PRODUCT));

			// then
			verify(openAiResponsesClient, never()).createResponse(any());
			verify(sajuResultService, never()).saveFinalResult(anyLong(), any(), any(), any());
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
		}

		@Test
		@DisplayName("결과 저장이 거부된 단일 해석은 결과를 되돌리지 않고 OG 이미지와 이메일도 보내지 않는다")
		void singleStopsWithoutRollbackWhenSaveIsRejected() {
			// given: GPT 를 기다리는 사이 되돌려지고 다시 시작된 결과
			givenSajuResponse();
			given(sajuResultService.saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약"))
				.willThrow(outdated());

			// when
			callInterpret();

			// then
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
			verify(ogImageGenerationService, never()).generateAndUploadOgImage(any(Result.class));
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
		}

		@Test
		@DisplayName("결과 저장이 거부된 궁합 해석은 결과를 되돌리지 않고 OG 이미지와 이메일도 보내지 않는다")
		void compatibilityStopsWithoutRollbackWhenSaveIsRejected() {
			// given
			givenCompatibilityResponse();
			given(sajuResultService.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "궁합 본문", 88, "궁합 요약"))
				.willThrow(outdated());

			// when
			callCompatibility();

			// then
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(any(), any());
			verify(ogImageGenerationService, never()).generateAndUploadOgImage(any(CompatibilityResult.class));
			verify(emailService, never()).sendResultReadyEmail(anyString(), anyString());
		}

		private InterpretationRunOutdatedException outdated() {
			return new InterpretationRunOutdatedException(PAYMENT_ID, STARTED_AT, ResultStatus.PROCESSING,
				LocalDateTime.of(2026, 9, 26, 10, 0));
		}
	}

	/**
	 * 결과를 쓰는 두 서비스(SajuResultService, ResultService)와 결과 엔티티를 진짜로 쓰고 저장소만 목으로 둔다. 저장소 목은 미리 만든
	 * 해석 중 엔티티를 돌려주기만 하므로, 해석이 끝난 뒤 그 엔티티의 상태가 DB 에 남을 상태다.
	 *
	 * <p>위 묶음들은 결과 서비스를 목으로 두어 "되돌리기를 불렀다" 까지만 본다. 되돌리기가 엔티티를 실제로 정보 입력 대기로 바꾸는지는
	 * 여기서 본다. 궁합 엔티티의 상태 변경이 인자를 무시해도 위 묶음은 통과했다.
	 */
	@Nested
	@DisplayName("실제 엔티티 상태")
	class RealEntityStatus {

		private static final Long USER_ID = 1L;
		// 해석을 시작한 시각(STARTED_AT)과 같은 2026-09-26 09:00 (서울).
		private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"),
			ZoneId.of("Asia/Seoul"));

		@Mock
		private ResultRepository resultRepository;
		@Mock
		private CompatibilityResultRepository compatibilityResultRepository;
		@Mock
		private SubCategoryRepository subCategoryRepository;

		private Result sajuRow;
		private CompatibilityResult compatibilityRow;
		private ManseInterpretationService serviceWithRealResults;

		@BeforeEach
		void setUpRealResultServices() {
			// 컨트롤러가 STARTED_AT 에 해석을 시작해 둔 행이다.
			sajuRow = ResultFixture.withId(
				ResultFixture.saju(USER_ID, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT), RESULT_ID);
			compatibilityRow = ResultFixture.withId(
				ResultFixture.compatibility(USER_ID, PAYMENT_ID, ResultStatus.PROCESSING, STARTED_AT), RESULT_ID);

			serviceWithRealResults = newService(
				new SajuResultService(resultRepository, compatibilityResultRepository),
				new ResultService(resultRepository, compatibilityResultRepository, subCategoryRepository, FIXED_CLOCK),
				analysisNormalizer);
		}

		@Test
		@DisplayName("유료 단일 해석이 성공하면 사주 결과가 COMPLETED 가 되고 정규화한 본문과 요약이 들어간다")
		void paidSingleEndsCompleted() {
			// given
			givenSajuResponse();
			givenSajuRowCanBeFilledAndSaved();
			given(sajuPromptFactory.create(SAJU_PRODUCT.id(), person())).willReturn(PAID_SINGLE_PROMPT);

			// when
			serviceWithRealResults.interpret(sajuCommand(SAJU_PRODUCT));

			// then
			assertThat(sajuRow)
				.extracting(Result::getStatus, Result::getInterpretation, Result::getSummary)
				.containsExactly(ResultStatus.COMPLETED, "정규화된 본문", "정규화된 요약");
		}

		@Test
		@DisplayName("무료 단일 해석이 성공하면 사주 결과가 COMPLETED 가 되고 정규화한 본문과 요약이 들어간다")
		void freeSingleEndsCompleted() {
			// given
			givenSajuResponse();
			givenSajuRowCanBeFilledAndSaved();
			given(sajuPromptFactory.createFree(FREE_FORTUNE_PRODUCT.id(), person())).willReturn(FREE_SINGLE_PROMPT);

			// when
			serviceWithRealResults.interpretFree(sajuCommand(FREE_FORTUNE_PRODUCT));

			// then
			assertThat(sajuRow)
				.extracting(Result::getStatus, Result::getInterpretation, Result::getSummary)
				.containsExactly(ResultStatus.COMPLETED, "정규화된 본문", "정규화된 요약");
		}

		@Test
		@DisplayName("유료 궁합 해석이 성공하면 궁합 결과가 COMPLETED 가 되고 본문·점수·요약이 들어간다")
		void paidCompatibilityEndsCompleted() {
			// given
			givenCompatibilityResponse();
			givenCompatibilityRowCanBeFilledAndSaved();
			given(compatibilityPromptFactory.create(COMPATIBILITY_PRODUCT.id(), persons()))
				.willReturn(COMPATIBILITY_PROMPT);

			// when
			serviceWithRealResults.analyzeCompatibilityWithSubcategory(compatibilityCommand(COMPATIBILITY_PRODUCT));

			// then
			assertThat(compatibilityRow)
				.extracting(CompatibilityResult::getStatus, CompatibilityResult::getInterpretation,
					CompatibilityResult::getCompatibilityScore, CompatibilityResult::getSummary)
				.containsExactly(ResultStatus.COMPLETED, "궁합 본문", 88, "궁합 요약");
		}

		@Test
		@DisplayName("무료 궁합 해석이 성공하면 궁합 결과가 COMPLETED 가 되고 본문·점수·요약이 들어간다")
		void freeCompatibilityEndsCompleted() {
			// given
			givenCompatibilityResponse();
			givenCompatibilityRowCanBeFilledAndSaved();
			given(compatibilityPromptFactory.create(COMPATIBILITY_PRODUCT.id(), persons()))
				.willReturn(COMPATIBILITY_PROMPT);

			// when
			serviceWithRealResults.analyzeCompatibilityFree(compatibilityCommand(COMPATIBILITY_PRODUCT));

			// then
			assertThat(compatibilityRow)
				.extracting(CompatibilityResult::getStatus, CompatibilityResult::getInterpretation,
					CompatibilityResult::getCompatibilityScore, CompatibilityResult::getSummary)
				.containsExactly(ResultStatus.COMPLETED, "궁합 본문", 88, "궁합 요약");
		}

		@Test
		@DisplayName("유료 단일 해석에서 GPT 가 실패하면 사주 결과가 INPUT_REQUIRED 로 돌아간다")
		void paidSingleReturnsToInputRequiredOnGptFailure() {
			// given
			willThrow(new RuntimeException("GPT 터짐")).given(openAiResponsesClient).createResponse(any());
			givenSajuRowCanBeFilled();
			given(sajuPromptFactory.create(SAJU_PRODUCT.id(), person())).willReturn(PAID_SINGLE_PROMPT);

			// when
			serviceWithRealResults.interpret(sajuCommand(SAJU_PRODUCT));

			// then
			assertThat(sajuRow.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("무료 단일 해석이 토큰 상한에 걸리면 사주 결과가 INPUT_REQUIRED 로 돌아간다")
		void freeSingleReturnsToInputRequiredOnIncompleteResponse() {
			// given
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());
			givenSajuRowCanBeFilled();
			given(sajuPromptFactory.createFree(FREE_FORTUNE_PRODUCT.id(), person())).willReturn(FREE_SINGLE_PROMPT);

			// when
			serviceWithRealResults.interpretFree(sajuCommand(FREE_FORTUNE_PRODUCT));

			// then
			assertThat(sajuRow.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("유료 궁합 해석에서 GPT 가 실패하면 궁합 결과가 INPUT_REQUIRED 로 돌아간다")
		void paidCompatibilityReturnsToInputRequiredOnGptFailure() {
			// given
			willThrow(new RuntimeException("GPT 터짐")).given(openAiResponsesClient).createResponse(any());
			givenCompatibilityRowCanBeFilled();
			given(compatibilityPromptFactory.create(COMPATIBILITY_PRODUCT.id(), persons()))
				.willReturn(COMPATIBILITY_PROMPT);

			// when
			serviceWithRealResults.analyzeCompatibilityWithSubcategory(compatibilityCommand(COMPATIBILITY_PRODUCT));

			// then
			assertThat(compatibilityRow.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		@Test
		@DisplayName("무료 궁합 해석이 토큰 상한에 걸리면 궁합 결과가 INPUT_REQUIRED 로 돌아간다")
		void freeCompatibilityReturnsToInputRequiredOnIncompleteResponse() {
			// given
			willThrow(new OpenAiIncompleteResponseException("max_output_tokens"))
				.given(openAiResponsesClient).createResponse(any());
			givenCompatibilityRowCanBeFilled();
			given(compatibilityPromptFactory.create(COMPATIBILITY_PRODUCT.id(), persons()))
				.willReturn(COMPATIBILITY_PROMPT);

			// when
			serviceWithRealResults.analyzeCompatibilityFree(compatibilityCommand(COMPATIBILITY_PRODUCT));

			// then
			assertThat(compatibilityRow.getStatus()).isEqualTo(ResultStatus.INPUT_REQUIRED);
		}

		/**
		 * 정규화기를 진짜로 쓰고 사업운(21)으로 돌린다. 정규화기에 상품 번호 대신 결제 ID 를 넘기거나 본문 대신 요약을 넘기면, 규칙 표에
		 * 없는 번호라 원문이 그대로 저장되어 제목 라벨이 남는다.
		 */
		@Test
		@DisplayName("사업운(21) 해석은 실제 정규화기를 거쳐 GPT 원문의 제목 라벨을 지운 본문을 저장한다")
		void businessLuckSavesTextWithoutTitleLabel() {
			// given
			given(openAiResponsesClient.createResponse(any())).willReturn(
				"{\"fullAnalysis\":\"[1. 총운]\\n핵심 성향은 임수 일간입니다.\",\"summary\":\"요약입니다\"}");
			givenSajuRowCanBeFilledAndSaved();
			given(sajuPromptFactory.create(InterpretationProduct.BUSINESS_LUCK.id(), person()))
				.willReturn(PAID_SINGLE_PROMPT);
			ManseInterpretationService serviceWithRealNormalizer = newService(
				new SajuResultService(resultRepository, compatibilityResultRepository),
				new ResultService(resultRepository, compatibilityResultRepository, subCategoryRepository, FIXED_CLOCK),
				new AnalysisNormalizer());

			// when
			serviceWithRealNormalizer.interpret(sajuCommand(InterpretationProduct.BUSINESS_LUCK));

			// then
			assertThat(sajuRow.getStatus()).isEqualTo(ResultStatus.COMPLETED);
			assertThat(sajuRow.getInterpretation())
				.as("정규화한 본문")
				.doesNotContain("[1. 총운]")
				.contains("핵심 성향은 임수 일간입니다.");
		}

		/** 입력 정보 채우기와 실패 뒤 되돌리기가 결제 ID 로 찾는 행. */
		private void givenSajuRowCanBeFilled() {
			given(resultRepository.findByPaymentIdForUpdate(PAYMENT_ID)).willReturn(Optional.of(sajuRow));
			given(resultRepository.save(sajuRow)).willReturn(sajuRow);
		}

		/** 위에 더해, 결과 저장이 결과 ID 로 찾는 행. */
		private void givenSajuRowCanBeFilledAndSaved() {
			givenSajuRowCanBeFilled();
			given(resultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(sajuRow));
		}

		private void givenCompatibilityRowCanBeFilled() {
			given(compatibilityResultRepository.findByPaymentIdForUpdate(PAYMENT_ID))
				.willReturn(Optional.of(compatibilityRow));
			given(compatibilityResultRepository.save(compatibilityRow)).willReturn(compatibilityRow);
		}

		private void givenCompatibilityRowCanBeFilledAndSaved() {
			givenCompatibilityRowCanBeFilled();
			given(compatibilityResultRepository.findByIdForUpdate(RESULT_ID)).willReturn(Optional.of(compatibilityRow));
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
				.sendInterpretationRequestNotification(anyLong(), anyString(), anyBoolean());

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
		}

		@Test
		@DisplayName("무료 단일 해석에서 알림이 실패해도(예전 빈 catch 자리) 해석은 끝까지 진행한다")
		void freeSingleNotificationFailureDoesNotStopFlow() {
			givenSajuResponse();
			willThrow(new RuntimeException("디스코드 장애"))
				.given(discordNotificationService)
				.sendInterpretationRequestNotification(anyLong(), anyString(), anyBoolean());

			callInterpretFree();

			verify(sajuResultService).saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
		}

		@Test
		@DisplayName("OG 이미지 생성이 실패해도 저장된 결과를 되돌리지 않고 이메일은 계속 보낸다")
		void ogImageFailureDoesNotRollback() {
			givenSajuResponse();
			willThrow(new RuntimeException("S3 장애"))
				.given(ogImageGenerationService).generateAndUploadOgImage(any(Result.class));

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
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
				.saveCompatibilityFinalResult(RESULT_ID, STARTED_AT, "궁합 본문", 88, "궁합 요약");
			verify(emailService).sendResultReadyEmail(EMAIL, "테스터");
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(any(), any());
		}

		@Test
		@DisplayName("이메일 발송이 실패해도 저장된 결과를 되돌리지 않는다")
		void emailFailureDoesNotRollback() {
			givenSajuResponse();
			willThrow(new RuntimeException("SMTP 장애"))
				.given(emailService).sendResultReadyEmail(anyString(), anyString());

			callInterpret();

			verify(sajuResultService).saveFinalResult(RESULT_ID, STARTED_AT, "정규화된 본문", "정규화된 요약");
			verify(ogImageGenerationService).generateAndUploadOgImage(result);
			verify(resultService, never()).rollbackStatusByPaymentId(any(), any());
		}
	}

	/**
	 * 해석 요청 알림은 외부 채널(Discord)로 나가고, 해석 로그는 운영 로그 수집 시스템에 쌓인다. 두 곳 모두 결제 ID 와 상품만 남기고
	 * 요청자와 궁합 상대의 이름, 계정 이메일, 생년월일은 남기지 않는다. 누구의 요청인지는 결제 ID 로 DB 에서 찾는다.
	 */
	@Nested
	@DisplayName("알림과 로그에 남기는 값")
	class PersonalDataInNoticeAndLogs {

		private final Logger appLogger = (Logger) LoggerFactory.getLogger("com.mansereok");
		private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
		private Level levelBeforeTest;

		@BeforeEach
		void captureLogsAtInfo() {
			// 테스트 JVM 의 로그 설정과 상관없이 운영과 같은 INFO 에서 본다.
			levelBeforeTest = appLogger.getLevel();
			appLogger.setLevel(Level.INFO);
			logs.start();
			appLogger.addAppender(logs);
		}

		@AfterEach
		void stopCapturingLogs() {
			appLogger.detachAppender(logs);
			logs.stop();
			appLogger.setLevel(levelBeforeTest);
		}

		@Test
		@DisplayName("유료 단일 해석 요청 알림에는 결제 ID·상품·유료 구분만 넘긴다")
		void paidSingleNoticeCarriesOnlyPaymentAndProduct() {
			// given
			givenSajuResponse();

			// when
			callInterpret();

			// then
			verify(discordNotificationService).sendInterpretationRequestNotification(PAYMENT_ID, "LIFE_OVERALL(1)", false);
		}

		@Test
		@DisplayName("무료 단일 해석 요청 알림에는 결제 ID·상품·무료 구분만 넘기고 사용자를 조회하지 않는다")
		void freeSingleNoticeCarriesOnlyPaymentAndProduct() {
			// given
			givenSajuResponse();

			// when
			callInterpretFree();

			// then
			verify(discordNotificationService).sendInterpretationRequestNotification(PAYMENT_ID, "CHANGES_2026(101)",
				true);
			verify(userService, never()).findByUsername(any());
		}

		@Test
		@DisplayName("유료 궁합 요청 알림에는 결제 ID·상품·유료 구분만 넘긴다")
		void paidCompatibilityNoticeCarriesOnlyPaymentAndProduct() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibility();

			// then
			verify(discordNotificationService).sendCompatibilityRequestNotification(PAYMENT_ID, "LOVE_STORY_4(4)", false);
		}

		@Test
		@DisplayName("무료 궁합 요청 알림에는 결제 ID·상품·무료 구분만 넘긴다")
		void freeCompatibilityNoticeCarriesOnlyPaymentAndProduct() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibilityFree(InterpretationProduct.REUNION);

			// then
			verify(discordNotificationService).sendCompatibilityRequestNotification(PAYMENT_ID, "REUNION(19)", true);
		}

		@Test
		@DisplayName("유료 단일 해석은 시작 로그에 결제 ID 와 상품을 남기고 어떤 로그에도 이름·이메일을 남기지 않는다")
		void paidSingleLogsNoPersonalData() {
			// given
			givenSajuResponse();

			// when
			callInterpret();

			// then
			assertThat(logMessages()).as("시작 로그").contains("✅ 사주 해석 요청 시작 - paymentId: 100, product: LIFE_OVERALL");
			assertThat(String.join("\n", logMessages())).doesNotContain("홍길동", EMAIL);
		}

		@Test
		@DisplayName("무료 단일 해석은 시작 로그에 결제 ID 와 상품을 남기고 어떤 로그에도 이름을 남기지 않는다")
		void freeSingleLogsNoPersonalData() {
			// given
			givenSajuResponse();

			// when
			callInterpretFree();

			// then
			assertThat(logMessages()).as("시작 로그").contains("🆓 무료 사주 해석 시작 - paymentId: 100, product: CHANGES_2026");
			assertThat(String.join("\n", logMessages())).doesNotContain("홍길동", EMAIL);
		}

		@Test
		@DisplayName("유료 궁합은 시작 로그에 결제 ID 와 상품을 남기고 어떤 로그에도 두 사람의 이름을 남기지 않는다")
		void paidCompatibilityLogsNoPersonalData() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibility();

			// then
			assertThat(logMessages()).as("시작 로그").contains("✅ 궁합 분석 요청 시작 - paymentId: 100, product: LOVE_STORY_4");
			assertThat(String.join("\n", logMessages())).doesNotContain("홍길동", "김영희", EMAIL);
		}

		@Test
		@DisplayName("무료 궁합은 시작 로그에 결제 ID 와 상품을 남기고 어떤 로그에도 두 사람의 이름을 남기지 않는다")
		void freeCompatibilityLogsNoPersonalData() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibilityFree();

			// then
			assertThat(logMessages()).as("시작 로그")
				.contains("🆓 무료 궁합/재회운 서비스 시작 - paymentId: 100, product: LOVE_STORY_4");
			assertThat(String.join("\n", logMessages())).doesNotContain("홍길동", "김영희", EMAIL);
		}

		private List<String> logMessages() {
			return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}
	}

	@Nested
	@DisplayName("비용과 스레드 풀")
	class CostAndThreadPool {

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
		@DisplayName("유료 단일과 유료 궁합은 유료 스레드 풀에서 돈다")
		void paidFlowsUsePaidExecutor() {
			assertThat(asyncExecutorOf("interpret")).isEqualTo("gptTaskExecutor");
			assertThat(asyncExecutorOf("analyzeCompatibilityWithSubcategory"))
				.isEqualTo("gptTaskExecutor");
		}

		@Test
		@DisplayName("유료 단일 요청은 primary 티어의 모델·출력 토큰 상한·추론 강도·출력 길이를 쓴다")
		void paidSingleRequestUsesPrimaryTier() {
			// given
			givenSajuResponse();

			// when
			callInterpret();

			// then
			assertUsesTier(captureRequest(), openAiProperties.primary());
		}

		/**
		 * 유료 궁합이 light 티어로 내려가면 긴 본문이 출력 토큰 상한에 걸려 잘리고 되돌려진다. 유료 단일만 보면 이 회귀를 놓친다.
		 */
		@Test
		@DisplayName("유료 궁합 요청은 primary 티어의 모델·출력 토큰 상한·추론 강도·출력 길이를 쓴다")
		void paidCompatibilityRequestUsesPrimaryTier() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibility();

			// then
			assertUsesTier(captureRequest(), openAiProperties.primary());
		}

		@Test
		@DisplayName("유료 재회운(19) 요청도 primary 티어의 모델·출력 토큰 상한·추론 강도·출력 길이를 쓴다")
		void paidReunionRequestUsesPrimaryTier() {
			// given
			givenCompatibilityResponse();

			// when
			callCompatibility(InterpretationProduct.REUNION);

			// then
			assertUsesTier(captureRequest(), openAiProperties.primary());
		}

		@Test
		@DisplayName("무료 단일 해석은 무료 스레드 풀과 light 티어를 그대로 쓴다")
		void freeSingleKeepsFreeExecutorAndLightTier() {
			givenSajuResponse();

			callInterpretFree();

			assertThat(asyncExecutorOf("interpretFree")).isEqualTo("gptFreeTaskExecutor");
			assertThat(captureRequest().getModel()).isEqualTo(openAiProperties.light().model());
		}

		/**
		 * OG 이미지를 다른 풀로 넘기면 그 풀이 가득 찼거나 먼저 닫혔을 때 이미지가 버려지고 다시 만들 길이 없다. 그래서 해석을 돌리는
		 * 스레드가 결과를 저장한 직후 그 자리에서 만든다. 여기서는 파이프라인이 OG 단계를 부른 스레드를 보고, OG 서비스에
		 * {@code @Async} 가 다시 붙지 않았는지도 함께 본다(서비스를 목으로 두어 스프링 프록시를 거치지 않기 때문이다).
		 */
		@Test
		@DisplayName("OG 이미지는 다른 풀로 넘기지 않고 해석을 돌리는 스레드에서 결과 저장 직후 바로 만든다")
		void ogImageRunsOnInterpretationThread() {
			givenSajuResponse();
			AtomicReference<String> ogImageThread = new AtomicReference<>();
			willAnswer(invocation -> {
				ogImageThread.set(Thread.currentThread().getName());
				return null;
			}).given(ogImageGenerationService).generateAndUploadOgImage(result);

			callInterpret();

			assertThat(ogImageThread.get()).isEqualTo(Thread.currentThread().getName());
			assertThat(Arrays.stream(OgImageGenerationService.class.getDeclaredMethods())
				.filter(method -> method.isAnnotationPresent(Async.class))
				.map(Method::getName))
				.as("@Async 가 붙은 OG 서비스 메서드").isEmpty();
		}
	}
}
