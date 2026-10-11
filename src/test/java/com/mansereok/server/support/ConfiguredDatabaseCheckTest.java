package com.mansereok.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.support.LocalMySqlTest.ConfiguredDatabaseCheck;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ContextConfiguration;

/**
 * LocalMySqlTest 가 DB 에 처음 붙기 전에 주소를 확인하는지 MySQL 없이 검증한다.
 *
 * <p>커넥션 풀은 첫 커넥션을 요청받을 때 DB 에 붙는다. 그래서 아래 테스트의 "통과" 경우에 아무도 듣지 않는 포트(1)를 써도
 * 컨텍스트가 뜬다면, 확인 과정에서 DB 에 붙지 않았다는 뜻이다.
 */
class ConfiguredDatabaseCheckTest {

	private static final String LOCAL_TEST_URL = "jdbc:mysql://127.0.0.1:1/mansereok_test";

	@Test
	@DisplayName("LocalMySqlTest 는 컨텍스트를 띄울 때 DB 주소 확인(ConfiguredDatabaseCheck)을 먼저 돌리도록 등록해 둔다")
	void localMySqlTestRegistersTheCheck() {
		// when
		ContextConfiguration configuration = AnnotatedElementUtils.findMergedAnnotation(
			LocalMySqlTest.class, ContextConfiguration.class);

		// then
		assertThat(configuration).isNotNull();
		assertThat(configuration.initializers()).contains(ConfiguredDatabaseCheck.class);
	}

	@Nested
	@DisplayName("spring.datasource.url 설정값이")
	class ConfiguredUrl {

		@Test
		@DisplayName("이 PC 의 테스트 스키마가 아니면 빈을 하나도 만들기 전에 멈춘다")
		void rejectsBeforeCreatingBeans() {
			// given
			MockEnvironment environment = new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:mysql://10.0.0.5:3306/mansereok_test");
			GenericApplicationContext context = new GenericApplicationContext();
			context.setEnvironment(environment);

			// when & then
			assertThatThrownBy(() -> new ConfiguredDatabaseCheck().initialize(context))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("로컬 테스트 DB 가 아니다: jdbc:mysql://10.0.0.5:3306/mansereok_test, mansereok_test");
		}
	}

	@Nested
	@DisplayName("커넥션 풀이 실제로 쓸 주소가")
	class ConnectionPoolUrl {

		// 스프링 부트가 만드는 커넥션 풀(HikariDataSource)과 spring.datasource.hikari.* 설정 적용을 그대로 쓴다.
		private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfiguredDatabaseCheck())
			.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
			.withPropertyValues("spring.datasource.url=" + LOCAL_TEST_URL);

		@Test
		@DisplayName("spring.datasource.hikari.jdbc-url 로 다른 스키마를 가리키면 풀을 만든 직후, DB 에 붙기 전에 멈춘다")
		void rejectsPoolUrlOverride() {
			contextRunner
				.withPropertyValues("spring.datasource.hikari.jdbc-url=jdbc:mysql://127.0.0.1:1/mansereok_review_probe")
				.run(context -> assertThat(context).getFailure()
					.rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("로컬 테스트 DB 가 아니다: jdbc:mysql://127.0.0.1:1/mansereok_review_probe, "
						+ "mansereok_review_probe"));
		}

		@Test
		@DisplayName("이 PC 의 테스트 스키마면 DB 에 붙지 않고 컨텍스트가 뜬다")
		void acceptsLocalTestUrl() {
			contextRunner.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(HikariDataSource.class).getJdbcUrl()).isEqualTo(LOCAL_TEST_URL);
			});
		}

		@Test
		@DisplayName("HikariDataSource 가 아닌 DataSource 는 DB 에 붙기 전에 주소를 알 수 없으므로 멈춘다")
		void rejectsOtherDataSourceTypes() {
			new ApplicationContextRunner()
				.withInitializer(new ConfiguredDatabaseCheck())
				.withPropertyValues("spring.datasource.url=" + LOCAL_TEST_URL)
				.withBean("dataSource", DataSource.class, () -> new DriverManagerDataSource(LOCAL_TEST_URL))
				.run(context -> assertThat(context).getFailure()
					.rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessageStartingWith("HikariDataSource 가 아닌 DataSource 는 커넥션을 열기 전에 주소를 확인할 수 없다"));
		}
	}
}
