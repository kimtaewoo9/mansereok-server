package com.mansereok.server.support;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.support.ConcurrentCalls.CallResult;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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

	@Test
	@DisplayName("동시 요청 수가 1보다 작으면 스레드를 만들지 않고 거절한다")
	void rejectsCountBelowOne() {
		assertThatThrownBy(() -> ConcurrentCalls.runAtTheSameTime(0, () -> "요청"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("동시 요청 수는 1 이상이어야 한다: 0");
	}
}
