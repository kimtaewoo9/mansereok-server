package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.OgImageGenerationService;
import com.mansereok.server.domain.interpret.service.S3UploadService;
import com.mansereok.server.support.fixture.ResultFixture;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @Async 에 적은 실행자 이름이 AsyncConfig 의 풀 빈과 실제로 이어지는지 스프링 컨텍스트를 띄워 확인한다. 이름이 어긋나면
 * 스프링은 첫 호출 때에야 빈을 찾지 못해 요청 스레드에서 NoSuchBeanDefinitionException 을 던진다. 문자열만 비교하는 테스트로는
 * 이 어긋남을 잡지 못한다.
 */
@DisplayName("@Async 실행자 연결")
class AsyncExecutorWiringTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(AsyncConfig.class);

	@Test
	@DisplayName("유료·무료·이름 없는 @Async 는 각각 GptAsync-·GptFree-·DefaultAsync- 스레드에서 돈다")
	void asyncNamesRunOnMatchingPools() {
		contextRunner.withBean(ThreadNameProbe.class).run(context -> {
			ThreadNameProbe probe = context.getBean(ThreadNameProbe.class);

			assertThat(probe.onPaidPool().get(5, TimeUnit.SECONDS)).startsWith("GptAsync-");
			assertThat(probe.onFreePool().get(5, TimeUnit.SECONDS)).startsWith("GptFree-");
			assertThat(probe.onDefaultPool().get(5, TimeUnit.SECONDS)).startsWith("DefaultAsync-");
		});
	}

	@Test
	@DisplayName("해석 서비스의 @Async 에 적힌 실행자 이름은 모두 컨텍스트의 Executor 빈으로 찾아진다")
	void interpretationExecutorNamesResolve() {
		List<String> executorNames = asyncExecutorNamesOf(ManseInterpretationService.class);

		assertThat(executorNames).containsExactlyInAnyOrder("gptTaskExecutor", "gptFreeTaskExecutor");
		contextRunner.run(context -> assertThat(executorNames)
			.allSatisfy(name -> assertThat(context.getBean(name, Executor.class)).isNotNull()));
	}

	/**
	 * OG 이미지는 해석 작업 스레드가 결과를 저장한 직후 그 자리에서 만든다. @Async 가 다시 붙으면 기본 풀로 넘어가 풀이 가득 찼거나
	 * 종료 중일 때 버려질 수 있다. @EnableAsync 가 켜진 컨텍스트에서 부른 스레드와 업로드한 스레드가 같은지 본다.
	 */
	@Test
	@DisplayName("OG 이미지 서비스는 비동기로 넘기지 않고 부른 스레드에서 바로 업로드한다")
	void ogImageRunsOnCallingThread() {
		S3UploadService s3UploadService = mock(S3UploadService.class);
		AtomicReference<String> uploadThread = new AtomicReference<>();
		given(s3UploadService.uploadFileAndGetPublicUrl(any(byte[].class), anyString(), anyString()))
			.willAnswer(invocation -> {
				uploadThread.set(Thread.currentThread().getName());
				return "https://example.com/og.png";
			});

		contextRunner
			.withBean(S3UploadService.class, () -> s3UploadService)
			.withBean(ResultRepository.class, () -> mock(ResultRepository.class))
			.withBean(CompatibilityResultRepository.class, () -> mock(CompatibilityResultRepository.class))
			.withBean(OgImageGenerationService.class)
			.run(context -> {
				context.getBean(OgImageGenerationService.class).generateAndUploadOgImage(completedResult());

				assertThat(uploadThread.get()).isEqualTo(Thread.currentThread().getName());
			});
	}

	private static List<String> asyncExecutorNamesOf(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods())
			.map(method -> method.getAnnotation(Async.class))
			.filter(Objects::nonNull)
			.map(Async::value)
			.distinct()
			.toList();
	}

	private static Result completedResult() {
		Result result = ResultFixture.saju(1L, 100L, ResultStatus.COMPLETED);
		ReflectionTestUtils.setField(result, "id", 7L);
		return result;
	}

	/** 자기가 돈 스레드 이름을 돌려주는 시험용 빈. */
	static class ThreadNameProbe {

		@Async("gptTaskExecutor")
		public CompletableFuture<String> onPaidPool() {
			return CompletableFuture.completedFuture(Thread.currentThread().getName());
		}

		@Async("gptFreeTaskExecutor")
		public CompletableFuture<String> onFreePool() {
			return CompletableFuture.completedFuture(Thread.currentThread().getName());
		}

		@Async
		public CompletableFuture<String> onDefaultPool() {
			return CompletableFuture.completedFuture(Thread.currentThread().getName());
		}
	}
}
