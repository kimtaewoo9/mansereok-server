package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 환경별 설정 파일(application.yml 은 로컬, -dev, -prod)에 적힌 actuator 공개 범위, 지표 환경 태그, JPA 방언 설정을 고정한다.
 *
 * <p>스프링을 띄우지 않고 yml 파일만 읽는다. 운영·개발 설정은 테스트에서 띄울 수 없어, 파일에 적힌 값을 직접 확인한다.
 */
class ProfileConfigFileTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
	@DisplayName("모든 환경이 actuator 중 health 와 prometheus 만 노출하고 metrics 는 노출하지 않는다")
	void exposesOnlyHealthAndPrometheus(String fileName) {
		// when
		Properties properties = load(fileName);

		// then
		assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
			.as(fileName).isEqualTo("health,prometheus");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
	@DisplayName("모든 환경이 health 응답에 DB·디스크 같은 상세를 싣지 않는다")
	void hidesHealthDetails(String fileName) {
		// when
		Properties properties = load(fileName);

		// then
		assertThat(properties.getProperty("management.endpoint.health.show-details"))
			.as(fileName).isEqualTo("never");
	}

	@ParameterizedTest(name = "{0} → environment={1}")
	@CsvSource(textBlock = """
		application.yml,      local
		application-dev.yml,  dev
		application-prod.yml, prod
		""")
	@DisplayName("지표에 붙는 environment 태그가 그 파일의 환경 이름과 같다")
	void tagsMetricsWithOwnEnvironment(String fileName, String expectedEnvironment) {
		// when
		Properties properties = load(fileName);

		// then
		assertThat(properties.getProperty("management.metrics.tags.environment"))
			.as(fileName).isEqualTo(expectedEnvironment);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
	@DisplayName("개발·운영 설정이 JPA 방언을 적지 않아 Hibernate 가 DB 에 맞는 방언을 스스로 고른다")
	void leavesDialectToHibernate(String fileName) {
		// when
		Properties properties = load(fileName);

		// then
		assertThat(properties).as(fileName).doesNotContainKey("spring.jpa.database-platform");
	}

	@Test
	@DisplayName("수집 토큰은 환경변수 MANAGEMENT_SCRAPE_TOKEN 에서 읽고, 없으면 비워 두어 지표 요청을 막는다")
	void readsScrapeTokenFromEnvironmentVariable() {
		// when
		Properties properties = load("application.yml");

		// then
		assertThat(properties.getProperty("app.management.scrape-token"))
			.isEqualTo("${MANAGEMENT_SCRAPE_TOKEN:}");
	}

	private static Properties load(String fileName) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource(fileName));
		Properties properties = yaml.getObject();
		assertThat(properties).as(fileName).isNotNull();
		return properties;
	}
}
