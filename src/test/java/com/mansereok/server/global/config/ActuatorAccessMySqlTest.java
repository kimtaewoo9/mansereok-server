package com.mansereok.server.global.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.support.LocalMySqlTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 애플리케이션 전체를 띄워 actuator 가 health 만 누구나 부를 수 있고, 지표(prometheus)는 수집 토큰이 있어야 하며, metrics 는 아예
 * 노출되지 않는지 실제 요청으로 확인한다. 보안 규칙(SecurityConfig)과 노출 설정(application.yml 의 management)이 함께 맞아야
 * 통과한다.
 *
 * <p>health 가 DB 연결 상태를 보므로 실제 MySQL 에 붙는 {@link LocalMySqlTest} 위에서 돈다. 스프링 부트 테스트는 기본으로 지표
 * 내보내기를 꺼서 /actuator/prometheus 를 만들지 않으므로 {@link AutoConfigureObservability} 로 켠다. DB 에 행을 만들지 않으므로
 * 뒤 정리가 없다.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@TestPropertySource(properties = "app.management.scrape-token=" + ActuatorAccessMySqlTest.SCRAPE_TOKEN)
class ActuatorAccessMySqlTest extends LocalMySqlTest {

	static final String SCRAPE_TOKEN = "actuator-access-test-scrape-token";

	@Autowired
	private MockMvc mockMvc;

	@Nested
	@DisplayName("로그인하지 않은 요청이")
	class Anonymous {

		@Test
		@DisplayName("health 를 부르면 200 과 상태만 받고 DB·디스크 같은 구성 요소 상세는 받지 않는다")
		void healthShowsStatusOnly() throws Exception {
			// when
			ResultActions result = mockMvc.perform(get("/actuator/health"));

			// then
			result.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
		}

		@Test
		@DisplayName("prometheus 를 부르면 401 로 막힌다")
		void prometheusIsRejected() throws Exception {
			// when
			ResultActions result = mockMvc.perform(get("/actuator/prometheus"));

			// then
			result.andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@DisplayName("수집 토큰을 실은 요청이")
	class WithScrapeToken {

		@Test
		@DisplayName("prometheus 를 부르면 200 과 environment=local 태그가 붙은 지표를 받는다")
		void prometheusReturnsMetrics() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer " + SCRAPE_TOKEN));

			// then
			result.andExpect(status().isOk())
				.andExpect(content().string(containsString("environment=\"local\"")));
		}

		@Test
		@DisplayName("metrics 를 부르면 노출하지 않은 경로라 404 를 받는다")
		void metricsIsNotExposed() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + SCRAPE_TOKEN));

			// then
			result.andExpect(status().isNotFound());
		}
	}

	@Nested
	@DisplayName("다른 토큰을 실은 요청이")
	class WithWrongToken {

		@Test
		@DisplayName("prometheus 를 부르면 401 로 막힌다")
		void prometheusIsRejected() throws Exception {
			// when
			ResultActions result = mockMvc.perform(
				get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"));

			// then
			result.andExpect(status().isUnauthorized());
		}
	}
}
