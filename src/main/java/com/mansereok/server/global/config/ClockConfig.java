package com.mansereok.server.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간에 의존하는 로직이 테스트에서 고정 시각(Clock.fixed)을 주입받을 수 있도록 Clock 을 빈으로 둔다.
 * 운영에서는 LocalDateTime.now() 와 같은 시스템 기본 시간대를 사용한다.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
