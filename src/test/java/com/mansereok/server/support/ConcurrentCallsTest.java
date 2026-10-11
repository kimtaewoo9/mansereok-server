package com.mansereok.server.support;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.support.ConcurrentCalls.CallResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConcurrentCallsTest {

	@Nested
	@DisplayName("출발 시점")
	class StartTiming {

		@Test
		@DisplayName("요청 5개가 모두 동시에 떠 있어야 넘을 수 있는 장벽을 2초 안에 모두 넘는다")
		void allTasksRunAtTheSameTime() {
			// given: 다섯 스레드가 모두 도착해야 열리는 장벽. 한 스레드라도 늦게 출발하면 2초 뒤 시간 초과로 깨진다
			CyclicBarrier barrier = new CyclicBarrier(5);

			// when
			List<CallResult<Integer>> results = ConcurrentCalls.runAtTheSameTime(5,
				() -> barrier.await(2, SECONDS));

			// then
			assertThat(results).hasSize(5)
				.allSatisfy(result -> assertThat(result.error()).as("장벽에서 깨진 요청이 없어야 한다").isNull());
		}

		@Test
		@DisplayName("모든 요청이 출발선에 선 뒤에야 본문을 시작한다")
		void noTaskStartsBeforeAllTasksAreReady() {
			// given: 작업을 만들 때마다 수를 올리고, 본문은 시작한 순간의 수를 돌려준다
			AtomicInteger createdTasks = new AtomicInteger();

			// when
			List<CallResult<Integer>> results = ConcurrentCalls.runAtTheSameTime(5, index -> {
				createdTasks.incrementAndGet();
				return createdTasks::get;
			});

			// then: 먼저 만든 작업도 다섯 개가 다 만들어진 뒤에 본문을 시작했다
			assertThat(results).extracting(CallResult::value).containsExactly(5, 5, 5, 5, 5);
		}
	}

	@Nested
	@DisplayName("결과 모으기")
	class CollectingResults {

		@Test
		@DisplayName("한 요청이 예외를 던져도 삼키지 않고, 나머지 요청의 값과 그 예외를 요청 순서대로 모두 담는다")
		void keepsEveryValueAndException() {
			// given
			IllegalStateException failure = new IllegalStateException("요청 2 실패");
			List<Callable<String>> tasks = List.of(
				() -> "요청 0",
				() -> "요청 1",
				() -> {
					throw failure;
				},
				() -> "요청 3",
				() -> "요청 4");

			// when
			List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(5, tasks::get);

			// then
			assertThat(results).extracting(CallResult::value)
				.containsExactly("요청 0", "요청 1", null, "요청 3", "요청 4");
			assertThat(results).extracting(CallResult::succeeded)
				.containsExactly(true, true, false, true, true);
			assertThat(results.get(2).error()).isSameAs(failure);
		}

		@Test
		@DisplayName("요청 번호를 받는 작업에는 0 부터 count - 1 까지 번호를 한 번씩 넘긴다")
		void passesEachIndexOnce() {
			// when
			List<CallResult<Integer>> results = ConcurrentCalls.runAtTheSameTime(4, index -> () -> index);

			// then
			assertThat(results).extracting(CallResult::value).containsExactly(0, 1, 2, 3);
		}
	}

	@Nested
	@DisplayName("결과 한도를 넘긴 요청이 있으면")
	class WhenARequestRunsPastTheLimit {

		@Test
		@DisplayName("그 요청은 TimeoutException 결과가 되고, 중단 신호를 받고도 도는 그 요청이 끝날 때까지 기다렸다가 돌려준다")
		void waitsForTimedOutRequestToFinish() {
			// given: 요청 1은 결과 한도 동안 끝나지 않고, 중단 신호를 받은 뒤에도 200ms 더 돈 다음 끝난다
			AtomicBoolean slowRequestFinished = new AtomicBoolean();
			List<Callable<String>> tasks = List.of(
				() -> "요청 0",
				() -> {
					keepRunningAfterInterrupt(Duration.ofMillis(200));
					slowRequestFinished.set(true);
					return "요청 1";
				});

			// when
			List<CallResult<String>> results = ConcurrentCalls.runAtTheSameTime(2, tasks::get,
				Duration.ofMillis(100), Duration.ofSeconds(10));

			// then
			assertThat(results.get(0).value()).isEqualTo("요청 0");
			assertThat(results.get(1).error()).isInstanceOf(TimeoutException.class);
			assertThat(slowRequestFinished)
				.as("시간을 넘긴 요청이 끝난 뒤에 돌아와야 그 요청이 테스트의 뒤 정리보다 늦게 커밋하지 않는다")
				.isTrue();
		}

		@Test
		@DisplayName("그 요청이 멈춤 대기 한도 안에도 끝나지 않으면 행이 남을 수 있다고 알리는 예외를 던진다")
		void failsWhenTimedOutRequestDoesNotStop() {
			// given: 중단 신호를 받아도 멈추지 않고 테스트가 풀어 줄 때까지 도는 요청
			CountDownLatch release = new CountDownLatch(1);
			Callable<String> stuckRequest = () -> {
				awaitIgnoringInterrupt(release);
				return "요청 0";
			};

			try {
				// when & then
				assertThatThrownBy(() -> ConcurrentCalls.runAtTheSameTime(1, index -> stuckRequest,
					Duration.ofMillis(100), Duration.ofMillis(100)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("결과 한도를 넘긴 요청이 중단 신호를 보낸 뒤 100ms 안에 끝나지 않았다. "
						+ "테스트의 뒤 정리가 끝난 뒤에 커밋해 행을 남길 수 있으니 DB 를 확인한다.");
			} finally {
				release.countDown();
			}
		}
	}

	@Nested
	@DisplayName("실패 메시지에 넣을 요청별 결과")
	class DescribingResults {

		@Test
		@DisplayName("성공은 '성공', 실패는 '예외 클래스 이름(예외 메시지)' 로 적어 요청 순서대로 한 줄에 묶는다")
		void describesEachResultInRequestOrder() {
			// given: 값이 없는 성공(Void 작업)도 성공으로 적는다
			List<CallResult<String>> results = List.of(
				new CallResult<>("요청 0", null),
				new CallResult<>(null, new IllegalStateException("요청 1 실패")),
				new CallResult<>(null, null));

			// when
			String description = ConcurrentCalls.describe(results);

			// then
			assertThat(description).isEqualTo("요청별 결과 [성공, IllegalStateException(요청 1 실패), 성공]");
		}
	}

	@Test
	@DisplayName("동시 요청 수가 1보다 작으면 스레드를 만들지 않고 거절한다")
	void rejectsCountBelowOne() {
		assertThatThrownBy(() -> ConcurrentCalls.runAtTheSameTime(0, () -> "요청"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("동시 요청 수는 1 이상이어야 한다: 0");
	}

	/**
	 * 중단 신호를 받기 전에는 끝나지 않고, 받은 뒤에도 extra 만큼 더 돈 다음 끝난다. JDBC 호출이 중단 신호로 바로 멈추지 않는
	 * 것을 흉내 낸다.
	 */
	private static void keepRunningAfterInterrupt(Duration extra) throws InterruptedException {
		try {
			new CountDownLatch(1).await();
		} catch (InterruptedException interrupted) {
			CountDownLatch done = new CountDownLatch(1);
			CompletableFuture.delayedExecutor(extra.toMillis(), MILLISECONDS).execute(done::countDown);
			done.await();
		}
	}

	/** 중단 신호를 한 번 무시하고 release 가 열릴 때까지 기다린다. */
	private static void awaitIgnoringInterrupt(CountDownLatch release) throws InterruptedException {
		try {
			release.await();
		} catch (InterruptedException interrupted) {
			release.await();
		}
	}
}
