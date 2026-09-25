package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonParseException;
import com.mansereok.server.domain.interpret.service.InterpretationPipeline.GptCall;
import com.mansereok.server.domain.interpret.service.InterpretationPipeline.PostStep;
import com.mansereok.server.domain.interpret.service.InterpretationPipeline.ResultStatusHandler;
import com.mansereok.server.global.exception.OpenAiIncompleteResponseException;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 해석 파이프라인이 어느 단계에서 실패해도 상태를 한 번 되돌리고, 되돌리기 실패가 원래 오류를 가리지 않는지 확인한다.
 *
 * <p>결과 단계(ResultStatusHandler)와 GPT 호출은 람다로 갈아 끼우는 가짜를 쓴다. 파이프라인 바깥으로 나가는 결과는 세 가지다.
 * 되돌리기를 몇 번 불렀나, 후처리(OG 이미지·이메일)가 돌았나, 무엇을 로그로 남겼나. @Async 로 도는 해석은 실패를 HTTP 응답으로
 * 돌려주지 못해 운영에서는 로그로만 보므로 로그 내용도 검증한다.
 *
 * <p>저장 재시도 사이의 대기는 실제로 기다리지 않고 요청된 시간만 기록한다.
 */
class InterpretationPipelineTest {

	private static final String FLOW_NAME = "테스트 해석";
	private static final Long PAYMENT_ID = 100L;
	private static final Long RESULT_ID = 7L;
	private static final String GPT_BODY = "GPT 가 쓴 해석 본문";
	private static final String SAVED = "저장된 결과";

	private final List<Long> requestedWaits = new ArrayList<>();
	private final InterpretationPipeline pipeline = new InterpretationPipeline(requestedWaits::add);
	private final FakeResultStatus resultStatus = new FakeResultStatus();
	private final AtomicInteger gptCalls = new AtomicInteger();
	private final List<String> postStepInputs = new ArrayList<>();

	private final Logger pipelineLogger = (Logger) LoggerFactory.getLogger(InterpretationPipeline.class);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void captureLogs() {
		logs.start();
		pipelineLogger.addAppender(logs);
	}

	@AfterEach
	void stopCapturingLogs() {
		pipelineLogger.detachAppender(logs);
		logs.stop();
	}

	@Nested
	@DisplayName("GPT 단계가 실패하면")
	class WhenGptStepFails {

		@Test
		@DisplayName("응답 JSON 파싱이 실패하면 결과를 저장하지 않고 한 번 되돌리며 후처리를 하지 않는다")
		void rollsBackOnceWhenJsonParsingFails() {
			// given
			GptCall<String> unparsableResponse = () -> {
				throw new JsonParseException(null, "평문 응답");
			};

			// when
			run(unparsableResponse);

			// then
			assertThat(resultStatus.saveAttempts).as("저장 시도").isZero();
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
			assertThat(postStepInputs).as("후처리").isEmpty();
			assertThat(errorMessages()).containsExactly("[테스트 해석] GPT 응답 파싱 실패 - resultId: 7");
		}

		@Test
		@DisplayName("토큰 상한에 걸려 응답이 미완성이면 한 번 되돌리고 미완성 이유를 로그에 남긴다")
		void logsReasonWhenResponseIsIncomplete() {
			// given
			GptCall<String> incompleteResponse = () -> {
				throw new OpenAiIncompleteResponseException("max_output_tokens");
			};

			// when
			run(incompleteResponse);

			// then
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
			assertThat(errorMessages())
				.containsExactly("[테스트 해석] 해석 미완성 - reason: max_output_tokens, resultId: 7");
		}
	}

	@Nested
	@DisplayName("결과 단계가 실패하면")
	class WhenResultStepFails {

		/**
		 * 컨트롤러가 이미 결과를 해석 중으로 바꿔 두었으므로 결과 ID 를 받기 전에 실패해도 되돌려야 한다. 되돌리기는 결제 ID 로
		 * 하므로 결과 ID 없이도 부른다.
		 */
		@Test
		@DisplayName("입력 정보 채우기가 실패하면 GPT 를 부르지 않고 결과 ID 없이도 한 번 되돌린다")
		void rollsBackWithoutResultIdWhenMarkInProgressFails() {
			// given
			resultStatus.markInProgress = () -> {
				throw new CannotAcquireLockException("잠금 대기 초과");
			};

			// when
			run(gptReturningBody());

			// then
			assertThat(gptCalls).as("GPT 호출").hasValue(0);
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
		}

		@Test
		@DisplayName("GPT 는 성공했는데 결과 저장이 실패하면 한 번 되돌리고 후처리를 하지 않는다")
		void rollsBackOnceWhenSaveFails() {
			// given
			resultStatus.save = attempt -> {
				throw new RuntimeException("저장 실패");
			};

			// when
			run(gptReturningBody());

			// then
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
			assertThat(postStepInputs).as("후처리").isEmpty();
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.InterpretationPipelineTest#permanentSaveFailures")
		@DisplayName("다시 해도 같은 저장 오류(결과 없음, 상태 불일치)는 다시 시도하지 않고 곧바로 되돌린다")
		void rollsBackImmediatelyOnPermanentSaveFailure(String name, RuntimeException permanentFailure) {
			// given
			resultStatus.save = attempt -> {
				throw permanentFailure;
			};

			// when
			run(gptReturningBody());

			// then
			assertThat(resultStatus.saveAttempts).as("저장 시도").isEqualTo(1);
			assertThat(requestedWaits).as("재시도 대기").isEmpty();
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("결과 저장이 일시적 DB 오류로 실패하면")
	class WhenSaveFailsTemporarily {

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.interpret.service.InterpretationPipelineTest#temporarySaveFailures")
		@DisplayName("200ms 기다렸다가 다시 저장하고, 성공하면 되돌리지 않고 후처리를 이어 간다")
		void retriesAndKeepsGptResult(String name, RuntimeException temporaryFailure) {
			// given
			resultStatus.save = attempt -> {
				if (attempt == 1) {
					throw temporaryFailure;
				}
				return SAVED;
			};

			// when
			run(gptReturningBody());

			// then
			assertThat(resultStatus.saveAttempts).as("저장 시도").isEqualTo(2);
			assertThat(requestedWaits).as("재시도 대기(ms)").containsExactly(200L);
			assertThat(resultStatus.rollbackCount).as("되돌리기").isZero();
			assertThat(postStepInputs).as("후처리에 넘긴 저장 결과").containsExactly(SAVED);
		}

		@Test
		@DisplayName("세 번 모두 실패하면 200ms·400ms 를 기다린 뒤 되돌리고, 로그에 GPT 본문을 남기지 않는다")
		void rollsBackAfterThreeAttemptsWithoutLoggingBody() {
			// given
			resultStatus.save = attempt -> {
				throw new CannotAcquireLockException("잠금 대기 초과 " + attempt);
			};

			// when
			run(gptReturningBody());

			// then
			assertThat(resultStatus.saveAttempts).as("저장 시도").isEqualTo(3);
			assertThat(requestedWaits).as("재시도 대기(ms)").containsExactly(200L, 400L);
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
			assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
				.as("재시도 로그와 최종 실패 로그를 포함한 모든 로그")
				.isNotEmpty()
				.noneMatch(message -> message.contains(GPT_BODY));
		}

		@Test
		@DisplayName("재시도를 기다리는 중에 스레드가 중단되면 더 시도하지 않고 되돌리며 중단 표시를 남겨 둔다")
		void stopsRetryingWhenInterrupted() {
			// given
			InterpretationPipeline interruptedPipeline = new InterpretationPipeline(millis -> {
				throw new InterruptedException("종료 중");
			});
			resultStatus.save = attempt -> {
				throw new CannotAcquireLockException("잠금 대기 초과");
			};

			// when
			boolean interruptFlagLeft;
			try {
				interruptedPipeline.run(FLOW_NAME, resultStatus, () -> {
				}, gptReturningBody(), List.of(recordingPostStep()));
			} finally {
				// 읽으면서 지워, 같은 스레드에서 도는 다음 테스트로 중단 표시가 새지 않게 한다.
				interruptFlagLeft = Thread.interrupted();
			}

			// then
			assertThat(interruptFlagLeft).as("중단 표시").isTrue();
			assertThat(resultStatus.saveAttempts).as("저장 시도").isEqualTo(1);
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("되돌리기도 실패하면")
	class WhenRollbackAlsoFails {

		@Test
		@DisplayName("밖으로 던지지 않고, 원래 오류에 되돌리기 실패를 suppressed 로 붙여 함께 남기며 결제 ID 를 로그에 적는다")
		void keepsOriginalFailureAndRecordsRollbackFailure() {
			// given
			resultStatus.rollback = () -> {
				throw new CannotCreateTransactionException("커넥션을 얻지 못했다");
			};
			GptCall<String> failingGpt = () -> {
				throw new IllegalStateException("GPT 장애");
			};

			// when & then
			assertThatCode(() -> run(failingGpt)).doesNotThrowAnyException();

			// then: 원래 오류 로그에 되돌리기 실패가 suppressed 로 함께 찍힌다
			ILoggingEvent failureLog = errorLogStartingWith("[테스트 해석] 해석 중 오류 발생");
			assertThat(failureLog.getThrowableProxy().getMessage()).isEqualTo("GPT 장애");
			assertThat(failureLog.getThrowableProxy().getSuppressed())
				.extracting(IThrowableProxy::getMessage)
				.containsExactly("커넥션을 얻지 못했다");

			// then: 결과가 해석 중으로 남았다는 한 줄이 결제 ID 와 함께 남는다
			assertThat(errorLogStartingWith("[테스트 해석] 상태 되돌리기도 실패").getFormattedMessage())
				.contains("paymentId: 100");
		}
	}

	@Nested
	@DisplayName("Error 가 나면")
	class WhenErrorIsThrown {

		@Test
		@DisplayName("StackOverflowError 도 되돌리기를 한 번 시도한 뒤 삼키지 않고 그대로 던진다")
		void rollsBackAndRethrowsError() {
			// given
			GptCall<String> overflowingGpt = () -> {
				throw new StackOverflowError("재귀 과다");
			};

			// when & then
			assertThatThrownBy(() -> run(overflowingGpt))
				.isInstanceOf(StackOverflowError.class)
				.hasMessage("재귀 과다");
			assertThat(resultStatus.rollbackCount).as("되돌리기").isEqualTo(1);
		}
	}

	static Stream<Arguments> temporarySaveFailures() {
		return Stream.of(
			Arguments.of("잠금 대기 초과", new CannotAcquireLockException("잠금 대기 초과")),
			Arguments.of("커넥션을 얻지 못함", new CannotCreateTransactionException("커넥션을 얻지 못했다")));
	}

	static Stream<Arguments> permanentSaveFailures() {
		return Stream.of(
			Arguments.of("결과 없음", new EntityNotFoundException("Result not found: 7")),
			Arguments.of("해석 중이 아님", new IllegalStateException("해석 중(PROCESSING)인 결과에만 저장할 수 있다")));
	}

	private void run(GptCall<String> gptCall) {
		pipeline.run(FLOW_NAME, resultStatus, () -> {
		}, gptCall, List.of(recordingPostStep()));
	}

	private GptCall<String> gptReturningBody() {
		return () -> {
			gptCalls.incrementAndGet();
			return GPT_BODY;
		};
	}

	private PostStep<String> recordingPostStep() {
		return new PostStep<>("OG 이미지 생성", postStepInputs::add);
	}

	private List<String> errorMessages() {
		return logs.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private ILoggingEvent errorLogStartingWith(String prefix) {
		return logs.list.stream()
			.filter(event -> event.getLevel() == Level.ERROR)
			.filter(event -> event.getFormattedMessage().startsWith(prefix))
			.findFirst()
			.orElseThrow(() -> new AssertionError(prefix + " 로 시작하는 ERROR 로그가 없다. 남은 로그: " + logs.list));
	}

	/**
	 * 결과 단계를 람다로 갈아 끼우는 가짜. 기본은 모두 성공이다. 저장은 몇 번째 시도인지 받아 시도마다 다르게 답할 수 있다.
	 */
	private static final class FakeResultStatus implements ResultStatusHandler<String, String> {

		private Supplier<Long> markInProgress = () -> RESULT_ID;
		private SaveAttempt save = attempt -> SAVED;
		private Runnable rollback = () -> {
		};

		private int saveAttempts;
		private int rollbackCount;

		@Override
		public Long paymentId() {
			return PAYMENT_ID;
		}

		@Override
		public Long markInProgress() {
			return markInProgress.get();
		}

		@Override
		public String saveFinalResult(Long resultId, String gptResult) {
			assertThat(resultId).as("저장에 넘긴 결과 ID").isEqualTo(RESULT_ID);
			assertThat(gptResult).as("저장에 넘긴 GPT 결과").isEqualTo(GPT_BODY);
			saveAttempts++;
			return save.answer(saveAttempts);
		}

		@Override
		public void rollbackToInitialStatus() {
			rollbackCount++;
			rollback.run();
		}
	}

	@FunctionalInterface
	private interface SaveAttempt {

		String answer(int attempt);
	}
}
