package com.mansereok.server.domain.interpret.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.mansereok.server.domain.interpret.client.OpenAiProperties;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;

/**
 * 오래 멈춘 해석 결과를 되돌리는 작업이 지키는 약속을 검증한다.
 *
 * <ol>
 *   <li>되돌리는 기준 시각은 주입된 Clock 의 "지금 - staleAfter" 이고, 되돌린 행의 변경 시각은 그 "지금" 이다.</li>
 *   <li>한 표에서 실패해도 예외를 밖으로 던지지 않고 다른 표를 되돌린다.</li>
 *   <li>staleAfter 가 OpenAI 호출 한 건이 가장 오래 걸리는 시간보다 길지 않으면 애플리케이션이 뜨지 않는다.</li>
 * </ol>
 *
 * <p>조건부 UPDATE 가 어떤 행을 되돌리는지는 DB 가 정하므로 ResultStartOnceMySqlTest 가 실제 MySQL 로 본다. 여기서는 리포지토리에
 * 넘기는 두 시각만 본다.
 */
@ExtendWith(MockitoExtension.class)
class StaleProcessingResultSchedulerTest {

	// 2026-09-26 09:00 (서울). 시간대를 명시해 개발자 PC 의 시간대에 결과가 흔들리지 않게 한다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 9, 0);

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Nested
	@DisplayName("되돌리기 작업이 돌면")
	class WhenRun {

		@ParameterizedTest(name = "[{index}] staleAfter {0} → {1} 전에 바뀐 결과")
		@CsvSource(textBlock = """
			# 멈춘 것으로 보는 시간, 되돌리는 기준 시각
			PT60M, 2026-09-26T08:00
			PT90M, 2026-09-26T07:30
			""")
		@DisplayName("두 결과 표 모두 Clock 기준 staleAfter 전보다 먼저 바뀐 해석 중 결과를 지금 시각으로 되돌린다")
		void revertsBothTablesWithClockTimes(Duration staleAfter, LocalDateTime expectedStaleBefore) {
			// given
			StaleProcessingResultScheduler scheduler = schedulerWith(staleAfter);

			// when
			scheduler.revertStaleProcessingResults();

			// then
			then(resultRepository).should().revertProcessingUpdatedBefore(expectedStaleBefore, NOW);
			then(compatibilityResultRepository).should().revertProcessingUpdatedBefore(expectedStaleBefore, NOW);
		}

		@Test
		@DisplayName("사주 결과 표에서 실패해도 예외를 밖으로 던지지 않고 궁합 결과 표를 되돌린다")
		void keepsGoingAfterOneTableFails() {
			// given
			StaleProcessingResultScheduler scheduler = schedulerWith(Duration.ofMinutes(60));
			LocalDateTime staleBefore = LocalDateTime.of(2026, 9, 26, 8, 0);
			given(resultRepository.revertProcessingUpdatedBefore(staleBefore, NOW))
				.willThrow(new QueryTimeoutException("잠금 대기 시간 초과"));

			// when & then
			assertThatCode(scheduler::revertStaleProcessingResults).doesNotThrowAnyException();
			then(compatibilityResultRepository).should().revertProcessingUpdatedBefore(staleBefore, NOW);
		}
	}

	/**
	 * StaleProcessingConfig 가 기동할 때 설정을 확인하는지 본다. OpenAI 기본값이면 호출 한 건이 가장 오래 걸리는 시간은
	 * (10초 + 180초) × 4번 + 30초 × 2번 = 820초(13분 40초)다.
	 */
	@Nested
	@DisplayName("설정은")
	class Settings {

		private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(OpenAiPropertiesRegistration.class, StaleProcessingConfig.class)
			.withPropertyValues("openai.api.key=test-key");

		@Test
		@DisplayName("yml 에 키가 없으면 멈춘 것으로 보는 시간 60분, 확인 간격 5분으로 뜬다")
		void startsWithDefaults() {
			runner.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(StaleProcessingProperties.class))
					.isEqualTo(new StaleProcessingProperties(Duration.ofMinutes(60), Duration.ofMinutes(5)));
			});
		}

		@ParameterizedTest(name = "[{index}] stale-after {0}, 읽기 제한 {1}ms")
		@CsvSource(textBlock = """
			# stale-after, OpenAI 읽기 제한(ms), 가장 오래 걸리는 호출
			PT13M41S,      180000,              13분 40초보다 1초 길다
			PT60M,         600000,              (10초 + 600초) × 4 + 60초 = 41분 40초
			""")
		@DisplayName("멈춘 것으로 보는 시간이 OpenAI 호출 한 건이 가장 오래 걸리는 시간보다 길면 뜬다")
		void startsWhenStaleAfterIsLongerThanLongestCall(String staleAfter, int readTimeoutMs, String reason) {
			runner.withPropertyValues("interpret.stale-processing.stale-after=" + staleAfter,
					"openai.api.read-timeout-ms=" + readTimeoutMs)
				.run(context -> assertThat(context).as(reason).hasNotFailed());
		}

		@ParameterizedTest(name = "[{index}] stale-after {0}, 읽기 제한 {1}ms")
		@CsvSource(textBlock = """
			# stale-after, OpenAI 읽기 제한(ms), 기동 실패 메시지에 들어갈 말
			PT10M,         180000,              stale-after(PT10M)가 OpenAI 호출 한 건이 가장 오래 걸리는 시간(PT13M40S)보다 길지 않다
			PT13M40S,      180000,              stale-after(PT13M40S)가 OpenAI 호출 한 건이 가장 오래 걸리는 시간(PT13M40S)보다 길지 않다
			PT60M,         900000,              stale-after(PT1H)가 OpenAI 호출 한 건이 가장 오래 걸리는 시간(PT1H1M40S)보다 길지 않다
			PT0S,          180000,              interpret.stale-processing.stale-after 는 0 보다 커야 합니다
			""")
		@DisplayName("멈춘 것으로 보는 시간이 OpenAI 호출 한 건이 가장 오래 걸리는 시간보다 길지 않으면 기동에 실패한다")
		void failsWhenStaleAfterIsNotLongerThanLongestCall(String staleAfter, int readTimeoutMs,
			String expectedMessage) {
			runner.withPropertyValues("interpret.stale-processing.stale-after=" + staleAfter,
					"openai.api.read-timeout-ms=" + readTimeoutMs)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedMessage);
				});
		}
	}

	private StaleProcessingResultScheduler schedulerWith(Duration staleAfter) {
		return new StaleProcessingResultScheduler(resultRepository, compatibilityResultRepository,
			new StaleProcessingProperties(staleAfter, Duration.ofMinutes(5)), FIXED_CLOCK);
	}

	/** 애플리케이션에서는 MansereokApplication 이 등록하는 OpenAI 설정을 테스트 컨텍스트에 등록한다. */
	@Configuration
	@EnableConfigurationProperties(OpenAiProperties.class)
	static class OpenAiPropertiesRegistration {

	}
}
