package com.mansereok.server.domain.interpret.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mansereok.server.domain.interpret.controller.ManseryeokController;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptContext;
import com.mansereok.server.domain.interpret.prompt.PromptContext;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.scheduler.StaleProcessingResultScheduler;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 해석 흐름(컨트롤러 → 유료 스레드 풀 → 결과 저장)을 실제 엔티티와 MySQL 로 돌려, 해석이 끝난 뒤 결과 상태가 DB 에 어떻게 남는지
 * 확인한다.
 *
 * <p>ManseInterpretationServiceFlowTest 는 저장소를 목으로 두므로 행 잠금, 조건부 UPDATE, 커넥션 풀, 스레드 풀 거부처럼 DB 와
 * 스레드가 정하는 일은 보지 못한다. 여기서는 OpenAI 호출(바탕 클래스의 openAiResponsesClient)만 목으로 두고 나머지는 진짜를 쓴다.
 * 해석은 @Async 스레드에서 끝나므로 sleep 없이 Awaitility 로 기다리고, 순서를 정해야 하는 곳은 OpenAI 호출 안에서 래치로 해석을
 * 붙잡는다.
 *
 * <p>HTTP 응답 코드(202·409·503)는 스프링 MVC 를 그대로 거치는 MockMvc 로 본다. 보안 필터는 붙이지 않으므로 요청자 이름은 비어
 * 있고, 결과 준비 이메일 단계만 사용자를 찾지 못해 실패 로그를 남긴다. 결과 상태에는 영향이 없다.
 *
 * <p>결제 ID 와 사용자 ID 는 실행마다 새로 만든다. 행은 이번 실행의 사용자 ID 로 만들고 뒤 정리에서 그 사용자 ID 와 이름으로만
 * 지운다. 결과 표는 결제 표를 참조하지 않으므로 결제 행은 만들지 않는다.
 */
class InterpretationFlowMySqlTest extends InterpretationMySqlTest {

	// 해석 한 건은 OpenAI 호출이 목이라 수백 밀리초 안에 끝난다.
	private static final Duration FLOW_WAIT = Duration.ofSeconds(10);
	// 50건이 유료 풀(스레드 25개)에서 OG 이미지까지 그리며 끝나기를 기다린다.
	private static final Duration MANY_FLOWS_WAIT = Duration.ofSeconds(60);
	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리는 시간을 재게 된다.
	private static final int REQUEST_THREADS = 10;
	private static final int PAYMENTS_PER_THREAD = 5;
	private static final long SAJU_SUBCATEGORY_ID = 1L;

	// 사업운(21) 정규화 규칙은 대괄호 제목 라벨을 지운다.
	private static final String BUSINESS_JSON =
		"{\"fullAnalysis\":\"[1. 총운]\\n핵심 성향은 임수 일간입니다.\",\"summary\":\"요약입니다\"}";
	// 무료 운세(101) 정규화 규칙은 한 줄에 이어 쓴 본문을 항목 머리말(환경의 변화, 인간관계의 변화 등)마다 문단으로 나눈다.
	private static final String FREE_FORTUNE_JSON =
		"{\"fullAnalysis\":\"환경의 변화에서 이동수가 강하게 들어옵니다. 인간관계의 변화에서는 조율이 필요합니다.\","
			+ "\"summary\":\"요약입니다\"}";
	private static final String COMPATIBILITY_JSON =
		"{\"score\":88,\"interpretation\":\"궁합 본문\",\"summary\":\"궁합 요약\"}";

	@Autowired
	private ManseInterpretationService manseInterpretationService;
	@Autowired
	private ResultService resultService;
	@Autowired
	private ResultRepository resultRepository;
	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;
	@Autowired
	private ManseryeokController controller;
	@Autowired
	private StaleProcessingResultScheduler staleProcessingResultScheduler;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private WebApplicationContext webApplicationContext;
	@Autowired
	@Qualifier("gptTaskExecutor")
	private Executor paidPool;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 사용자 ID·결제 ID(UNIQUE)·사용자 이름이 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runKey = Long.parseLong(runId, 16) * 100;
	private final Long userId = runKey;
	private final String username = "flow_" + runId;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
	}

	@Test
	@DisplayName("유료 단일 해석이 성공하면 결과가 COMPLETED 가 되고 사업운(21) 본문의 제목 라벨이 지워져 저장된다")
	void paidSingleCompletesWithNormalizedText() {
		// given
		Long paymentId = paymentId(1);
		given(openAiResponsesClient.createResponse(any())).willReturn(BUSINESS_JSON);
		LocalDateTime startedAt = startSaju(paymentId);

		// when
		manseInterpretationService.interpret(sajuCommand(paymentId, startedAt, InterpretationProduct.BUSINESS_LUCK));

		// then
		awaitSajuStatus(paymentId, "COMPLETED");
		assertThat(sajuText(paymentId))
			.as("저장된 본문")
			.doesNotContain("[1. 총운]")
			.contains("핵심 성향은 임수 일간입니다.");
	}

	@Test
	@DisplayName("무료 단일 해석이 성공하면 결과가 COMPLETED 가 되고 무료 운세(101) 본문이 항목마다 문단으로 나뉘어 저장된다")
	void freeSingleCompletesWithNormalizedText() {
		// given
		Long paymentId = paymentId(1);
		given(openAiResponsesClient.createResponse(any())).willReturn(FREE_FORTUNE_JSON);
		LocalDateTime startedAt = startSaju(paymentId);

		// when
		manseInterpretationService.interpretFree(
			sajuCommand(paymentId, startedAt, InterpretationProduct.CHANGES_2026));

		// then
		awaitSajuStatus(paymentId, "COMPLETED");
		assertThat(sajuText(paymentId)).as("저장된 본문")
			.isEqualTo("환경의 변화에서 이동수가 강하게 들어옵니다.\n\n인간관계의 변화에서는 조율이 필요합니다.");
	}

	@Test
	@DisplayName("유료 궁합 해석이 성공하면 궁합 결과가 COMPLETED 가 되고 본문·점수·요약이 저장된다")
	void paidCompatibilityCompletes() {
		// given
		Long paymentId = paymentId(1);
		given(openAiResponsesClient.createResponse(any())).willReturn(COMPATIBILITY_JSON);
		LocalDateTime startedAt = startCompatibility(paymentId);

		// when
		manseInterpretationService.analyzeCompatibilityWithSubcategory(
			compatibilityCommand(paymentId, startedAt, InterpretationProduct.LOVE_STORY_4));

		// then
		awaitCompatibilityStatus(paymentId, "COMPLETED");
		assertThat(compatibilityColumns(paymentId)).containsExactly("궁합 본문", 88, "궁합 요약");
	}

	@Test
	@DisplayName("무료 궁합 해석이 성공하면 궁합 결과가 COMPLETED 가 되고 본문·점수·요약이 저장된다")
	void freeCompatibilityCompletes() {
		// given
		Long paymentId = paymentId(1);
		given(openAiResponsesClient.createResponse(any())).willReturn(COMPATIBILITY_JSON);
		LocalDateTime startedAt = startCompatibility(paymentId);

		// when
		manseInterpretationService.analyzeCompatibilityFree(
			compatibilityCommand(paymentId, startedAt, InterpretationProduct.REUNION));

		// then
		awaitCompatibilityStatus(paymentId, "COMPLETED");
		assertThat(compatibilityColumns(paymentId)).containsExactly("궁합 본문", 88, "궁합 요약");
	}

	/**
	 * 궁합 결과의 되돌리기는 예전에 상태 인자를 무시해, 실패한 궁합이 PROCESSING 에 갇혔다. 목 테스트는 되돌리기 호출까지만 봐서 이를
	 * 놓쳤다. 여기서는 DB 에 남은 상태를 본다.
	 */
	@Test
	@DisplayName("GPT 호출이 실패하면 사주 결과와 궁합 결과 모두 DB 에서 INPUT_REQUIRED 로 돌아간다")
	void bothResultKindsReturnToInputRequiredWhenGptFails() {
		// given
		Long sajuPayment = paymentId(1);
		Long compatibilityPayment = paymentId(2);
		willThrow(new IllegalStateException("OpenAI 장애")).given(openAiResponsesClient).createResponse(any());
		LocalDateTime sajuStartedAt = startSaju(sajuPayment);
		LocalDateTime compatibilityStartedAt = startCompatibility(compatibilityPayment);

		// when
		manseInterpretationService.interpret(
			sajuCommand(sajuPayment, sajuStartedAt, InterpretationProduct.LIFE_OVERALL));
		manseInterpretationService.analyzeCompatibilityWithSubcategory(
			compatibilityCommand(compatibilityPayment, compatibilityStartedAt, InterpretationProduct.LOVE_STORY_4));

		// then
		awaitSajuStatus(sajuPayment, "INPUT_REQUIRED");
		awaitCompatibilityStatus(compatibilityPayment, "INPUT_REQUIRED");
	}

	@Test
	@DisplayName("해석이 OpenAI 응답을 기다리는 동안 같은 결제로 다시 요청하면 409 로 끝나 OpenAI 는 한 번만 불리고, 응답이 오면 COMPLETED 가 된다")
	void secondRequestWhileWaitingForOpenAiGetsConflict() throws Exception {
		// given: 첫 요청의 해석이 OpenAI 호출 안에서 붙잡혀 있다
		Long paymentId = paymentId(1);
		saveSajuRow(paymentId);
		CountDownLatch openAiCalled = new CountDownLatch(1);
		CountDownLatch releaseOpenAi = new CountDownLatch(1);
		holdOpenAiCalls(openAiCalled, releaseOpenAi, BUSINESS_JSON);
		try {
			mockMvc.perform(interpretRequest(paymentId)).andExpect(status().isAccepted());
			assertThat(openAiCalled.await(10, SECONDS)).as("첫 요청의 해석이 OpenAI 호출에 들어갔다").isTrue();

			// when
			ResultActions second = mockMvc.perform(interpretRequest(paymentId));

			// then
			second.andExpect(status().isConflict());
			assertThat(sajuStatus(paymentId)).as("첫 해석이 붙잡혀 있는 동안의 상태").isEqualTo("PROCESSING");
		} finally {
			releaseOpenAi.countDown();
		}
		awaitSajuStatus(paymentId, "COMPLETED");
		then(openAiResponsesClient).should(times(1)).createResponse(any());
	}

	/**
	 * 컨트롤러는 제출이 거부되면 그 요청이 시작한 해석만 되돌린다. 되돌리기는 결제 ID 와 해석을 시작한 시각으로 행을 고르므로, 같은 풀에서
	 * 돌고 있는 다른 결제의 해석 중 상태는 그대로다.
	 */
	@Test
	@DisplayName("유료 스레드 풀이 가득 차 다른 결제의 요청이 503 으로 거부되면 그 결제만 INPUT_REQUIRED 로 돌아가고, 해석 중인 결제는 PROCESSING 으로 남았다가 COMPLETED 가 된다")
	void rejectedSubmissionRollsBackOnlyItsOwnPayment() throws Exception {
		// given: 결제 A 의 해석이 OpenAI 호출에서 붙잡혀 있고, 유료 풀의 남은 스레드와 대기열은 멈춰 있는 작업이 모두 채웠다
		Long runningPayment = paymentId(1);
		Long rejectedPayment = paymentId(2);
		saveSajuRow(runningPayment);
		saveSajuRow(rejectedPayment);
		CountDownLatch openAiCalled = new CountDownLatch(1);
		CountDownLatch releaseOpenAi = new CountDownLatch(1);
		CountDownLatch releaseFillers = new CountDownLatch(1);
		holdOpenAiCalls(openAiCalled, releaseOpenAi, BUSINESS_JSON);
		try {
			mockMvc.perform(interpretRequest(runningPayment)).andExpect(status().isAccepted());
			assertThat(openAiCalled.await(10, SECONDS)).as("결제 A 의 해석이 OpenAI 호출에 들어갔다").isTrue();
			fillUntilRejected(paidPool, releaseFillers);

			// when
			ResultActions rejected = mockMvc.perform(interpretRequest(rejectedPayment));

			// then
			rejected.andExpect(status().isServiceUnavailable());
			assertThat(sajuStatus(rejectedPayment)).as("거부된 결제 B").isEqualTo("INPUT_REQUIRED");
			assertThat(sajuStatus(runningPayment)).as("해석 중인 결제 A").isEqualTo("PROCESSING");
		} finally {
			releaseFillers.countDown();
			releaseOpenAi.countDown();
		}
		awaitSajuStatus(runningPayment, "COMPLETED");
	}

	/**
	 * 서로 다른 결제는 서로의 행을 기다리지 않아야 한다. 해석 시작(조건부 UPDATE), 입력 정보 채우기와 결과 저장(행 잠금 조회), 오래 멈춘
	 * 결과 되돌리기(상태·시각 범위 UPDATE)가 한꺼번에 돌아도 잠금 대기 초과나 교착, 커넥션 대기 초과가 없어야 한다.
	 *
	 * <p>해석 실행은 일시적 DB 오류를 두 번까지 다시 시도하고, 그때마다 WARN 을 남긴다. 결과가 모두 COMPLETED 여도 다시 시도해서 겨우
	 * 성공했을 수 있으므로, 해석 실행과 컨트롤러가 WARN 이상을 한 줄도 남기지 않았는지도 본다. 오래 멈춘 결과 되돌리기는 다른 실행이
	 * 남긴 오래된 행을 되돌리면 WARN 을 남기므로, 거기서는 ERROR 만 본다.
	 */
	@Test
	@DisplayName("서로 다른 결제 50건을 10개 스레드로 한꺼번에 요청하고 사이사이 오래 멈춘 결과 되돌리기를 돌려도 잠금 오류나 커넥션 대기 초과 없이 모두 COMPLETED 가 된다")
	void manyPaymentsCompleteWithoutLockOrConnectionErrors() {
		// given
		userRepository.save(User.create(username, "흐름", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false));
		List<Long> paymentIds = paymentIds(1, REQUEST_THREADS * PAYMENTS_PER_THREAD);
		paymentIds.forEach(this::saveSajuRow);
		given(openAiResponsesClient.createResponse(any())).willReturn(BUSINESS_JSON);
		ListAppender<ILoggingEvent> logs = captureLogsOf(InterpretationPipeline.class, ManseryeokController.class,
			StaleProcessingResultScheduler.class);

		// when
		List<CallResult<List<HttpStatusCode>>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_THREADS,
			thread -> () -> requestEachThenRevertStale(paymentIdsOfThread(thread)));

		// then
		try {
			assertThat(calls).as("요청 스레드").allSatisfy(call -> {
				assertThat(call.error()).as("요청 스레드가 던진 예외").isNull();
				assertThat(call.value()).as("요청마다 받은 응답").containsOnly(HttpStatus.ACCEPTED);
			});
			await().atMost(MANY_FLOWS_WAIT).untilAsserted(() ->
				assertThat(countSajuRows("COMPLETED")).as("COMPLETED 인 결과").isEqualTo(paymentIds.size()));
			assertThat(messages(logs, InterpretationPipeline.class, Level.WARN))
				.as("해석 실행이 남긴 WARN 이상 로그(일시적 DB 오류로 다시 시도, 되돌리기, 결과 쓰기 거부)").isEmpty();
			assertThat(messages(logs, ManseryeokController.class, Level.WARN))
				.as("컨트롤러가 남긴 WARN 이상 로그(제출 거부)").isEmpty();
			assertThat(messages(logs, StaleProcessingResultScheduler.class, Level.ERROR))
				.as("오래 멈춘 결과 되돌리기가 남긴 ERROR 로그").isEmpty();
		} finally {
			stopCapturingLogs(logs, InterpretationPipeline.class, ManseryeokController.class,
				StaleProcessingResultScheduler.class);
		}
	}

	/** 결제마다 해석을 요청하고, 요청 사이사이에 오래 멈춘 결과 되돌리기를 한 번씩 돌린다. 요청마다 받은 응답 코드를 돌려준다. */
	private List<HttpStatusCode> requestEachThenRevertStale(List<Long> paymentIds) {
		List<HttpStatusCode> statuses = new ArrayList<>();
		for (Long paymentId : paymentIds) {
			statuses.add(controller.interpret(SAJU_SUBCATEGORY_ID, interpretBody(paymentId), username).getStatusCode());
			staleProcessingResultScheduler.revertStaleProcessingResults();
		}
		return statuses;
	}

	/**
	 * OpenAI 호출에 들어오면 called 를 내리고, release 가 풀릴 때까지 해석 스레드를 붙잡았다가 json 을 돌려준다.
	 */
	private void holdOpenAiCalls(CountDownLatch called, CountDownLatch release, String json) {
		given(openAiResponsesClient.createResponse(any())).willAnswer(invocation -> {
			called.countDown();
			assertThat(release.await(30, SECONDS)).as("붙잡은 OpenAI 호출이 풀렸다").isTrue();
			return json;
		});
	}

	/**
	 * pool 의 남은 스레드와 대기열을 release 가 풀릴 때까지 멈춰 있는 작업으로 채운다. 풀 크기를 적어 두지 않고 거부될 때까지 넣으므로
	 * 풀 설정(AsyncConfig)이 바뀌어도 그대로 쓴다.
	 */
	private static void fillUntilRejected(Executor pool, CountDownLatch release) {
		for (int submitted = 0; submitted < 10_000; submitted++) {
			try {
				pool.execute(() -> awaitQuietly(release));
			} catch (RejectedExecutionException full) {
				return;
			}
		}
		throw new IllegalStateException("작업을 10,000개 넣어도 풀이 거부하지 않았다. 풀의 대기열에 한도가 있는지 확인한다.");
	}

	private static void awaitQuietly(CountDownLatch release) {
		try {
			release.await(30, SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Long paymentId(long sequence) {
		return runKey + sequence;
	}

	/** first 번째부터 last 번째까지의 결제 ID. */
	private List<Long> paymentIds(long first, long last) {
		return LongStream.rangeClosed(first, last).mapToObj(this::paymentId).toList();
	}

	/** 요청 스레드(0 부터)가 맡는 결제 ID. 스레드마다 PAYMENTS_PER_THREAD 개씩 겹치지 않게 나눈다. */
	private List<Long> paymentIdsOfThread(int thread) {
		long first = (long) thread * PAYMENTS_PER_THREAD + 1;
		return paymentIds(first, first + PAYMENTS_PER_THREAD - 1);
	}

	private void saveSajuRow(Long paymentId) {
		resultRepository.save(Result.createInitial(userId, paymentId, "사주 " + runId));
	}

	/** 사주 결과를 만들고 해석을 시작해, 해석을 시작한 시각을 돌려준다. 컨트롤러가 해석을 제출하기 직전과 같은 상태다. */
	private LocalDateTime startSaju(Long paymentId) {
		saveSajuRow(paymentId);
		return resultService.startProcessing(paymentId);
	}

	private LocalDateTime startCompatibility(Long paymentId) {
		compatibilityResultRepository.save(CompatibilityResult.createInitial(userId, paymentId, "궁합 " + runId));
		return resultService.startCompatibilityProcessing(paymentId);
	}

	private SajuInterpretationCommand sajuCommand(Long paymentId, LocalDateTime startedAt,
		InterpretationProduct product) {
		return new SajuInterpretationCommand(paymentId, startedAt, product, username,
			PromptContext.of("홍길동", PromptFixtures.person1()));
	}

	private CompatibilityInterpretationCommand compatibilityCommand(Long paymentId, LocalDateTime startedAt,
		InterpretationProduct product) {
		return new CompatibilityInterpretationCommand(paymentId, startedAt, product, username,
			CompatibilityPromptContext.of("홍길동", PromptFixtures.person1(), "김영희", PromptFixtures.person2()));
	}

	private RequestBuilder interpretRequest(Long paymentId) {
		return post("/api/v1/manseryeok/interpret/{subcategoryId}", SAJU_SUBCATEGORY_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"홍길동\",\"solarDate\":\"1990-01-01\",\"solarTime\":\"12:00\","
				+ "\"gender\":\"MALE\",\"isLunar\":false,\"paymentId\":" + paymentId + "}");
	}

	private static ManseInterpretationRequest interpretBody(Long paymentId) {
		ManseInterpretationRequest request = new ManseInterpretationRequest();
		request.setName("홍길동");
		request.setSolarDate(LocalDate.of(1990, 1, 1));
		request.setGender("MALE");
		request.setIsLunar(false);
		request.setPaymentId(paymentId);
		return request;
	}

	private void awaitSajuStatus(Long paymentId, String expected) {
		await().atMost(FLOW_WAIT).untilAsserted(() ->
			assertThat(sajuStatus(paymentId)).as("결제 %d 의 사주 결과 상태", paymentId).isEqualTo(expected));
	}

	private void awaitCompatibilityStatus(Long paymentId, String expected) {
		await().atMost(FLOW_WAIT).untilAsserted(() ->
			assertThat(jdbcTemplate.queryForObject("SELECT status FROM compatibility_results WHERE payment_id = ?",
				String.class, paymentId)).as("결제 %d 의 궁합 결과 상태", paymentId).isEqualTo(expected));
	}

	private String sajuStatus(Long paymentId) {
		return jdbcTemplate.queryForObject("SELECT status FROM results WHERE payment_id = ?", String.class,
			paymentId);
	}

	private String sajuText(Long paymentId) {
		return jdbcTemplate.queryForObject("SELECT interpretation FROM results WHERE payment_id = ?", String.class,
			paymentId);
	}

	/** 궁합 결과의 본문, 점수, 요약. */
	private List<Object> compatibilityColumns(Long paymentId) {
		return jdbcTemplate.queryForObject(
			"SELECT interpretation, compatibility_score, summary FROM compatibility_results WHERE payment_id = ?",
			(row, rowNum) -> List.of(row.getString(1), row.getInt(2), row.getString(3)), paymentId);
	}

	private int countSajuRows(String status) {
		Integer count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM results WHERE user_id = ? AND status = ?", Integer.class, userId, status);
		return count == null ? 0 : count;
	}

	/** 여러 스레드가 남기는 로그를 한 목록에 모은다. WARN 과 ERROR 는 로그 설정과 상관없이 모인다. */
	private static ListAppender<ILoggingEvent> captureLogsOf(Class<?>... sources) {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		for (Class<?> source : sources) {
			((Logger) LoggerFactory.getLogger(source)).addAppender(appender);
		}
		return appender;
	}

	/** source 가 남긴 로그 중 minimum 이상 수준의 메시지. */
	private static List<String> messages(ListAppender<ILoggingEvent> appender, Class<?> source, Level minimum) {
		return appender.list.stream()
			.filter(event -> event.getLoggerName().equals(source.getName()))
			.filter(event -> event.getLevel().isGreaterOrEqual(minimum))
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private static void stopCapturingLogs(ListAppender<ILoggingEvent> appender, Class<?>... sources) {
		for (Class<?> source : sources) {
			((Logger) LoggerFactory.getLogger(source)).detachAppender(appender);
		}
		appender.stop();
	}
}
