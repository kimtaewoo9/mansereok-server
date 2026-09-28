package com.mansereok.server.support;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * 같은 작업을 여러 스레드에서 한 순간에 출발시키고, 요청마다 돌려준 값이나 던진 예외를 모아 돌려준다.
 *
 * <p>스레드 풀 크기를 요청 수와 같게 두고 래치 두 개로 출발을 맞춘다. ready 는 "모든 스레드가 출발선에 섰다" 를, start 는
 * "지금 출발" 을 뜻한다. 완료 대기용 래치 하나만 쓰면 먼저 만든 스레드가 먼저 끝나 버려 동시 요청이 되지 않는다.
 *
 * <p>예외는 삼키지 않고 요청마다 {@link CallResult} 에 담는다. 최종 행 개수만 세면 "대부분의 요청이 예외로 실패했지만 행은
 * 하나" 같은 회귀를 놓치므로, 테스트는 모든 결과를 확인한다.
 *
 * <p>결과 한도({@value #RESULT_TIMEOUT_SECONDS}초)를 넘긴 요청은 {@link TimeoutException} 을 담은 결과가 된다. 이때 그 요청의
 * 스레드는 아직 돌고 있다. JDBC 호출은 중단 신호(interrupt)로 멈추지 않으므로, 곧바로 돌아가면 그 요청이 테스트의 뒤 정리가
 * 끝난 뒤에 커밋해 행을 남길 수 있다. 그래서 돌려주기 전에 스레드가 모두 끝나기를 최대 {@value #STOP_TIMEOUT_SECONDS}초
 * 기다린다. 이 값은 InnoDB 잠금 대기 기본값(50초)보다 길어, 잠금을 기다리던 요청도 그 안에 오류로 끝난다. 그래도 끝나지 않으면
 * 행이 남을 수 있다는 예외를 던진다.
 *
 * <p>HikariCP 기본 풀은 커넥션 10개다. 요청 수가 이를 넘으면 잠금이 아니라 커넥션을 기다리는 시간을 재게 되므로, 더 늘릴 때는
 * spring.datasource.hikari.maximum-pool-size 도 함께 올린다.
 */
public final class ConcurrentCalls {

	private static final long READY_TIMEOUT_SECONDS = 10;
	private static final long RESULT_TIMEOUT_SECONDS = 30;
	private static final long STOP_TIMEOUT_SECONDS = 60;

	private ConcurrentCalls() {
	}

	/**
	 * 같은 작업 count 개를 한 순간에 출발시킨다.
	 *
	 * @return 요청 순서대로 담은 결과. 크기는 count 와 같다.
	 */
	public static <T> List<CallResult<T>> runAtTheSameTime(int count, Callable<T> task) {
		Objects.requireNonNull(task, "task");
		return runAtTheSameTime(count, index -> task);
	}

	/**
	 * 요청 번호(0 부터 count - 1)마다 다른 작업을 만들어 한 순간에 출발시킨다. 사용자나 주문을 요청마다 다르게 줄 때 쓴다.
	 *
	 * @return 요청 번호 순서대로 담은 결과. 크기는 count 와 같다.
	 * @throws IllegalArgumentException count 가 1보다 작을 때
	 * @throws IllegalStateException    스레드가 {@value #READY_TIMEOUT_SECONDS}초 안에 출발선에 서지 못했을 때, 결과 한도를 넘긴
	 *                                  요청이 {@value #STOP_TIMEOUT_SECONDS}초 더 기다려도 끝나지 않았을 때, 테스트 스레드가
	 *                                  중단됐을 때
	 */
	public static <T> List<CallResult<T>> runAtTheSameTime(int count,
		IntFunction<Callable<T>> taskForIndex) {
		return runAtTheSameTime(count, taskForIndex, Duration.ofSeconds(RESULT_TIMEOUT_SECONDS),
			Duration.ofSeconds(STOP_TIMEOUT_SECONDS));
	}

	/**
	 * 결과 한도와 멈춤 대기 한도를 정해 실행한다. 한도를 넘기는 경로를 짧은 시간으로 검증할 때 쓴다.
	 *
	 * @param resultTimeout 모든 결과를 모으는 데 쓰는 전체 한도. 넘긴 요청은 TimeoutException 결과가 된다.
	 * @param stopTimeout   결과를 모은 뒤, 아직 도는 스레드에 중단 신호를 보내고 끝나기를 기다리는 한도
	 */
	static <T> List<CallResult<T>> runAtTheSameTime(int count, IntFunction<Callable<T>> taskForIndex,
		Duration resultTimeout, Duration stopTimeout) {
		if (count < 1) {
			throw new IllegalArgumentException("동시 요청 수는 1 이상이어야 한다: " + count);
		}
		Objects.requireNonNull(taskForIndex, "taskForIndex");

		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			List<Future<T>> futures = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				Callable<T> task = Objects.requireNonNull(taskForIndex.apply(index), "task " + index);
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			if (!ready.await(READY_TIMEOUT_SECONDS, SECONDS)) {
				throw new IllegalStateException(
					READY_TIMEOUT_SECONDS + "초 안에 스레드 " + count + "개가 출발선에 서지 못했다.");
			}
			start.countDown();
			List<CallResult<T>> results = collectResults(futures, resultTimeout);
			stopAndWait(executor, stopTimeout);
			return results;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 요청 결과를 기다리던 테스트 스레드가 중단됐다.", e);
		} finally {
			// 출발선 시간 초과처럼 결과를 모으기 전에 빠져나온 경우에도 스레드를 멈춘다. 이때는 아직 본문을 시작한 작업이 없다.
			executor.shutdownNow();
		}
	}

	/**
	 * 모든 결과를 합쳐서 timeout 안에 모은다. 시간 안에 끝나지 않은 요청은 {@link TimeoutException} 을 담은 결과가 된다.
	 */
	private static <T> List<CallResult<T>> collectResults(List<Future<T>> futures, Duration timeout)
		throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		List<CallResult<T>> results = new ArrayList<>(futures.size());
		for (Future<T> future : futures) {
			long remaining = Math.max(0, deadline - System.nanoTime());
			try {
				results.add(new CallResult<>(future.get(remaining, NANOSECONDS), null));
			} catch (ExecutionException e) {
				results.add(new CallResult<>(null, e.getCause()));
			} catch (TimeoutException e) {
				results.add(new CallResult<>(null, e));
			}
		}
		return results;
	}

	/**
	 * 아직 도는 스레드에 중단 신호를 보내고, 모두 끝날 때까지 timeout 만큼 기다린다. 모든 요청이 제시간에 끝났다면 바로 돌아온다.
	 *
	 * @throws IllegalStateException timeout 안에 끝나지 않은 스레드가 있을 때
	 */
	private static void stopAndWait(ExecutorService executor, Duration timeout) throws InterruptedException {
		executor.shutdownNow();
		if (!executor.awaitTermination(timeout.toNanos(), NANOSECONDS)) {
			throw new IllegalStateException("결과 한도를 넘긴 요청이 중단 신호를 보낸 뒤 " + timeout.toMillis()
				+ "ms 안에 끝나지 않았다. 테스트의 뒤 정리가 끝난 뒤에 커밋해 행을 남길 수 있으니 DB 를 확인한다.");
		}
	}

	/**
	 * 실패 메시지에 넣을 요청별 결과를 요청 순서대로 한 줄로 만든다.
	 * 예: "요청별 결과 [성공, CouponSoldOutException(선착순 마감되었습니다.), ...]"
	 */
	public static String describe(List<? extends CallResult<?>> results) {
		return results.stream()
			.map(CallResult::describe)
			.collect(joining(", ", "요청별 결과 [", "]"));
	}

	/**
	 * 요청 하나의 결과. 작업이 값을 돌려주면 error 가 null 이고, 예외를 던지면 그 예외가 error 에 담긴다.
	 */
	public record CallResult<T>(T value, Throwable error) {

		public boolean succeeded() {
			return error == null;
		}

		/** 실패 메시지에 넣을 이 요청의 결과. 성공이면 "성공", 실패면 "예외 클래스 이름(예외 메시지)". */
		public String describe() {
			return succeeded() ? "성공" : error.getClass().getSimpleName() + "(" + error.getMessage() + ")";
		}
	}
}
