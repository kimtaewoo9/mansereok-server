package com.mansereok.server.domain.interpret.client;

/**
 * 재시도 백오프 대기. 테스트가 실제로 기다리지 않도록 주입 가능한 형태로 분리했다.
 */
@FunctionalInterface
public interface BackoffSleeper {

	void sleep(long millis) throws InterruptedException;

	static BackoffSleeper threadSleep() {
		return Thread::sleep;
	}
}
